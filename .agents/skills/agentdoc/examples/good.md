# Good examples

These comments follow agentdoc. Copy the shape, not the domain words, unless
you are documenting the same type.

## Type: what it is, how to build it, then the invariant

```java
/// An Elm-style runtime. It holds a state, the updates that apply messages to
/// that state, and the effects those updates request.
///
/// Call [#of] to create an application. Call [#on] to register what each
/// message type does. Call [#effect] to register how each effect type runs.
/// Then call [#dispatch] with a message and wait until the queue is empty. Or
/// drive the two halves yourself.
///
/// ```
/// var app = Application.of(0)
///         .on("add", (state, message) -> Step.of(state + 1,
///                 Effect.of("log").with("line", "added")))
///         .effect("log", (effect, emit) ->
///                 System.out.println(effect.string("line", "")));
///
/// app.dispatch(Message.of("add"));
/// ```
```

The first paragraph names the parts. The second names the calls in order.
The fence is a program. Invariants that the fence cannot show come after.

## Two halves, named, then why both are public

```java
/// [#advance] applies one message. It returns the effects that the update
/// requested. It does not run an effect. [#perform] runs effects. It returns
/// the messages that those effects emitted. [#dispatch] is the closed loop:
/// it advances, it performs, it puts the result back on the queue, and it
/// repeats until the queue is empty. That loop is not the only one. A durable
/// actor calls [#advance] on a logged message and never calls [#perform]. Replay
/// then rebuilds state and does not send last week's email again. Both methods
/// are public for that reason.
```

Order of operations first. Exception second. Reason last. No semicolon.

## Failure as behavior

```java
/// If an update throws, the state does not change. The update requests one
/// effect that sends an `error` message. If an effect has no handler, or if a
/// handler throws, the runtime emits `error` too. Failures follow the same
/// path as any other message. A caller that suppresses effects also suppresses
/// a second report of a failure that it already recorded. If an update throws
/// while it handles `error`, it produces nothing. An application that reports
/// an error about an error never stops.
```

No slogan. Each sentence is one path. The last sentence is why the previous
sentence exists.

## One thread, one wait bound, two counters

```java
/// Effect handlers run at the same time. They may only emit. Only the
/// dispatching thread reads and writes the state. The effects of one step
/// share a wait bound ([#patience]). The default is to wait as long as they
/// take. If a step stops waiting, it leaks the remaining threads, it counts
/// them in [#abandoned], and it drops anything they emit later ([#fenced]).
```

Actor named. Hedge kept (“may only emit” is a rule, not a guess). Leak stated
as fact, not as “the trade-off, stated plainly.”

## Two policies, two sentences

```java
/// Two updates for the same message type both run, in registration order.
/// Two effect handlers for the same type do not. The second replaces the
/// first. That is how a test swaps a handler.
```

Compose vs replace split. The test sentence attaches only to replace.

## Method comment: invariant, not the signature

```java
/// Updates the state with one message. No effect runs here.
///
/// The state is committed before this returns. A caller that wants the state
/// without the consequences calls this and never calls [#perform].
```

What it does. What it does not do. Who calls it that way.

## Method: contract from the body

```java
/// Runs the effects of one step. Returns the messages they emitted.
///
/// An empty list returns immediately. Each effect runs on its own virtual
/// thread. This method does not change the state. [#advance] does that.
///
/// This method returns when every effect has finished, or when
/// [#patience(Duration)] runs out. Null patience waits with no bound. The
/// returned list is a snapshot. It stops changing when the step closes the
/// fence. Order in the list is arrival order, not argument order.
///
/// If patience runs out, the step interrupts the remaining threads and leaks
/// them. The step counts them in [#abandoned()]. The step emits
/// `error.timeout` with `reason` and `while`. Anything those threads emit
/// later is dropped and counted in [#fenced()].
///
/// A Java thread in native code does not stop. The alternative to the leak
/// is a step that never returns. A handler must still set its own timeout.
/// Patience does not replace that.
///
/// If a handler throws, or if an effect has no handler, the step emits
/// `error`. This method does not throw. If the dispatching thread is
/// interrupted while it waits, the step emits `error`.
```

Empty case. Default. Each emitted type. Frozen return. Arrival order. Paired
method. Caller procedure. One topic per paragraph. No private `#run`.
