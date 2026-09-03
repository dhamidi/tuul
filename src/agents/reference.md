# Reference: proposed agents contract

This page is the proposed implementation contract for `agents`. No public
implementation exists. The names and message shapes are design vocabulary,
not current Java APIs.

## Scope

The package defines the workspace, members, projects, runs, subagent runs,
turns, tool invocations, executors, the live protocol, the activity feed,
budgets, the run catalogue, the search index, and the archive workflow.

The package does not define a model provider, a tool sandbox, an
authentication provider, or a surface. It uses `identity` for the
authenticated subject and `web.sessions` for the browser session. It uses
`web.cable` and `eventstream` for live updates and `sqlite3` for the search
index.

## Terms

| Term | Meaning |
|---|---|
| deployment | One process, one actor system, one workspace. |
| subject | The opaque local identifier that `identity` resolves. |
| member | One subject with one role in the workspace. |
| role | `owner`, `editor`, or `viewer`. |
| project | One named, versioned recipe: model, instructions, files, tools, limits. |
| run | One conversation with one project version. The unit that is browsed and shared. |
| child run | A run that another run spawned. Its id is nested under the parent's id. |
| turn | One model call and the tool calls it requested. |
| response | The turns from one person's message to the next idle state. |
| invocation | One tool call in flight. |
| executor | Where a run's tools execute: the deployment, or one connected surface. |
| surface | A client that reads the event stream: web, terminal, editor, phone. |
| blob | Content stored by hash outside every journal. |
| hot | A journal under the actor system root. |
| cold | A journal and its blobs at the archive location. |
| tombstone | The catalogue record of a deleted run. |

## Actor topology

| Address pattern | Spawn | State authority |
|---|---|---|
| `workspace` | durable | Name, members, roles, defaults, archive location. |
| `member/{subject}` | durable | One person's preferences. |
| `project/{project}` | durable | Every version of one project. Which version is current. |
| `budget/{yyyy-mm}` | durable, `Durability.full` | Reservations and spend for one month. |
| `runs/{yyyy-mm}` | durable | Which runs opened in one month, their title, owner, project, visibility, status, tier, and tombstones. |
| `run/{run}` | durable, `Durability.full` | The transcript, status, visibility, owner, executor, children, and pending calls of one run. |
| `run/{run}/{child}` | as `run` | The same for one child run. |
| `archive/{run}` | durable | The accepted steps of one move between hot and cold. |
| `search` | ephemeral | None. It writes one derived SQLite file. |
| `activity` | ephemeral | Active runs, connected surfaces, viewers, and typing. |
| `live/{run}` | ephemeral | Partial output of the turn in flight, viewers, and typing for one run. |
| `tool/{run}/{call}` | ephemeral | One process, or one pending request to a surface, and buffered output. |

A run id is a ULID. The catalogue partition is the month of the ULID's
timestamp. A child run id is the parent's id, a slash, and a new ULID.

## Spawn options

| Actor | `keepsLog` | `idle` | `durability` | `redelivers` | `mailbox` |
|---|---|---|---|---|---|
| `workspace` | true | 0, never | normal | false | 1024 |
| `member` | true | 10 min | normal | false | 64 |
| `project` | true | 30 min | normal | false | 256 |
| `budget` | true | 0, never | full | false | 4096 |
| `runs` | true | 1 h | normal | false | 4096 |
| `run` | true | 15 min | full | false | 1024 |
| `archive` | true | 5 min | normal | false | 64 |
| `search` | false | 0, never | none | false | 4096 |
| `activity` | false | 0, never | none | false | 8192 |
| `live` | false | 1 min | none | false | 8192 |
| `tool` | false | 0, never | none | false | 256 |

`redelivers` is false everywhere. A run re-issues its own pending calls on
`actors.resume`. See *Resume rules*.

