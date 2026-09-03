# Tutorial: one run from open to archive

This tutorial walks through the proposed `agents` design. The package is not
implemented. The names describe a future actor protocol.

Ada and Ben are two of the 25 people on one deployment. Ada starts a run
from her terminal to review a pull request. Ben watches from the web, adds
a message, and later archives the run.

## 1. The deployment

The team's deployment runs one actor system. On first start it tells
`workspace` `workspace.create {name: "Acme", creator: "s-ada"}`. Ada is the
owner. She tells `workspace` `member.add {subject: "s-ben", role: "editor",
by: "s-ada"}` for each teammate.

The workspace actor is durable and never evicted. Its log holds these
commands and every membership change after them. It stays a few kilobytes
for years.

## 2. A project

Ada tells `project/reviewer` `project.create {name: "Reviewer", by:
"s-ada"}` and then `project.version {model, instructions, files, tools,
limits, by}`. The tools are `read-file`, `shell`, and `delegate`. `shell`
runs `where: "surface"` and is not idempotent. The limits set 40 turns per
response and a 150,000-token context.

Version 1 is current. A run records the version it started with and never
reads a later one.

## 3. Open a run from the terminal

Ada's terminal client authenticates with her bearer token and opens one
event stream. It makes the ULID `01J...7Q`, subscribes to `run:01J...7Q`,
and tells `runs/2026-09` `runs.open {run, project: "reviewer", version: 1,
owner: "s-ada", title: "Review PR 418", executor: "surface", connection}`.

The catalogue records the run and tells `run/01J...7Q` `run.open`. The run
records it, tells the catalogue `runs.started`, tells `activity`
`activity.run`, and waits. Ada types her first message. The terminal tells
the run `run.say`.

The run is durable with `Durability.full`. Every entry it records reaches
the disk before the run acts on it.

## 4. The first turn

The run tells `budget/2026-09` `spend.reserve {run, turn: 1, estimate}`. The
budget accepts. The run records `turn.requested` and emits `model.complete`
with turn 1 as the idempotency key.

The model streams. The effect tells `live/01J...7Q` `live.delta` for each
chunk. The live actor broadcasts `delta` events on the run's topic. Ada's
terminal draws the answer as it grows.

The model asks for `read-file` on two paths. The effect emits
`model.answered {turn: 1, text, calls, usage}`. The run records it, tells
the budget `spend.record`, tells the live actor `live.entry`, and tells
`activity` that it is `acting`.

## 5. Tools run on Ada's machine

The run spawns `tool/01J...7Q/c1` and `tool/01J...7Q/c2` and tells each
`tool.start` with executor `surface`. Each tool actor tells the live actor
`live.request`. The cable sends a `tool` event to Ada's connection only.

Ada's terminal reads both files and posts `tool.output` and `tool.exit` for
each call. Each tool actor forwards the output to the live actor and tells
the run `tool.finished`. A file above 64 KiB goes to a blob, and the entry
carries the hash.

When no call is pending, the run starts turn 2.

## 6. Ben watches from the web

Ben's browser has the `workspace` topic open on the team page. An
`activity` event says Ada's run is acting. Ben opens it.

`Topics.of` asks the workspace for Ben's role and the catalogue for the
run's visibility. The run is `workspace`-visible, so the cable answers the
topic `run:01J...7Q`. The page asks `live.now`, draws the partial answer,
and lets Turbo apply `message` events from then on. The cable tells the
live actor `live.join`. Ada's terminal shows that Ben is here.

The model calls `shell` on turn 3. The run records `permission.asked`. Both
surfaces show the question. Ben tells the run `permission.answered {call,
allow: true, by: "s-ben"}`. Ada's terminal runs the command. Ben's page
shows the output line by line.

## 7. Ben speaks

Ben tells the run `run.say {subject: "s-ben", text: "Also check the
migration."}`. The run records it and includes it in the next context. Both
surfaces show the entry with Ben's name.

## 8. A subagent

On turn 5 the model calls `delegate {project: "tester", task}`. The run
records `child.spawned` and tells the catalogue `runs.open` with `parent`
set. The child is `run/01J...7Q/01J...9A`. It has its own transcript, its
own budget lines, and its own place in the activity feed.

The child finishes and tells the parent `child.finished {summary, seq}`.
The parent records the summary as the result of the `delegate` call. The
parent's log holds the summary, not the child's transcript.

## 9. A restart in the middle

The process restarts during turn 7. The run replays its log. No effect runs
during replay. The state says turn 7 is `thinking` with no answer.

`actors.resume` arrives. The run re-emits `model.complete` for turn 7 with
the same idempotency key. The live actor starts empty. Both surfaces
reconnect with `Last-Event-ID`, receive `reload`, and ask `run.view` with
`since`.

If the restart had happened while `shell` was running, the run would record
`run.pause` with reason `tool.repeat`, because `shell` is not idempotent.
Either surface could confirm, and the run would spawn the tool again.

## 10. The run goes idle

On turn 12 the model answers without calls. The run tells the catalogue
`runs.status idle` with a summary and tells `activity`. Fifteen minutes
later the system evicts it. Its journal stays where it is.

Ada closes her terminal. The cable tells the run `run.detach`. The run has
no pending surface call, so it stays `idle`. Ben can still send it a
message from the web. A `shell` call in that response would wait until Ada
attaches a terminal again.

## 11. Archive

A month later Ben tells `runs/2026-09` `runs.archive {run, by: "s-ben"}`.
Ben is an editor, not the run's owner, so the catalogue refuses. Ada tells
it instead.

The catalogue closes the run and spawns `archive/01J...7Q`. The archive
actor freezes the run, evicts it, copies both journals and every blob to
the archive location, verifies the digests, tells the catalogue
`runs.archived`, erases the hot copies, and records `archive.done`.

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

Read [reference.md](reference.md) for every command, query, event, and
limit. Read [explanation.md](explanation.md) for why each actor is durable
or ephemeral.
