# How-to

This page describes tasks for the proposed `agents` package. It does not
document current Java APIs. Exact rules are in [reference.md](reference.md).
Design reasons are in [explanation.md](explanation.md).

## Open a run

1. Resolve the session subject with `web.sessions`.
2. Ask `workspace/{ws}` `workspace.role {subject}`. Refuse a role below
   `editor`.
3. Make a ULID for the run.
4. Tell `runs/{ws}/{yyyy}` `runs.open {run, agent, version, owner, title}`.
5. Redirect to the run page. The page shows status `opening` until the run
   acknowledges with `runs.started`.

Do not tell the run actor `run.open` yourself. The catalogue does it, and it
repeats it on resume until the run acknowledges.

## Send a message to a shared run

1. Ask the workspace for the role. Refuse a role below `editor`.
2. Ask the catalogue `runs.get {run}`. Refuse tier `cold` and status
   `finished`, `failed`, or `cancelled`.
3. Tell `run/{ws}/{run}` `run.say {subject, text}`.

Several people may send to one run. The run handles their messages in
mailbox order and records each one with its subject.

## Watch a run live

1. Render `Cable.source` once on the run page.
2. Implement `Topics.of(request)` to answer `run:{ws}/{run}` only when the
   subject may read the run.
3. On stream open, tell `presence/{ws}/{run}` `presence.join`.
4. On stream close, tell `presence.leave`.
5. On a reconnect gap, reload the page.

The page renders the transcript from `ActorSystem.inspect(run)`. It asks
`stream.current` so that a viewer who opens mid-turn sees the partial
answer.

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

1. Give the agent a tool named `delegate` with arguments `agent` and `task`.
2. When the model calls it, the run records `child.spawned` and tells the
   catalogue `runs.open` with `parent` set.
3. The child runs on its own. It tells the parent `child.finished` with a
   summary.
4. The parent records the summary as the tool result of the `delegate` call
   and continues.

The parent's transcript holds the summary only. Browse the child from the
parent's tree view.

## Add a tool

1. Add `{name, description, schema, idempotent, timeout}` to the agent
   version's `tools`.
2. Register one effect handler for the tool's process or connection on the
   actor system.
3. Emit `tool.output` chunks and one `tool.exit` from the handler.
4. Set `idempotent` to true only when running the tool twice with the same
   arguments has one outcome.

A tool that is not idempotent pauses its run after a crash and waits for a
person to confirm the repeat.

## Set a budget

1. Tell `budget/{ws}/{yyyy-mm}` `budget.set {limit, by}`.
2. Repeat for each month, or put a default in `workspace.settings` that a
   new month reads on its first `spend.reserve`.

A run that cannot reserve fails with `budget.exhausted`. Raise the limit and
tell the run `run.resume`.

## Archive a run

1. Ask the catalogue `runs.get {run}`. The status must be terminal.
2. Tell the catalogue `runs.archive {run, by}`.
3. Watch `archive/{ws}/{run}` reach `archive.done`.

The run stays listed. Its summary page shows the title, the agent, the
owner, the dates, and the final summary. The transcript shows a restore
affordance instead of entries.

## Restore a run

1. Tell the catalogue `runs.restore {run, by}`.
2. Watch the archive actor reach `archive.done`.

The run page shows the transcript again. Restoring does not resume the run.

## Delete a run

1. Tell the catalogue `runs.delete {run, by}`.

The catalogue writes a tombstone and erases the journals and blobs of the
run and its children, hot or cold. Nothing restores a deleted run.

## Rebuild the search index

1. Tell `search/{ws}` `search.rebuild {by}`.

The actor deletes the SQLite file, replays every catalogue partition, and
inspects every hot run. Archived runs are indexed from their catalogue
summary.

## Keep a growing workspace fast

- A catalogue partition holds one year. Nothing else is needed for the
  catalogue.
- A budget partition holds one month.
- A run is bounded by one conversation. Set `limits.context` on the agent
  so that compaction keeps the context inside the model window, and set
  `limits.turns` so that a run cannot grow without a person's decision.
- Archive finished runs that nobody has opened for a period the workspace
  chooses. This is a person's operation. Nothing in the system runs it on a
  schedule unless the workspace installs a routine that does.

## Watch the actor system itself

1. Subscribe to `ActorSystem.traces()`.
2. Show `summoned` events with their `millis` on an operations page. A run
   whose replay grows past a second is a run to archive.
3. Show `quarantined` events beside the run so that a workspace owner can
   cancel the run.
