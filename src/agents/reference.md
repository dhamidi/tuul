# Reference: proposed agents contract

This page is the proposed implementation contract for `agents`. No public
implementation exists. The names and message shapes are design vocabulary,
not current Java APIs.

## Scope

The package defines workspaces, members, agent definitions, runs, subagent
runs, turns, tool invocations, live streaming, presence, budgets, the run
catalogue, the search index, and the archive workflow.

The package does not define a model provider, a tool sandbox, an
authentication provider, or a browser UI. It uses `identity` for the
authenticated subject and `web.sessions` for the session. It uses `web.cable`
for live updates and `sqlite3` for the search index.

## Terms

| Term | Meaning |
|---|---|
| workspace | One shared space with members. Every other id starts with its id. |
| subject | The opaque local identifier that `identity` resolves. |
| member | One subject with one role in one workspace. |
| role | `owner`, `editor`, or `viewer`. |
| agent | One named, versioned recipe: model, system prompt, tools, limits. |
| run | One execution of one agent version. The unit that is browsed and shared. |
| child run | A run that another run spawned. Its id is nested under the parent's id. |
| turn | One model call and the tool calls it requested. |
| invocation | One tool call in flight. |
| blob | Content stored by hash outside every journal. |
| hot | A journal under the actor system root. |
| cold | A journal and its blobs at an archive location. |
| tombstone | The catalogue record of a deleted run. |

## Actor topology

| Address pattern | Spawn | State authority |
|---|---|---|
| `workspace/{ws}` | durable | Name, members, roles, default agent limits, archive location. |
| `member/{ws}/{subject}` | durable | One person's preferences in one workspace. |
| `agent/{ws}/{agent}` | durable | Every version of one agent definition. Which version is current. |
| `budget/{ws}/{yyyy-mm}` | durable, `Durability.full` | Reservations and spend for one month. |
| `runs/{ws}/{yyyy}` | durable | Which runs opened in one year, their title, owner, agent, visibility, status, tier, and tombstones. |
| `run/{ws}/{run}` | durable, `Durability.full` | The transcript, status, visibility, owner, children, and pending calls of one run. |
| `run/{ws}/{run}/{child}` | as `run` | The same for one child run. |
| `archive/{ws}/{run}` | durable | The accepted steps of one move between hot and cold. |
| `search/{ws}` | ephemeral | None. It writes one derived SQLite file. |
| `stream/{ws}/{run}` | ephemeral | Partial output of the turn in flight. |
| `tool/{ws}/{run}/{call}` | ephemeral | One process or connection and its buffered output. |
| `presence/{ws}/{run}` | ephemeral | The subjects with an open stream to this run. |

A run id is a ULID. The catalogue partition is the year of the ULID's
timestamp. A child run id is the parent's id, a slash, and a new ULID.

## Spawn options

| Actor | `keepsLog` | `idle` | `durability` | `redelivers` | `mailbox` |
|---|---|---|---|---|---|
| `workspace` | true | 1 h | normal | false | 1024 |
| `member` | true | 10 min | normal | false | 64 |
| `agent` | true | 30 min | normal | false | 256 |
| `budget` | true | 10 min | full | false | 4096 |
| `runs` | true | 1 h | normal | false | 4096 |
| `run` | true | 15 min | full | false | 1024 |
| `archive` | true | 5 min | normal | false | 64 |
| `search` | false | 1 h | none | false | 4096 |
| `stream` | false | 1 min | none | false | 8192 |
| `tool` | false | 0, never | none | false | 256 |
| `presence` | false | 1 min | none | false | 1024 |

`redelivers` is false everywhere. A run re-issues its own pending calls on
`actors.resume`. See *Resume rules*.

`Definition.settled` returns true for a `run` in status `finished`,
`failed`, or `cancelled`, for an `archive` whose last step completed, and for
a `tool` after it reported. Settled is a passivation hint. It refuses
nothing.

## Blob store

