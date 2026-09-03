# Explanation

This document explains the proposed `agents` actor hierarchy. It is not an
implementation. Read [reference.md](reference.md) for the exact rules and
[README.md](README.md) for the lifetime and size table.

## The transcript is the journal

A run actor records every message it handles. The model's answer arrives as
the command `model.answered`. A tool's result arrives as `tool.finished`. A
person's message arrives as `run.say`. The journal of the run actor therefore
holds the whole conversation, in order, with the timestamp of each entry.

Nothing copies the transcript into a second store. The run page reads the
run's state with `ActorSystem.inspect`. A scrubber reads an earlier state with
`ActorSystem.inspectAt`. A reader can open the journal while the actor
appends, because a journal uses write-ahead logging.

This follows the law of `actors`: effects never run during replay. Replaying a
run rebuilds its state from the recorded answers. It never calls the model
again and it never runs a tool again. A replay of a 1,000-turn run costs a
few hundred milliseconds of SQLite reads and JSON parsing. It costs no tokens.

## A run is one actor and a turn is not

A turn is one model call and the tool calls it requests. A run holds many
turns. The decisions that must be serialized belong to the run: whether the
next turn may start, whether a tool result is still wanted, whether the run is
cancelled. One actor owns those decisions.

A turn actor would split one conversation across many logs. Every page would
then join them. The run's log is bounded by the size of one conversation, and
that bound is what makes one actor per run enough.

## A subagent is a run

An agent that delegates work spawns a child run at `run/{ws}/{run}/{child}`.
The child is the same actor type with a nested id. It has its own transcript
and its own budget line. The parent records `child.spawned` and later
`child.finished` with the child's summary. It never holds the child's
transcript.

This keeps every log bounded by one conversation. It also makes the subtree
of one run one prefix listing. `Logs.catalogue("run", "{ws}/{run}/")` names
every descendant without an index, and an archive of the parent moves the
whole subtree.

## Streaming is ephemeral

A model streams tokens. Recording each token as a command would multiply the
journal by the number of tokens per answer and would add nothing, because the
complete answer is the fact. The `stream` actor collects partial output for
one turn and broadcasts it to open tabs. When the turn ends, the run records
the complete answer and the stream actor resets.

A crash during a turn loses the partial output. That is correct. The run
actor sees no `model.answered` for that turn, and on `actors.resume` it
issues the model call again. A viewer reloads and sees the last committed
state.

## Tools are ephemeral actors

A tool invocation owns a process, a network connection, or a sandbox. That
resource dies with the process, so nothing about it belongs in a log. The
`tool` actor owns one invocation. It schedules its own timeout, it accepts
`tool.cancel`, and it reports `tool.finished` to the run. Output above the
inline limit streams to a blob and the command carries the hash.

The run records the result. The invocation actor records nothing. A restart
kills every running tool, and each run re-issues the calls it was waiting on.
The run's own log says which calls those are.

## The catalogue is durable and the search index is not

Which runs exist is domain truth. The `runs` actor decides it. It records
that a run was opened, retitled, shared, finished, archived, restored, or
deleted. Those are decisions, and no other store holds them.

Whether a run matches a search is a derived answer. The `search` actor writes
a SQLite file with a full-text index through an effect. The file uses
`Migrations.derived`, so any mismatch deletes and rebuilds it from the
catalogue and the run states. Keeping this in a journal would be a second
record of one truth.

The catalogue is partitioned by year. A workspace that opens 50,000 runs in a
year has a 15 MB catalogue for that year. Listing recent runs opens one
partition. Browsing older runs opens another.

## Membership and visibility are two questions

The `workspace` actor answers whether a subject is a member and with which
role. The `run` actor answers whether that member may read this run. A run is
visible to the workspace, private to its owner, or shared by link. The run
owns that decision, because the actor that owns a resource owns its
authorization. That is the rule the `identity` design states, and this design
keeps it.

The catalogue holds a copy of each run's visibility so that a listing does
not summon every run. The run tells the catalogue when its visibility
changes. The copy is a derived view and it carries the run's sequence number
as its deduplication key.

## The budget is a ledger

Every turn spends tokens. A workspace budget must stop a run that would
exceed it, and it must survive a restart. Both need a serialized decision
with a durable record. The `budget` actor is that record. It uses
`Durability.full` because nobody can reconstruct a spend ledger from
anywhere else.

A ledger grows by one command per turn. A month partition bounds it. The
run asks the current month's budget for a reservation before each turn and
reports the real spend after it. A new month is an empty actor with no
history to replay.

## Archive is a workflow

Moving a run to cold storage has several steps: freeze the run, evict it,
checkpoint and copy its journal, copy its blobs, verify the digests, record
the location in the catalogue, and erase the hot copy. A crash between any two
steps must not lose the run and must not leave two copies that both claim to
be current.

The `archive` actor records each accepted step. Replay resumes the workflow
at the first step that has not completed. Every step is idempotent: copying
a file that exists with the same digest is a no-op, and erasing a file that
is gone is a no-op. This is the coordinator pattern from `actors`.

## An archived address must not be written

Every address always exists. A message to `run/{ws}/{run}` after its journal
moved to cold storage would summon an empty run and write a new hot journal.
Two journals would then exist for one id.

The design closes that path at the boundary. The web feature and the
catalogue check the catalogue state before they send anything to a run. An
archived run accepts only `restore` through the catalogue. The run
definition also refuses any command before `run.open`, so a stray message
records one refusal and changes no state. A restore replaces the stray
journal with the cold copy and records that it did so.

## Nothing is deleted on its own

`Logs` in `actors` never deletes a log, and this design adds no sweeper. A
finished run settles and is evicted from memory. Its journal stays. A run
leaves the hot store only through `archive` and leaves the system only
through `delete`. Both are explicit operations that a person with the right
role runs, and both are recorded in the catalogue with who ran them and
when.

Deletion writes a tombstone. The catalogue keeps the id, the date, and the
subject who deleted the run. A listing can show that a run existed. Nothing
can bring it back.

## Effects run at most once and the run re-issues them

A model call costs money. A tool call can change the world. Neither is safe
to repeat blindly, so every run uses the default at-most-once effect mode of
`actors`. A crash between appending `turn.requested` and receiving
`model.answered` leaves a turn without an answer.

The run knows this from its own state on `actors.resume`. It re-issues the
model call with the same turn number as its idempotency key. A model provider
that supports idempotency keys returns the same answer. One that does not
returns a new answer, and the run records whichever answer arrives first.
The same rule applies to a tool call: the run re-issues it, and a tool that
is not idempotent must say so in its definition so that the run asks a
person before it repeats the call.

## What is deliberately not built

A snapshot of a run's state, a second process that shares one log root, a
transport to a system that runs tools elsewhere, and a live inspector of the
actor system itself. `actors` leaves seams for each of them. This design
uses `Ownership` for the first, `Transport` for the second, and `traces()` for
the last, and it builds none of them.