`Definition.settled` returns true for a `run` in status `closed`, for an
`archive` whose last step completed, and for a `tool` after it reported. It
returns false for an `idle` run, which passivates on the idle timeout
instead. Settled is a passivation hint. It refuses nothing.

## Blob store

The blob store is an effect handler, not an actor. It keeps one file per
content hash under `blobs/{run}/{sha256}` for run content and
`blobs/project/{project}/{sha256}` for project files. A command that refers
to a blob carries `{"blob": "sha256:...", "size": n, "type": "text/plain"}`.

| Limit | Value |
|---|---|
| Inline body in a run command | 64 KiB |
| One blob | 256 MiB |
| Blobs per run | 10,000 |

The `blob.put` effect writes to a temporary file, verifies the digest, and
renames. It returns `blob.stored` with the hash. The `blob.get` effect
streams a blob to a writer. A blob belongs to the run whose prefix holds it
and moves with the run.

## Workspace actor

Commands:

| Command | Body | Rule |
|---|---|---|
| `workspace.create` | `name`, `creator` | First command only. The creator becomes `owner`. |
| `workspace.rename` | `name`, `by` | `owner` only. |
| `member.add` | `subject`, `role`, `by` | `owner` only. Idempotent by `subject`. |
| `member.role` | `subject`, `role`, `by` | `owner` only. The last owner cannot be demoted. |
| `member.remove` | `subject`, `by` | `owner` only. Runs the member owns stay. |
| `workspace.settings` | `defaults`, `by` | `owner` only. Default model, turn limit, monthly budget, default visibility. |
| `workspace.archive-location` | `location`, `by` | `owner` only. Where cold copies go. |

Queries:

| Query | Reply |
|---|---|
| `workspace.role` `{subject}` | `{role}` or `{role: null}`. |
| `workspace.members` | The member list with roles. |
| `workspace.settings` | The defaults. |

The workspace actor holds no run list. The catalogue does. It is never
evicted because every request asks it for a role.

## Member actor

| Command | Body |
|---|---|
| `member.prefer` | `key`, `value`. Idempotent. |
| `member.follow` | `subject`. Adds a member topic to this person's default subscriptions. |
| `member.unfollow` | `subject`. |

Read positions per run are not commands. The search database holds them in a
`positions` table keyed by subject and run. A lost position costs one scroll.

## Project actor

| Command | Body | Rule |
|---|---|---|
| `project.create` | `name`, `by` | First command only. |
| `project.version` | `model`, `instructions`, `files`, `tools`, `limits`, `by` | Appends version `n+1` and makes it current. `files` are blob references. |
| `project.current` | `version`, `by` | Makes an earlier version current. |
| `project.retire` | `by` | Refuses new runs. Existing runs continue. |

Query `project.version {version}` replies with that version. A run records
the version number it started with and never reads a later one.

A `tools` entry is `{name, description, schema, idempotent, timeout,
foreground, background, where}`. `where` is `deployment`, `surface`, or
`any`. `idempotent` decides what a run does when it must re-issue the call.
See *Resume rules*. `foreground` is how long the turn waits for the call
before `background: auto` moves it to the background, thirty seconds by
default. `background` is `never`, `auto`, or `always`.

`limits` is `{context, turns, parallel, wake}`. `parallel` bounds calls in
flight per run, eight by default. `wake` says whether a background result
starts a turn while the run is `idle`, true by default.

## Budget actor

| Command | Body | Rule |
|---|---|---|
| `budget.set` | `limit`, `by` | `owner` only. A new month reads the workspace default on its first reservation. |
| `spend.reserve` | `run`, `turn`, `estimate` | Refused with `budget.exhausted` when the estimate exceeds the remainder. Idempotent by `run` and `turn`. |
| `spend.record` | `run`, `turn`, `input`, `output`, `cost` | Replaces the reservation. Idempotent by `run` and `turn`. |
| `spend.release` | `run`, `turn` | Cancels a reservation the run will not use. |

Query `budget.remaining` replies with the limit, the spend, the open
reservations, and the remainder, in total and per member.