The blob store is an effect handler, not an actor. It keeps one file per
content hash under `blobs/{ws}/{run}/{sha256}`. A command that refers to a
blob carries `{"blob": "sha256:...", "size": n, "type": "text/plain"}`.

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
| `workspace.rename` | `name` | `owner` only. |
| `member.add` | `subject`, `role`, `by` | `owner` only. Idempotent by `subject`. |
| `member.role` | `subject`, `role`, `by` | `owner` only. The last owner cannot be demoted. |
| `member.remove` | `subject`, `by` | `owner` only. Runs the member owns stay. |
| `workspace.settings` | `defaults`, `by` | `owner` only. Default model, turn limit, budget. |
| `workspace.archive-location` | `location`, `by` | `owner` only. Where cold copies go. |
| `workspace.delete` | `by` | `owner` only. Refused while any run is hot. |

Queries:

| Query | Reply |
|---|---|
| `workspace.role` `{subject}` | `{role}` or `{role: null}`. |
| `workspace.members` | The member list with roles. |
| `workspace.settings` | The defaults. |

The workspace actor holds no run list. The catalogue does.

## Member actor

| Command | Body |
|---|---|
| `member.prefer` | `key`, `value`. Idempotent. |

Read positions per run are not commands. The search database holds them in a
`positions` table keyed by subject and run. A lost position costs one scroll.

## Agent actor

| Command | Body | Rule |
|---|---|---|
| `agent.define` | `name`, `by` | First command only. |
| `agent.version` | `model`, `system`, `tools`, `limits`, `by` | Appends version `n+1` and makes it current. |
| `agent.current` | `version`, `by` | Makes an earlier version current. |
| `agent.retire` | `by` | Refuses new runs. Existing runs continue. |

Query `agent.version {version}` replies with that version. A run records
the version number it started with and never reads a later one.

A `tools` entry is `{name, description, schema, idempotent, timeout}`.
`idempotent` decides what a run does when it must re-issue the call. See
*Resume rules*.

## Budget actor

| Command | Body | Rule |
|---|---|---|
| `budget.set` | `limit`, `by` | `owner` only. |
| `spend.reserve` | `run`, `turn`, `estimate` | Refused with `budget.exhausted` when the estimate exceeds the remainder. Idempotent by `run` and `turn`. |
| `spend.record` | `run`, `turn`, `input`, `output`, `cost` | Replaces the reservation. Idempotent by `run` and `turn`. |
| `spend.release` | `run`, `turn` | Cancels a reservation the run will not use. |

Query `budget.remaining` replies with the limit, the spend, the open
reservations, and the remainder.

The run addresses the month of `message.at()`. A turn that starts at the end
of a month and ends in the next records its spend in the month it reserved.

## Catalogue actor

The `runs/{ws}/{yyyy}` actor is the authority for which runs exist.

| Command | Body | Rule |
|---|---|---|
| `runs.open` | `run`, `agent`, `version`, `owner`, `title`, `parent` | Records the run and tells the run actor `run.open`. Idempotent by `run`. |
| `runs.started` | `run` | The run acknowledges `run.open`. |
| `runs.retitled` | `run`, `title`, `seq` | From the run. Deduplicated by `seq`. |
| `runs.visibility` | `run`, `visibility`, `seq` | From the run. Deduplicated by `seq`. |
| `runs.status` | `run`, `status`, `summary`, `turns`, `cost`, `seq` | From the run. Deduplicated by `seq`. |
| `runs.archive` | `run`, `by` | `owner` or the run's owner. Refused unless status is terminal. Spawns `archive/{ws}/{run}`. |
| `runs.archived` | `run`, `location`, `digest` | From the archive actor. |
| `runs.restore` | `run`, `by` | Refused unless tier is `cold`. Spawns the archive actor in reverse. |
| `runs.restored` | `run` | From the archive actor. |
| `runs.delete` | `run`, `by` | `owner` or the run's owner. Erases hot and cold copies and writes a tombstone. |

Queries:

| Query | Reply |
|---|---|
| `runs.list` `{subject, role, filter, after, limit}` | Runs the subject may see, newest first, with a cursor. |
| `runs.get` `{run}` | One entry: title, owner, agent, version, visibility, status, summary, tier, location, parent. |
| `runs.tree` `{run}` | The entry and its descendants. |

