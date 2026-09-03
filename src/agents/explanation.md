# Explanation

This document explains the proposed `agents` actor hierarchy. It is not an
implementation. Read [reference.md](reference.md) for the exact rules and
[README.md](README.md) for the lifetime and size table.

## One workspace is one deployment

A team of 20 to 30 people gets one process, one log root, one blob
directory, one search database, and one identity configuration. Nothing in
an address names the workspace, because there is only one. The `workspace`
actor is a singleton with an empty id.

This removes a whole class of questions. There is no tenant check on any
address, no shard function, no cross-tenant listing, and no shared file that
two teams write. It also sets the scale every number in this design is
measured against: tens of concurrent runs, tens of open connections, and a
hot store that grows by tens of gigabytes per year.

## The transcript is the journal

A run actor records every message it handles. A person's message arrives as
`run.say`. The model's answer arrives as the command `model.answered`. A
tool's result arrives as `tool.finished`. The journal of the run actor
therefore holds the whole conversation, in order, with the timestamp of each
entry.

Nothing copies the transcript into a second store. A surface that opens a
run reads its state with `ActorSystem.inspect`. A scrubber reads an earlier
state with `ActorSystem.inspectAt`. A reader can open the journal while the
actor appends, because a journal uses write-ahead logging.

This follows the law of `actors`: effects never run during replay. Replaying
a run rebuilds its state from the recorded answers. It never calls the model
again and it never runs a tool again. A replay of a 500-message run costs a
few hundred milliseconds of SQLite reads and JSON parsing. It costs no
tokens.

## A run is a conversation, not a job

The product works like Claude. A person sends a message, the model answers,
tools run, the model answers again, and then the run waits for the next
message. That wait can last minutes or months. A run therefore has no end
that the model decides. It is `idle` between responses, and it is `closed`
only when a person closes or archives it.

An idle run passivates after fifteen minutes. Its journal stays. The next
message summons it and replays it. Memory holds only the runs somebody used
in the last quarter hour. That is the virtual actor pattern doing what it
was built for.

## A subagent is a run

An agent that delegates work spawns a child run at `run/{run}/{child}`. The
child is the same actor type with a nested id. It has its own transcript
and its own budget line. The parent records `child.spawned` and later
`child.finished` with the child's summary. It never holds the child's
transcript.

This keeps every log bounded by one conversation. It also makes the subtree
of one run one prefix listing. `Logs.catalogue("run", "{run}/")` names every
descendant without an index, and an archive of the parent moves the whole
subtree.

## One event stream serves every surface

A surface is a browser tab, a terminal, an editor, or a phone. All of them
need the same things: the transcript as it stands, every new entry as it is
recorded, the tokens of the answer in flight, and who else is here. They
differ only in how they draw it.

So there is one protocol. A surface reads one `text/event-stream` connection
from `web.cable`. Every event has a topic, a sequence number, a name, and a
JSON body. The browser gets one more event name on the same topics, whose
body is a Turbo Stream fragment, and Turbo applies it without any
application JavaScript. A terminal ignores that event name and draws the JSON.
The sequence numbers are shared, so a reconnect from any surface asks the
same question and gets the same answer.

An event carries the same entry shape that `inspect` returns. A surface that
loads a run and then follows its stream renders one shape, and there is no
second renderer to keep in step.

## The live actor is the fan-out point

Tokens stream from the model effect. Tool output streams from a process or
from a surface. Viewers join and leave. Typing starts and stops. All of that
is true for one turn or one connection, and none of it is a fact the run
must keep.

The `live/{run}` actor collects all of it and broadcasts it on the run's
topic. It exists so that the run actor records only facts and the cable
sends only what a viewer can draw. A surface that opens mid-turn asks the
live actor for the partial answer and the current viewers, then follows the
stream.

Presence used to be a separate actor. Presence and deltas share one topic,
one lifetime, and one reason to exist, and two actors for one topic gave the
cable two senders to order. One actor is enough.

## Watching a teammate is a feed, not a record

The `activity` actor knows which runs are thinking or acting right now, who
owns them, who is watching them, and who is typing where. Every run tells it
on each status change. Every `live` actor tells it when a viewer joins or
leaves. It broadcasts on the `workspace` topic and on one topic per member.

It is ephemeral on purpose. What happened in a run is in the run's journal.
Who looked at what, and when, is not history the team needs, and a durable
record of it would be a record of people watching people. A restart rebuilds
the feed from the loaded runs and from the surfaces that reconnect.

Following a teammate is subscribing to their topic. The surface then sees
their runs start, think, act, and go idle, and can open any run they may
read. The run itself decides that.

## Tools run where the surface says

A run started from the web runs its tools in the deployment's sandbox. A run
started from a terminal runs its tools on that machine, the way Claude Code
does, because the files and the shell the person means are there.