The run addresses the month of `message.at()`. A turn that starts at the end
of a month and ends in the next records its spend in the month it reserved.

## Catalogue actor

The `runs/{yyyy-mm}` actor is the authority for which runs exist.

| Command | Body | Rule |
|---|---|---|
| `runs.open` | `run`, `project`, `version`, `owner`, `title`, `parent`, `executor` | Records the run and tells the run actor `run.open`. Idempotent by `run`. |
| `runs.started` | `run` | The run acknowledges `run.open`. |
| `runs.retitled` | `run`, `title`, `seq` | From the run. Deduplicated by `seq`. |
| `runs.visibility` | `run`, `visibility`, `seq` | From the run. Deduplicated by `seq`. |
| `runs.status` | `run`, `status`, `summary`, `messages`, `cost`, `seq` | From the run on `idle` and `closed`. Deduplicated by `seq`. |
| `runs.archive` | `run`, `by` | `owner` or the run's owner. Refused unless status is `idle` or `closed`. Closes the run. Spawns `archive/{run}`. |
| `runs.archived` | `run`, `location`, `digest` | From the archive actor. |
| `runs.restore` | `run`, `by` | Refused unless tier is `cold`. Spawns the archive actor in reverse. |
| `runs.restored` | `run` | From the archive actor. |
| `runs.delete` | `run`, `by` | `owner` or the run's owner. Erases hot and cold copies and writes a tombstone. |

Queries:

| Query | Reply |
|---|---|
| `runs.list` `{subject, role, filter, after, limit}` | Runs the subject may see, newest first, with a cursor. `filter` selects owner, project, status, or tier. |
| `runs.get` `{run}` | One entry: title, owner, project, version, visibility, status, summary, tier, location, parent, executor. |
| `runs.tree` `{run}` | The entry and its descendants. |

`runs.list` applies visibility from the catalogue's copy. A `workspace` run
is visible to every member. A `private` run is visible to its owner and to
workspace owners. A `link` run is visible to anyone who presents its token.
The catalogue never summons a run to answer a list.

On `actors.resume` the catalogue re-tells `run.open` for every run that has
no `runs.started`.

## Run actor

Status moves among `opening`, `idle`, `thinking`, `acting`, `waiting`, and
`closed`. `thinking` is a model call in flight. `acting` is one or more tool
calls in flight. `waiting` is a run that needs a person: a permission, a
repeat confirmation, or an executor. `idle` is the resting state between
responses. `closed` is terminal and is set only by a person or by archive.

Commands:

| Command | Body | Rule |
|---|---|---|
| `run.open` | `project`, `version`, `owner`, `title`, `parent`, `task`, `executor` | First command only. Any earlier command is refused and changes nothing. |
| `run.say` | `subject`, `text`, `attachments` | `editor` or above, or the owner. Starts a response when `idle`. Queued when a response is in flight. |
| `run.title` | `title`, `by` | Tells the catalogue. |
| `run.share` | `visibility`, `token`, `by` | Owner or workspace owner. Tells the catalogue. |
| `run.attach` | `executor`, `connection`, `by` | Makes a connected surface the executor. Owner only. |
| `run.detach` | `connection` | From the cable when the executor's connection closes. Status becomes `waiting` if a surface tool is pending. |
| `turn.requested` | `turn`, `reason` | The run records that it asked the model. Reserves budget first. |
| `model.answered` | `turn`, `text`, `calls`, `usage`, `stop` | From the model effect. Duplicate `turn` is refused. |
| `model.failed` | `turn`, `error`, `retryable` | From the model effect. A retryable error schedules one retry. |
| `tool.finished` | `turn`, `call`, `result`, `blob`, `error` | From a tool actor. Duplicate `call` is refused. |
| `tool.cancelled` | `call`, `by` | Tells the tool actor `tool.cancel`. Any surface. |
| `call.backgrounded` | `call`, `reason` | `always`, `asked`, or `auto`. The call leaves the foreground set. The context shows the model a placeholder. |
| `call.deadline` | `call` | Scheduled at `tool.start`. A call still pending records `tool.finished` with `error {kind: "lost"}`. Ignored otherwise. |
| `permission.asked` | `call`, `tool`, `arguments` | The run needs a person's approval for this call. Status `waiting`. |
| `permission.answered` | `call`, `allow`, `by`, `remember` | From any surface. `remember` records a rule for this run. |
| `child.spawned` | `child`, `task` | Opens the child through the catalogue. |
| `child.finished` | `child`, `status`, `summary`, `seq` | From the child. Deduplicated by `seq`. |
| `context.compacted` | `turn`, `summary`, `dropped` | From the compaction effect. Replaces earlier turns in the context with the summary. |
| `run.pause` | `by`, `reason` | Refuses new turns. Tool calls in flight finish. |
| `run.resume` | `by` | Starts the next turn if one is due. |
| `run.close` | `by`, `summary` | Cancels tool calls in flight and every child. Terminal. |
| `run.freeze` | `by` | From the archive actor. Refuses every command except `run.thaw` and queries. |
| `run.thaw` | `by` | From the archive actor. |