`runs.list` applies visibility from the catalogue's copy. A `workspace`
run is visible to every member. A `private` run is visible to its owner and
to workspace owners. A `link` run is visible to anyone who presents its
token. The catalogue never summons a run to answer a list.

On `actors.resume` the catalogue re-tells `run.open` for every run that has
no `runs.started`.

## Run actor

Status moves in this order: `opening`, `idle`, `thinking`, `acting`,
`waiting`, then one of `finished`, `failed`, `cancelled`. `thinking` is a
model call in flight. `acting` is one or more tool calls in flight. `waiting`
is a run that asked a person and stopped.

Commands:

| Command | Body | Rule |
|---|---|---|
| `run.open` | `agent`, `version`, `owner`, `title`, `parent`, `task` | First command only. Any earlier command is refused and changes nothing. |
| `run.say` | `subject`, `text`, `attachments` | Any member with `editor` or above, or the owner. Starts a turn when idle. |
| `run.title` | `title`, `by` | Tells the catalogue. |
| `run.share` | `visibility`, `token`, `by` | Owner or workspace owner. Tells the catalogue. |
| `turn.requested` | `turn`, `reason` | The run records that it asked the model. Reserves budget first. |
| `model.answered` | `turn`, `text`, `calls`, `usage`, `stop` | From the model effect. Duplicate `turn` is refused. |
| `model.failed` | `turn`, `error`, `retryable` | From the model effect. A retryable error schedules one retry. |
| `tool.finished` | `turn`, `call`, `result`, `blob`, `error` | From a tool actor. Duplicate `call` is refused. |
| `tool.cancelled` | `call`, `by` | Tells the tool actor `tool.cancel`. |
| `child.spawned` | `child`, `task` | Opens the child through the catalogue. |
| `child.finished` | `child`, `status`, `summary`, `seq` | From the child. Deduplicated by `seq`. |
| `context.compacted` | `turn`, `summary`, `dropped` | From the compaction effect. Replaces earlier turns in the context with the summary. |
| `run.pause` | `by` | Refuses new turns. Tool calls in flight finish. |
| `run.resume` | `by` | Starts the next turn if one is due. |
| `run.cancel` | `by` | Cancels tool calls in flight and every child. Terminal. |
| `run.finish` | `summary` | From the update when the model stops without calls. Terminal. |
| `run.fail` | `error` | Terminal. |
| `run.freeze` | `by` | From the archive actor. Refuses every command except `run.thaw` and queries. |
| `run.thaw` | `by` | From the archive actor. |

Queries:

| Query | Reply |
|---|---|
| `run.view` `{subject, role, token}` | The transcript view when the subject may read it. `run.forbidden` otherwise. |
| `run.status` | Status, turn count, cost, pending calls. |

`Definition.inspect` returns the transcript as JSON with one entry per
recorded command, the status, the pending calls, and the children. The
transcript entry for a blob carries the hash and the size and not the
content. The run page streams a blob through `blob.get`.

The context the run sends to the model is the transcript after the last
`context.compacted`. Its size is bounded by the model's context window. The
run requests compaction when the context passes the agent's `limits.context`.

## Turn protocol

1. The run receives `run.say` while `idle`, or `tool.finished` completes the
   last pending call while `acting`.
2. The run tells `budget/{ws}/{yyyy-mm}` `spend.reserve`. A refusal records
   `run.fail` with `budget.exhausted`.
3. The run records `turn.requested` and emits the effect `model.complete`
   with the context, the turn number as the idempotency key, and the stream
   address.
4. The model effect tells `stream/{ws}/{run}` `stream.delta` for each chunk
   and finally emits `model.answered` to the run.
5. The run records the answer, tells the budget `spend.record`, and
   broadcasts the entry on the cable.
6. For each requested call the run spawns `tool/{ws}/{run}/{call}` and tells
   it `tool.start`. Status becomes `acting`.
7. Each tool actor tells the run `tool.finished`. When no call is pending,
   the run starts the next turn.
