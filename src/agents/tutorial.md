# Tutorial: one run from open to archive

This tutorial walks through the proposed `agents` design. The package is not
implemented. The names describe a future actor protocol.

Two people, Ada and Ben, share the workspace `acme`. Ada runs an agent that
reviews a pull request. Ben watches, adds a message, and later archives the
run.

## 1. Create the workspace

Ada's session subject is `s-ada`. The application tells `workspace/acme`
`workspace.create {name: "Acme", creator: "s-ada"}`. Ada is the owner.

The workspace actor is durable. Its log holds this command and every
membership change after it. It stays a few kilobytes for years.

## 2. Add a member

Ada tells `workspace/acme` `member.add {subject: "s-ben", role: "editor",
by: "s-ada"}`. Ben may now open runs and speak in shared runs.

`member/acme/s-ben` does not exist yet. It comes into being when Ben records
a preference, because every address always exists and an empty log is an
empty actor.

## 3. Define an agent

Ada tells `agent/acme/reviewer` `agent.define {name: "Reviewer", by:
"s-ada"}` and then `agent.version {model, system, tools, limits, by}`. The
tools are `read-file`, `shell`, and `delegate`. `shell` is not idempotent.
The limits set 40 turns and a 150,000-token context.

Version 1 is current. A run records the version it started with and never
reads a later one.

## 4. Open a run

Ada opens a run. The application makes the ULID `01J...7Q`, tells
`runs/acme/2026` `runs.open {run, agent: "reviewer", version: 1, owner:
"s-ada", title: "Review PR 418"}`, and redirects to the run page.

The catalogue records the run and tells `run/acme/01J...7Q` `run.open`.
The run records it, tells the catalogue `runs.started`, and starts the first
turn. The page shows `opening`, then `thinking`.

The run is durable with `Durability.full`. Every answer it records reaches
the disk before the run acts on it.

## 5. The first turn

The run tells `budget/acme/2026-09` `spend.reserve {run, turn: 1,
estimate}`. The budget accepts. The run records `turn.requested` and emits
`model.complete` with turn 1 as the idempotency key.

The model streams. The effect tells `stream/acme/01J...7Q` `stream.delta`
for each chunk. The stream actor broadcasts on the cable topic
`run:acme/01J...7Q`. Ada's page shows the answer as it grows.

The model asks for `read-file` on two paths. The effect emits
`model.answered {turn: 1, text, calls, usage}`. The run records it, tells
the budget `spend.record`, tells the stream `stream.reset`, and broadcasts
the finished entry.

## 6. Tools run

The run spawns `tool/acme/01J...7Q/c1` and `tool/acme/01J...7Q/c2` and
tells each `tool.start`. Both are ephemeral. Each schedules its timeout,
runs its effect, and tells the run `tool.finished`. A file above 64 KiB goes
to a blob, and the command carries the hash.

When no call is pending, the run starts turn 2.

## 7. Ben watches and speaks

Ben opens the run page. `Topics.of` asks the workspace for Ben's role and
the catalogue for the run's visibility. The run is `workspace`-visible, so
the cable answers the topic. The page tells `presence/acme/01J...7Q`
`presence.join`. Ada's page shows Ben's avatar.

Ben tells the run `run.say {subject: "s-ben", text: "Also check the
migration."}`. The run records it and includes it in the next context. Both
pages show the entry with Ben's name.

## 8. A subagent

On turn 5 the model calls `delegate {agent: "tester", task}`. The run
records `child.spawned` and tells the catalogue `runs.open` with `parent`
set. The child is `run/acme/01J...7Q/01J...9A`. It has its own transcript
and its own budget lines.

The child finishes and tells the parent `child.finished {summary, seq}`.
The parent records the summary as the result of the `delegate` call. The
parent's log holds the summary, not the child's transcript.

## 9. A restart in the middle

The process restarts during turn 7. The run replays its log. No effect runs
during replay. The state says turn 7 is `thinking` with no answer.

`actors.resume` arrives. The run re-emits `model.complete` for turn 7 with
the same idempotency key. The stream actor starts empty. Ada's page
reconnects, finds a gap, and reloads from `inspect`.

If the restart had happened while `shell` was running, the run would record
`run.pause` with reason `tool.repeat`, because `shell` is not idempotent.
Ada would confirm, and the run would spawn the tool again.

## 10. The run finishes

On turn 12 the model answers without calls. The run records `run.finish
{summary}` and tells the catalogue `runs.status`. The run settles. The
system evicts it. Its journal stays where it is.

The catalogue now holds the title, the owner, the agent, the dates, the
turn count, the cost, and the summary. A listing shows the run without
summoning it.

## 11. Archive

A month later Ben tells `runs/acme/2026` `runs.archive {run, by: "s-ben"}`.
Ben is an editor, not the run's owner, so the catalogue refuses. Ada tells
it instead.

The catalogue spawns `archive/acme/01J...7Q`. The archive actor freezes the
run, evicts it, copies both journals and every blob to the workspace's
archive location, verifies the digests, tells the catalogue `runs.archived`,
erases the hot copies, and records `archive.done`.

The run stays listed. Its page shows the summary and a restore button. Its
journal is in cold storage, and its cold copy is the only copy.

## 12. Restore, or delete

Ada tells the catalogue `runs.restore {run, by: "s-ada"}`. The archive actor
copies the files back, verifies them, tells the catalogue `runs.restored`,
and thaws the run. The transcript is browsable again.

Ada tells the catalogue `runs.delete {run, by: "s-ada"}`. The catalogue
writes a tombstone and erases every journal and blob of the run and its
child. The listing shows that the run existed and who deleted it. Nothing
brings it back.

## Continue

Read [reference.md](reference.md) for every command, query, and limit. Read
[explanation.md](explanation.md) for why each actor is durable or ephemeral.