Queries:

| Query | Reply |
|---|---|
| `run.view` `{subject, role, token, since}` | The transcript view from entry `since` when the subject may read it. `run.forbidden` otherwise. |
| `run.status` | Status, message count, cost, pending calls, executor. |

`Definition.inspect` returns the transcript as JSON with one entry per
recorded command, the status, the pending calls, the executor, and the
children. The transcript entry for a blob carries the hash and the size and
not the content. A surface streams a blob through `blob.get`.

An entry is `{seq, at, kind, by, body}`. `kind` is one of `say`, `answer`,
`result`, `backgrounded`, `permission`, `child`, `status`, `compaction`.
An `answer` entry lists its calls. A `result` entry carries one call's
outcome, error or not. `Entry.of`
maps a recorded command to an entry. The live protocol sends the same shape.

The context the run sends to the model is the transcript after the last
`context.compacted`. Its size is bounded by the model's context window. The
run requests compaction when the context passes the project's
`limits.context`.

Every status change tells `activity` `activity.run {run, status, owner,
turn}`. Every recorded entry tells `live/{run}` `live.entry`.

## Turn protocol

A turn is one model call and the calls it requests. See
[turn.md](turn.md) for the worked example.

1. The run receives `run.say` while `idle`, or the last foreground call of
   the previous turn reports, or a background call finishes while `idle`.
2. The run tells `budget/{yyyy-mm}` `spend.reserve`. A refusal records
   `run.pause` with reason `budget.exhausted`.
3. The run records `turn.requested {turn, reason}` and emits the effect
   `model.complete` with the context, the turn number as the idempotency
   key, and the live address. Status `thinking`.
4. The model effect tells `live/{run}` `live.delta` for each chunk and
   finally emits `model.answered` to the run.
5. The run records the answer, tells the budget `spend.record`, and tells
   the live actor `live.entry`.
6. For each requested call, in list order, the run checks the project's
   permission rules. A call that needs approval records `permission.asked`.
   An allowed call enters the pending table as `foreground`, or `queued`
   when `limits.parallel` calls are already running, or `background` when
   the tool or the model asked for that. The run spawns
   `tool/{run}/{call}`, tells it `tool.start`, and schedules
   `call.deadline {call}` at the tool's `timeout`. Status `acting`.
7. Each tool actor tells the run `tool.finished`. The run records it,
   starts the next `queued` call if any, and checks the table.
8. A foreground call that passes the tool's `foreground` budget records
   `call.backgrounded {call, reason: "auto"}` when the tool allows it. The
   run schedules that check at `tool.start`.
9. When every call is `finished` or `background`, the action phase ends.
   An answer that had calls requests the next turn with every result and
   placeholder in the model's call order. An answer without calls and with
   stop reason `end` tells the catalogue and the activity feed `idle`.