8. An answer with no calls and a stop reason of `end` records `run.finish`.

## Tool actor

| Command | Body |
|---|---|
| `tool.start` | `name`, `arguments`, `timeout`, `run`, `turn`, `call` |
| `tool.cancel` | `by` |
| `tool.output` | `chunk`. From the process effect. Buffered to 64 KiB, then streamed to a blob. |
| `tool.exit` | `code`, `blob`. From the process effect. |
| `tool.timeout` | Scheduled at `tool.start`. |

On `tool.exit`, `tool.timeout`, or `tool.cancel` the actor tells the run
`tool.finished` and evicts itself. It forwards `tool.output` to the stream
actor for live display.

## Stream actor

| Command | Body |
|---|---|
| `stream.delta` | `turn`, `text` or `call`. Appends and broadcasts on the cable. |
| `stream.reset` | `turn`. From the run when it records the answer. |

Query `stream.current` replies with the partial output so that a page that
opens mid-turn shows it at once.

## Presence actor

| Command | Body |
|---|---|
| `presence.join` | `subject`, `connection` |
| `presence.leave` | `subject`, `connection` |

Query `presence.viewers` replies with the distinct subjects. The cable
handler tells `join` when a stream opens and `leave` when it closes. On a
restart every connection reopens and joins again.

## Live updates

The cable topic of a run is `run:{ws}/{run}`. `Topics.of(request)` reads
the session subject, asks the workspace for the role, asks the catalogue for
the run's visibility, and answers the topic only when the subject may read
the run. `Topics.query` is never used.

The run broadcasts one Turbo Stream per recorded entry, keyed by the entry's
sequence number. The stream actor broadcasts partial output as a replace of
the turn's pending element. A client that reconnects after a gap reloads the
run page, which renders from `inspect`.

## Child runs

A child is opened through the catalogue with `parent` set. It receives its
task in `run.open`. The child tells the parent `child.finished` with its
summary and its own sequence number. The parent records it and continues.

A parent's `run.cancel` tells every child `run.cancel`. A parent's `archive`
moves the subtree. A parent's `delete` deletes the subtree. A child cannot
be archived or deleted on its own.

## Archive protocol

`archive/{ws}/{run}` records each step as a command with the same request id.

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

`search/{ws}` writes `search/{ws}.sqlite` with `Migrations.derived`. Tables:
`runs` mirrors the catalogue entries, `entries` holds one row per transcript
entry with an FTS5 index, `positions` holds read cursors.

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
| `run` in `acting` | For each pending call whose tool is `idempotent`, spawn the tool actor again. For each that is not, record `run.pause` with reason `tool.repeat` and wait for a person. |
| `run` with a child in flight | Nothing. The child reports on its own. |
| `runs` | Re-tell `run.open` for each run without `runs.started`. |
| `archive` | Re-issue the first step without a record. |
| `budget` | Nothing. Reservations expire when the run records or releases them. |
| `tool`, `stream`, `presence`, `search` | Empty. Nothing to resume. |

## Failure rules

- A model error with `retryable` true schedules one retry after 10 seconds.
  A second failure records `run.fail`.
- A tool timeout records `tool.finished` with `error` `timeout`. The model
  sees the error on the next turn.
- A run that passes `limits.turns` records `run.finish` with reason
  `turn-limit`.
- A crash-looping run is quarantined by `actors`. The catalogue shows
  status `quarantined` from the registry, and a workspace owner may cancel
  it.
- A message to a run in tier `cold` is refused at the web boundary with
  `run.archived`. A stray message that reaches the run actor records one
  refusal. A restore replaces that journal and records `archive.replaced`.

## Proposed changes to `actors`

- `Logs.export(Address, Path)` checkpoints and copies one journal.
  `Logs.adopt(Address, Path)` replaces one journal from a file. `Journals`
  is the first implementation.
- `Logs.catalogue` gains a tier so that a listing can say which addresses
  are hot. The catalogue actor holds the tier of record.

Both are seams that `actors` already names. Neither changes a definition.