The `tool/{run}/{call}` actor owns one invocation either way. With the
deployment executor it owns a process. With a surface executor it owns a
pending request that the surface's stream carries out, and it waits for the
surface to report. If the surface disconnects for longer than the call's
timeout, the call fails with `executor.gone`, and the run waits for a person.
Output goes through the `live` actor in both cases, so a teammate on the web
watches a terminal-driven run as it happens.

## Streaming is ephemeral

Recording each token as a command would multiply the journal by the number
of tokens per answer and would add nothing, because the complete answer is
the fact. A crash during a turn loses the partial output. That is correct.
The run actor sees no `model.answered` for that turn, and on `actors.resume`
it issues the model call again. A surface reloads and sees the last
committed state.

## The catalogue is durable and the search index is not

Which runs exist is domain truth. The `runs` actor decides it. It records
that a run was opened, retitled, shared, closed, archived, restored, or
deleted. Those are decisions, and no other store holds them.

Whether a run matches a search is a derived answer. The `search` actor
writes a SQLite file with a full-text index through an effect. The file uses
`Migrations.derived`, so any mismatch deletes and rebuilds it from the
catalogue and the run states. Keeping this in a journal would be a second
record of one truth.

The catalogue is partitioned by month. A busy team opens about 10,000 runs a
month, and that is a 10 MB partition. A listing of recent runs opens one or
two partitions. Anything older goes through search, whose database spans
every month.

## Membership and visibility are two questions

The `workspace` actor answers whether a subject is a member and with which
role. The `run` actor answers whether that member may read this run. A run
is visible to the workspace, private to its owner, or shared by link. The
run owns that decision, because the actor that owns a resource owns its
authorization. That is the rule the `identity` design states, and this
design keeps it.

The catalogue holds a copy of each run's visibility so that a listing does
not summon every run. The run tells the catalogue when its visibility
changes. The copy is a derived view and it carries the run's sequence number
as its deduplication key.

Workspace visibility is the default. The team is small and the product is
built for watching each other work. Private is a choice a person makes per
run.

## The budget is a ledger

Every turn spends tokens. A workspace budget must stop a run that would
exceed it, and it must survive a restart. Both need a serialized decision
with a durable record. The `budget` actor is that record. It uses
`Durability.full` because nobody can reconstruct a spend ledger from
anywhere else.

A ledger grows by one command per turn. A month partition bounds it. The run
asks the current month's budget for a reservation before each turn and
reports the real spend after it. A new month is an empty actor with no
history to replay.

## Archive is a workflow

Moving a run to cold storage has several steps: freeze the run, evict it,
checkpoint and copy its journal, copy its blobs, verify the digests, record
the location in the catalogue, and erase the hot copy. A crash between any
two steps must not lose the run and must not leave two copies that both
claim to be current.

The `archive` actor records each accepted step. Replay resumes the workflow
at the first step that has not completed. Every step is idempotent: copying
a file that exists with the same digest is a no-op, and erasing a file that
is gone is a no-op. This is the coordinator pattern from `actors`.

## An archived address must not be written

Every address always exists. A message to `run/{run}` after its journal
moved to cold storage would summon an empty run and write a new hot journal.
Two journals would then exist for one id.

The design closes that path at the boundary. The web feature and the
catalogue check the catalogue state before they send anything to a run. An
archived run accepts only `restore` through the catalogue. The run
definition also refuses any command before `run.open`, so a stray message
records one refusal and changes no state. A restore replaces the stray
journal with the cold copy and records that it did so.

## Nothing is deleted on its own

`Logs` in `actors` never deletes a log, and this design adds no sweeper. An
idle run is evicted from memory. Its journal stays. A run leaves the hot
store only through `archive` and leaves the system only through `delete`.
Both are explicit operations that a person with the right role runs, and
both are recorded in the catalogue with who ran them and when.

Deletion writes a tombstone. The catalogue keeps the id, the date, and the
subject who deleted the run. A listing can show that a run existed. Nothing
can bring it back.

## Effects run at most once and the run re-issues them

A model call costs money. A tool call can change the world. Neither is safe
to repeat blindly, so every run uses the default at-most-once effect mode of
`actors`. A crash between appending `turn.requested` and receiving
`model.answered` leaves a turn without an answer.

The run knows this from its own state on `actors.resume`. It re-issues the
model call with the same turn number as its idempotency key. A tool call is
re-issued only when its definition says the tool is idempotent. Otherwise
the run records `run.pause` and asks a person, on every surface, whether to
repeat it.

## What is deliberately not built

A snapshot of a run's state, a second process that shares one log root, a
transport to a system that runs tools elsewhere, and a live inspector of the
actor system itself. `actors` leaves seams for each of them. This design
uses `Ownership` for the first, `Transport` for the second, and `traces()` for
the last, and it builds none of them.
