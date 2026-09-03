# How-to

This page describes tasks for the proposed `agents` package. It does not
document current Java APIs. Exact rules are in [reference.md](reference.md).
Design reasons are in [explanation.md](explanation.md).

## Open a run from the web

1. Resolve the session subject with `web.sessions`.
2. Ask `workspace` `workspace.role {subject}`. Refuse a role below
   `editor`.
3. Make a ULID for the run.
4. Tell `runs/{yyyy-mm}` `runs.open {run, project, version, owner, title,
   executor: "deployment"}`.
5. Redirect to the run page. The page shows status `opening` until the run
   acknowledges with `runs.started`.

Do not tell the run actor `run.open` yourself. The catalogue does it, and it
repeats it on resume until the run acknowledges.

## Open a run from a terminal

1. Authenticate with the bearer token that `identity` issued.
2. Open the event stream with the topic `run:{run}` before you open the
   run, so that the first events are not missed.
3. Tell the catalogue `runs.open` with `executor: "surface"` and your
   connection id.
4. Handle every `tool` event: run the tool, post `tool.output` chunks, post
   `tool.exit`.

A teammate on the web sees your shell output as it happens. Their page
cannot execute tools for your run.

## Send a message to a shared run

1. Ask the workspace for the role. Refuse a role below `editor`.
2. Ask the catalogue `runs.get {run}`. Refuse tier `cold` and status
   `closed`.
3. Tell `run/{run}` `run.say {subject, text}`.

Several people may send to one run. The run handles their messages in
mailbox order and records each one with its subject. A message sent while a
response is in flight is queued and starts the next response.

## Watch a run live

1. Open one event stream with the topic `run:{run}`. A browser page
   renders `Cable.source` once. A terminal opens the stream over HTTP.
2. Ask `live/{run}` `live.now` to draw the partial answer and the viewers.
3. Apply `entry`, `delta`, `output`, `presence`, and `typing` events as
   they arrive. A browser lets Turbo apply `message` and ignores the rest.
4. On a `reload` event, ask `run.view` with `since` set to your last
   sequence, then continue.

The cable tells the live actor `live.join` when the stream opens and
`live.leave` when it closes. A surface does nothing for presence.

## Watch what the team is doing

1. Open the event stream with the topic `workspace`.
2. Ask `activity` `activity.now {subject, role}` to draw the present state.
3. Apply `activity` events. Each one carries a run's status change or a
   member's presence change.
4. Open any listed run the subject may read, through its own topic.

To follow one person, subscribe to `member:{subject}` instead. Tell
`member/{me}` `member.follow {subject}` to make that a default.

## Answer a permission question from any surface

1. Watch for an entry of kind `permission` with no answer.
2. Show the tool and its arguments.
3. Tell the run `permission.answered {call, allow, by, remember}`.

The first answer wins. Every surface sees the answer as an entry. A
`remember` rule applies to this run only.

## Browse a run's history

1. Call `ActorSystem.inspectAt(run, seq)` for the state after `seq`
   entries.
2. Render the transcript up to that point.
3. Step `seq` to scrub.

`inspectAt` builds a throwaway instance and runs no effect. It costs one
replay per call. Cache the JSON for a page that scrubs often.

## Share a run

1. Ask the catalogue `runs.get {run}`. Only the run's owner or a workspace
   owner may change visibility.
2. Tell the run `run.share {visibility, by}`. Use `workspace`, `private`,
   or `link`.
3. For `link`, generate a random token and pass it in the command. The run
   records it and tells the catalogue.

A `link` viewer presents the token in the URL. The catalogue answers
`runs.get` and the run answers `run.view` when the token matches.

## Run a subagent

1. Give the project a tool named `delegate` with arguments `project` and
   `task`.
2. When the model calls it, the run records `child.spawned` and tells the
   catalogue `runs.open` with `parent` set.
3. The child runs on its own, with the parent's executor and visibility. It
   tells the parent `child.finished` with a summary.
4. The parent records the summary as the tool result of the `delegate` call
   and continues.

The parent's transcript holds the summary only. Browse the child from the
parent's tree view. The activity feed shows the child as its own run.

## Add a tool

1. Add `{name, description, schema, idempotent, timeout, where}` to the
   project version's `tools`.
2. For `where` `deployment` or `any`, register one effect handler for the
   tool's process or connection on the actor system.
3. For `where` `surface` or `any`, implement the tool in the surface
   client.
4. Emit `tool.output` chunks and one `tool.exit` from either side.
5. Set `idempotent` to true only when running the tool twice with the same
   arguments has one outcome.

A tool that is not idempotent pauses its run after a crash and asks, on
every surface, before it repeats.

## Set a budget

1. Put a monthly default in `workspace.settings`.
2. Tell `budget/{yyyy-mm}` `budget.set {limit, by}` to change one month.

A run that cannot reserve pauses with `budget.exhausted` and status
`waiting`. Raise the limit and tell the run `run.resume`.

## Archive a run

1. Ask the catalogue `runs.get {run}`. The status must be `idle` or
   `closed`.
2. Tell the catalogue `runs.archive {run, by}`.
3. Watch `archive/{run}` reach `archive.done`.

The run stays listed. Its summary page shows the title, the project, the
owner, the dates, and the summary. The transcript shows a restore
affordance instead of entries.

## Restore a run

1. Tell the catalogue `runs.restore {run, by}`.
2. Watch the archive actor reach `archive.done`.

The run page shows the transcript again. The run is `closed`. Send it a
message to continue it, which reopens it as `idle` first.

## Delete a run

1. Tell the catalogue `runs.delete {run, by}`.

The catalogue writes a tombstone and erases the journals and blobs of the
run and its children, hot or cold. Nothing restores a deleted run.

## Rebuild the search index

1. Tell `search` `search.rebuild {by}`.

The actor deletes the SQLite file, replays every catalogue partition, and
inspects every hot run. Archived runs are indexed from their catalogue
summary.

## Keep a growing deployment fast

- A catalogue partition holds one month. A budget partition holds one
  month. Neither needs more.
- A run is bounded by one conversation. Set `limits.context` on the
  project so that compaction keeps the context inside the model window, and
  set `limits.turns` per response so that a response cannot loop without a
  person's decision.
- Archive idle runs that nobody has opened for a period the team chooses.
  This is a person's operation. Nothing in the system runs it on a
  schedule unless the team installs a routine that does.
- Watch `summoned` traces. A run whose replay grows past a second is a run
  to archive.

## Watch the actor system itself

1. Subscribe to `ActorSystem.traces()`.
2. Show `summoned` events with their `millis` on an operations page.
3. Show `quarantined` events in the activity feed so that a workspace owner
   can close the run.