The pending table is part of the run's state. Replay rebuilds it from
`model.answered`, `call.backgrounded`, and `tool.finished`.

## Tool actor

| Command | Body |
|---|---|
| `tool.start` | `name`, `arguments`, `timeout`, `run`, `turn`, `call`, `executor` |
| `tool.cancel` | `by` |
| `tool.output` | `chunk`. From the process effect or the surface. Buffered to 64 KiB, then streamed to a blob. Forwarded to the live actor. |
| `tool.progress` | `fraction`, `message`. From the process effect or the surface. Forwarded to the live actor. Never recorded. |
| `tool.exit` | `code`, `blob`. From the process effect or the surface. |
| `tool.timeout` | Scheduled at `tool.start`. Kills the process or tells the surface to. |

With executor `deployment` the actor emits the tool's process effect. With
executor `surface` it tells `live/{run}` `live.request {call, name,
arguments}`, which the cable sends to the executor's connection as a `tool`
event. The surface posts `tool.output`, `tool.progress`, and `tool.exit`
back over HTTP with the call id.

The actor ends in exactly one way. It tells the run `tool.finished` and
evicts itself. The body is one of:

| Outcome | Body |
|---|---|
| success | `result` inline, or `blob` above 64 KiB, and `duration` |
| tool error | `error {kind: "failed", code, message}` and the output so far |
| timeout | `error {kind: "timeout"}` |
| cancelled | `error {kind: "cancelled", by}` |
| executor gone | `error {kind: "executor-gone"}` |
| denied | `error {kind: "denied", by}` |

A tool actor does not know whether its call is foreground or background.
Backgrounding is a run decision and changes nothing in the tool actor.

## Live actor

`live/{run}` is the fan-out point for one run.

| Command | Body |
|---|---|
| `live.entry` | An entry. From the run. Broadcast as `entry`. |
| `live.delta` | `turn`, `text` or `call`. Appended and broadcast as `delta`. |
| `live.output` | `call`, `chunk`. From a tool actor. Broadcast as `output`. The last 64 KiB per running call is kept for `live.now`. |
| `live.progress` | `call`, `fraction`, `message`. From a tool actor. Broadcast as `progress`. The last one per running call is kept. |
| `live.call` | `call`, `state`. From the run: `started`, `queued`, `backgrounded`, `finished`. Broadcast as `call`. |
| `live.request` | `call`, `name`, `arguments`. Sent as `tool` to the executor's connection only. |
| `live.reset` | `turn`. From the run when it records the answer. |
| `live.join` | `subject`, `connection`, `surface`. Broadcast as `presence`. Tells `activity`. |
| `live.leave` | `subject`, `connection`. Broadcast as `presence`. Tells `activity`. |
| `live.typing` | `subject`, `on`. Broadcast as `typing`. Expires after 5 seconds. |

Query `live.now` replies with the partial output of the current turn, every
running call with its state, output tail, and last progress, the viewers,
and who is typing, so that a surface that opens mid-turn draws the present
state before it follows the stream.

## Activity actor

`activity` is a singleton.

| Command | Body |
|---|---|
| `activity.run` | `run`, `status`, `owner`, `turn`, `title`. From the run on each status change. Broadcast on `workspace` and `member:{owner}`. |
| `activity.viewer` | `run`, `subject`, `on`. From a live actor. Broadcast on `workspace` and `member:{subject}`. |
| `activity.surface` | `subject`, `connection`, `surface`, `on`. From the cable. Broadcast on `workspace`. |

Query `activity.now {subject, role}` replies with every active run the
subject may see, its status, its owner, its viewers, and every connected
member with their surface.

On a restart the actor starts empty. Every loaded run tells it on its next
status change. Every surface reconnects and joins again. The feed is
complete again within one reconnect interval.

## Live protocol

