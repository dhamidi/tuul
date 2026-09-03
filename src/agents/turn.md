# Explanation: one turn, many tools

This document zooms in on the action phase of one turn in a `run` actor:
the model asks for several tools at once, some fail, some take too long and
move to the background, some report progress, and the turn finishes. Read
[reference.md](reference.md) for the exact commands and events.

## The shape of a turn

A turn is one model call and the tool calls it requests. It has three
phases:

1. **Thinking.** The run has recorded `turn.requested` and waits for
   `model.answered`.
2. **Acting.** The run has recorded the answer and waits for the results of
   the calls it requested.
3. **Finishing.** Every foreground call has a result. The run either
   requests the next turn with those results, or, when the model stopped
   without calls, goes `idle`.

The run actor is the only place that decides when acting ends. Tool actors
report. They never decide.

## Calls start together

`model.answered` carries a list of calls. The run records the answer as one
command and then starts every call in that list, in list order, in one step.
Each call gets its own ephemeral actor at `tool/{run}/{call}`. Actor effects
run in list order, so the spawn and `tool.start` of call 1 enter the runtime
before those of call 2, and the tool actors then run concurrently on their
own threads.

The run keeps a table of pending calls in its state:

```
pending:
  c1  read-file   foreground  started 12:00:00.100
  c2  grep        foreground  started 12:00:00.101
  c3  test-suite  foreground  started 12:00:00.102
  c4  fetch       foreground  started 12:00:00.103
```

Two limits bound the table. A project sets `limits.parallel`, eight by
default, and a call past that limit waits in the table as `queued` until
another call reports. The deployment's process handler bounds the whole
machine separately. The model never sees a queue. It asked for four calls
and it gets four results.

For each call the run also emits a scheduled message to itself,
`call.deadline {call}`, at the tool's `timeout`. A timer is an effect and
does not survive replay, and the run re-arms it on `actors.resume` from the
start time recorded in the pending table.

## A result is a fact, whatever it says

A tool actor ends in exactly one way: it tells the run `tool.finished`. The
body says what happened:

| Outcome | `tool.finished` body |
|---|---|
| success | `result` inline, or `blob` when the output passed 64 KiB, and `duration` |
| tool error | `error {kind: "failed", code, message}` and whatever output there was |
| timeout | `error {kind: "timeout"}`. The tool actor killed the process. |
| cancelled | `error {kind: "cancelled", by}` |
| executor gone | `error {kind: "executor-gone"}`. A surface call whose connection closed for longer than its timeout. |
| denied | `error {kind: "denied", by}`. A person answered a permission question with no. |
| lost | `error {kind: "lost"}`. The deadline fired and no tool actor reported. |

Every one of those is one recorded command. A failed tool never fails the
run and never fails the turn. The model reads the error on its next turn
and decides what to do about it, the same way a person reading a terminal
would. The only way a call leaves the pending table is a `tool.finished`
command, and the only way that command is missing is a deadline that
records one.

That last row is what makes the design closed. A tool actor is ephemeral
and can die of an `Error` without reporting. The run does not watch tool
actors, because `actors` has no links or monitors. It watches the clock it
set for itself. When `call.deadline` arrives for a call that is still
pending, the run records `tool.finished` with `lost`, and the turn goes on.

## Backgrounding is the run's decision

Some tools take minutes. A test suite, a build, a crawl. Waiting for them
would stall the turn and every surface with it. The run backgrounds a call
in one of three ways:

- the tool's definition says `background: always`;
- the model asked for it with the `background` argument;
- the call passed the tool's `foreground` budget, thirty seconds by default,
  and the definition allows `background: auto`.

The run records `call.backgrounded {call, reason}`. The call stays in the
pending table with status `background`. The tool actor notices nothing. It
keeps running, keeps reporting progress, and reports `tool.finished` when
it is done.

For the model the run synthesizes the call's result at once: "running in the
background as task c3, you will be told when it finishes". That text is not
a command. The definition produces it from the recorded `call.backgrounded`
whenever it builds the context, so replay produces it again without any
special case. The turn's foreground set is now three calls, and the turn can
finish without c3.

To a person watching, nothing changed. The surface still shows c3 running,
with its progress and its output tail. Backgrounding is transparent to the
watcher and visible to the model.

## Progress is live and not recorded

A tool reports progress in two ways. Output is one: a process writes to its
standard streams and the tool actor forwards chunks to `live/{run}` as
`live.output`. Structured progress is the other: a tool that knows its
fraction, its current step, or a count tells the tool actor `tool.progress
{fraction, message}` and the actor forwards it as `live.progress`.

Neither is a command. The run never sees progress and the journal never
holds it. What the journal holds is the final `tool.finished` with the
output blob and the duration, which is enough to show afterwards that c3
took four minutes and wrote twelve megabytes.