Every surface reads one `text/event-stream` connection from
`Cable.stream(Topics)`. A browser authenticates with the session cookie. A
terminal or editor authenticates with a bearer token that `identity` issues
for the subject.

Topics:

| Topic | Carries |
|---|---|
| `run:{run}` | `entry`, `delta`, `output`, `progress`, `call`, `presence`, `typing`, `status`, and `message` for that run. `tool` to the executor's connection only. |
| `workspace` | `activity` for every visible run and every connected member. |
| `member:{subject}` | `activity` for that member's runs and presence. |

Events:

| Event name | Body | Who reads it |
|---|---|---|
| `message` | A Turbo Stream fragment | The browser, through Turbo's stream source. |
| `entry` | An entry, as `inspect` returns it | Every non-browser surface. |
| `delta` | `{turn, text}` or `{turn, call}` | Every non-browser surface. |
| `output` | `{call, chunk}` | Every non-browser surface. |
| `progress` | `{call, fraction, message}` | Every non-browser surface. |
| `call` | `{call, name, state}` | Every non-browser surface. |
| `presence` | `{viewers: [{subject, surface}]}` | Every surface. |
| `typing` | `{subject, on}` | Every surface. |
| `status` | `{status, turn, pending}` | Every surface. |
| `activity` | `{runs: [...], members: [...]}` | The workspace and member feeds. |
| `tool` | `{call, name, arguments, timeout}` | The executor's connection. |

Every event id is `{topic}:{sequence}`. A reconnecting surface sends
`Last-Event-ID`. The cable replays what its backlog still holds. A backlog
gap answers with a `reload` event, and the surface asks `run.view` with
`since` set to its last sequence.

`Topics.of(request)` reads the subject, asks the workspace for the role, and
asks the catalogue for each requested run's visibility. It answers only the
topics the subject may read. `Topics.query` is never used.

The browser topic and the terminal topic are the same topic. The web
feature renders the `message` fragment from the same entry it sends as
`entry`, at delivery time, so both surfaces see one sequence.

## Executors

A run has one executor at a time.

| Executor | Tools run | When |
|---|---|---|
| `deployment` | In the deployment's sandbox through effect handlers. | A run opened from the web, or from a terminal that chose it. |
| `surface` | On the machine of the surface named by `connection`. | A run opened from a terminal or an editor. |

A tool whose `where` is `surface` cannot run on the deployment executor. A
run whose executor's connection closes moves any pending surface call to
`waiting` and records `run.detach`. The owner attaches a new connection
with `run.attach`. A pending call older than its timeout fails with
`executor.gone`.

Every surface sees a surface-executed tool's output, because the executor
posts it to the tool actor and the tool actor forwards it to the live actor.

## Child runs

A child is opened through the catalogue with `parent` set. It inherits the
parent's executor and visibility. It receives its task in `run.open`. The
child tells the parent `child.finished` with its summary and its own
sequence number. The parent records it and continues.

A parent's `run.close` tells every child `run.close`. A parent's `archive`
moves the subtree. A parent's `delete` deletes the subtree. A child cannot
be archived or deleted on its own.

## Archive protocol

`archive/{run}` records each step as a command with the same request id.

1. `archive.begin` `{run, by, direction}`.
2. Tell the run `run.freeze` and record `archive.frozen` on the reply.
3. Evict the run and its subtree and record `archive.evicted`.
4. Emit `cold.put` for each journal in the subtree. The handler checkpoints
   the write-ahead log, copies the file, and verifies the digest. Record
   `archive.copied {address, digest}` per file. A copy that already exists
   with the same digest is a no-op.
5. Emit `cold.put` for each blob. Record `archive.copied` per blob.
6. Tell the catalogue `runs.archived {run, location, digest}`.
7. Emit `hot.erase` for each journal and blob. Record `archive.erased`.
8. Record `archive.done`. The actor settles.

A restore runs the same steps with `direction` reversed: copy from cold,
verify, tell the catalogue `runs.restored`, erase the cold copy, tell the run
`run.thaw`.

On `actors.resume` the actor re-issues the first step without its record.

`hot.erase` is the one operation in this design that calls `Logs.erase`. A
workspace owner or the run's owner triggers it through the catalogue.
Nothing else calls it.

## Delete protocol

`runs.delete` records a tombstone `{run, by, at}` and emits `hot.erase` and
`cold.erase` for the subtree and its blobs. The catalogue keeps the
tombstone forever. A `runs.get` on a tombstone replies with the tombstone.
A `runs.open` with a tombstoned id is refused.

## Search actor

`search` writes `search.sqlite` with `Migrations.derived`. Tables: `runs`
mirrors the catalogue entries, `entries` holds one row per transcript entry
with an FTS5 index, `positions` holds read cursors.

| Command | Body |
|---|---|
| `search.index` | `run`, `seq`, `text`. From the run after each entry. Idempotent by `run` and `seq`. |
| `search.entry` | A catalogue entry. From the catalogue after each change. |
| `search.rebuild` | `by`. Deletes the file, replays every catalogue partition, and inspects every hot run. |
| `search.position` | `subject`, `run`, `seq`. |

Query `search.find {subject, role, query, after, limit}` replies with
matching runs and entries the subject may see.

An archived run keeps its rows. The catalogue entry carries the tier. A
rebuild indexes an archived run from its catalogue summary only.

## Resume rules

`actors.resume` arrives after replay and before any live message.

| Actor | On resume |
|---|---|
| `run` in `thinking` | Re-emit `model.complete` for the pending turn with the same idempotency key. |
| `run` in `acting` | For each pending foreground call whose tool is `idempotent`, spawn the tool actor again and re-arm `call.deadline` from the recorded start. For each that is not, record `run.pause` with reason `tool.repeat` and wait for a person. A deadline that already passed records `lost` at once. |
| `run` with a background call pending | The same rule per call, in any status. |
| `run` in `waiting` | Nothing. The question stands until a surface answers it. |
| `run` with a child in flight | Nothing. The child reports on its own. |
| `runs` | Re-tell `run.open` for each run without `runs.started`. |
| `archive` | Re-issue the first step without a record. |
| `budget` | Nothing. Reservations expire when the run records or releases them. |
| `tool`, `live`, `activity`, `search` | Empty. Nothing to resume. |

## Failure rules

- A model error with `retryable` true schedules one retry after 10 seconds.
  A second failure records `run.pause` with the error and status `waiting`.
- A tool failure of any kind is one `tool.finished` command. It never
  fails the turn or the run. The model sees the error on the next turn.
- A tool actor that dies without reporting is covered by `call.deadline`,
  which records `lost`.
- A run that passes `limits.turns` in one response records `run.pause`
  with reason `turn-limit`.
- A crash-looping run is quarantined by `actors`. The activity feed shows
  status `quarantined` from the registry, and a workspace owner may close
  it.
- A message to a run in tier `cold` is refused at the web boundary with
  `run.archived`. A stray message that reaches the run actor records one
  refusal. A restore replaces that journal and records `archive.replaced`.
- A surface that reconnects after a backlog gap receives `reload` and asks
  `run.view` with `since`. It never receives a duplicate entry, because
  entries carry the run's sequence number.

## Proposed changes to `actors` and `web.cable`

- `Logs.export(Address, Path)` checkpoints and copies one journal.
  `Logs.adopt(Address, Path)` replaces one journal from a file. `Journals`
  is the first implementation.
- `Logs.catalogue` gains a tier so that a listing can say which addresses
  are hot. The catalogue actor holds the tier of record.
- `Cable.broadcast` accepts an `eventstream.Event` with a name and a JSON
  body beside the Turbo fragment, so that one topic carries both.
- `Cable` gains a per-connection send, used for the `tool` event to the
  executor's connection.

Each is a seam that the package already names. None changes a definition.