A surface that opens mid-call asks `live.now`. The live actor keeps the
last 64 KiB of output and the last progress message per running call, so
the surface draws the present before it follows the stream.

## The turn finishes

The action phase ends when every call in the pending table is either
`finished` or `background`. The run then does one of two things:

- If the answer that started this turn had calls, the run requests the next
  turn. The context for it carries every foreground result and every
  background placeholder in the order the model requested the calls,
  whatever order they reported in.
- If the answer had no calls and the stop reason was `end`, the response is
  done. The run tells the catalogue and the activity feed `idle` and waits.

Result order is the model's order and not arrival order, so the context is
a pure function of the journal and replay builds it identically.

## A background call finishes later

The tool actor of a backgrounded call reports `tool.finished` like any
other, possibly turns later, possibly when the run is `idle`. The run
records it at once. What it does next depends on its status:

| Run status | What happens |
|---|---|
| `thinking` | The result waits. It is delivered as a notification in the context of the next turn. |
| `acting` | The same. The result does not end the current action phase, because it was not in the foreground set. |
| `idle` | The run starts a turn with reason `background.finished` so that the model can act on it. The project's `limits.wake` may turn this off, and then the result waits for the next person's message. |
| `waiting` | The result waits. The person's question stands. |
| `closed` | The result is recorded and nothing else happens. |

A notification is a message in the model's context that says which task
finished and carries its result. The definition renders it from the
recorded `tool.finished` of a backgrounded call, so it is as replayable as
everything else.

## Cancellation

A person cancels one call with `tool.cancelled {call, by}` from any
surface. The run tells the tool actor `tool.cancel`, and the actor kills its
process, or tells the surface to, and reports `cancelled`. A `run.pause`
cancels nothing and lets the pending calls finish. A `run.close` cancels
every pending call, background ones included.

## A crash during the action phase

The process restarts with c1 finished, c2 failed, c3 in the background, and
c4 still pending. Replay rebuilds the pending table from the journal: c1
and c2 have results, c3 is `background` with no result, c4 is `foreground`
with no result. No effect ran during replay, so no tool actor exists.

`actors.resume` then applies one rule per pending call:

| Call | Tool is idempotent | What the run does |
|---|---|---|
| c3, background | yes | Spawns the tool actor again with the same call id. |
| c3, background | no | Records `run.pause {reason: "tool.repeat", call}` and asks a person on every surface. |
| c4, foreground | yes | Spawns it again and re-arms the deadline from the recorded start. |
| c4, foreground | no | Records `run.pause` as above. |

The deadline is re-armed from the original start time, so a crash does not
extend a timeout. A call whose deadline already passed during the outage
gets `lost` at once, and the turn finishes on that.

## The worked example

The model answers turn 3 with four calls. Wall-clock is on the left, the
journal row the run appends is in the middle, and the live events every
surface receives are on the right. Progress and output are events only.

```
time        journal (seq: type)                    live events on run:{run}
12:00:00.0  41: model.answered {calls: c1..c4}      entry answer; status acting
12:00:00.1                                          call c1 started
12:00:00.1                                          call c2 started
12:00:00.1                                          call c3 started
12:00:00.1                                          call c4 started
12:00:00.4  42: tool.finished c1 {result}           entry result c1
12:00:00.9  43: tool.finished c2 {error failed 1}   entry result c2
12:00:05.0                                          progress c3 {fraction 0.1, "12/120"}
12:00:05.0                                          output c3 "PASS test_a ..."
12:00:30.1  44: call.backgrounded c3 {auto}         entry backgrounded c3
12:00:45.0                                          progress c3 {fraction 0.4}
12:01:00.1  45: tool.finished c4 {error timeout}    entry result c4
12:01:00.1  46: turn.requested {turn 4}             status thinking
12:01:00.1                                          delta ... delta ...
12:01:04.0  47: model.answered {calls: []}          entry answer; status idle
12:03:58.0                                          progress c3 {fraction 1.0}
12:03:58.2  48: tool.finished c3 {blob, 238 s}      entry result c3
12:03:58.2  49: turn.requested {turn 5, wake}       status thinking
12:04:02.0  50: model.answered {calls: []}          entry answer; status idle
```

Nine commands describe two responses and four tools. The context for turn
4 holds the results of c1, c2, and c4 and the placeholder for c3, in that
order. The context for turn 5 holds the same transcript plus a notification
that c3 finished, with the blob reference for its output. A replay of rows
41 to 50 produces both contexts without running anything.

## What this costs

A turn with `n` calls appends `n + 1` commands plus one per backgrounded
call. A call's journal cost is its result, capped at 64 KiB inline. A
four-minute test run that wrote twelve megabytes costs one 200-byte row and
one blob. The live actor holds at most 64 KiB of tail per running call.
Progress costs nothing durable at all.
