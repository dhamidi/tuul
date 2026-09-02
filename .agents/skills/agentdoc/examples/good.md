# Good examples

These comments follow agentdoc. Copy the shape, not the domain words, unless
you are documenting the same type. The language of each example does not
matter. The shape is the same in any of them.

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

## Module: which entry point for which situation

```python
"""Turns a name a reader typed into the object that describes it.

Call this from anything that answers a reader. The CLI prints the object
and the browser renders it. Both get the same object for the same name.

Start with `any` when the name can be any shape. Call `symbol`, `member`,
or `document` when the shape is already known. Call `search` for words
instead of a name, `roots` when there is no name yet, and `code` to turn
an answer into the text it was read from.
"""
```

The reader learns which function to call before learning any of them.

## Value: what it means and who passes it

```typescript
/**
 * The number of matches one search answers with. Pass it as `limit` to
 * `search` unless the caller has a reason for another number.
 */
export const MATCHES = 25;
```

Not "the default." The function has no default. The comment says what the
value is for and where it goes.

## Options: each field, what it adds, and the default value

```rust
/// How much of a symbol to include. Pass [`Scope::BARE`] for the symbol
/// alone.
///
/// `all` includes members that are not public. `members` adds a
/// description of every type the symbol holds, under `members`.
/// `recursive` adds subpackages to that, and implies `members`.
/// `documents` adds the body of every package document instead of only
/// its title.
pub struct Scope { pub all: bool, pub members: bool, pub recursive: bool, pub documents: bool }
```

Each field: what it adds and where it lands in the result. The implication
is stated as a rule with no invented reason.

## Dispatch: which input goes where, and the empty case

```go
// Any answers a name of any shape.
//
// The shape decides the answer. "web/tutorial" is a document and goes to
// Document. "json.Json#parse" is a member and goes to Member. Anything
// else is a symbol and goes to Symbol. A name is read as one shape only.
// The result is nil when the catalog has nothing by that name in that
// shape.
func Any(catalog Catalog, name string, scope Scope) *Answer
```

Every branch has a sentence. "One shape only" tells the reader that a
member name is never retried as a symbol.

## Structured result: field by field

```typescript
/**
 * One member of a type, by name.
 *
 * The object is the type's description with `member` set to the name,
 * `methods` holding every overload of that name, and `fields` holding a
 * field of that name. With `all`, non-public members count too. The result
 * is undefined when `member` is empty, when the type does not exist, or
 * when the type has no member of that name.
 */
```

Three fields named. Three empty cases named. Nothing about how.

## Fallback: what is tried, in what order, and how the result says so

```python
def search(catalog, text, limit):
    """Searches names and comments for words.

    The result holds `query`, `every`, and `groups`. Blank text answers
    with no groups.

    The catalog first looks for what holds every word, at most `limit`
    matches. When that finds nothing, it looks for what holds any word and
    sets `every` to False. A symbol appears once, so three overloads of one
    method are one match.
    """
```

Order of attempts. The flag that reports which attempt won. The empty
input. The dedupe rule as a fact about the result.

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

Order of operations first. Exception second. Reason last, and the reason is
visible in the code: two public methods and a caller that uses one.

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
sentence exists, and the code shows it.

## Degraded mode: what still answers and how the reader finds out

```go
// Everything the index knows is in index.db. When the project does not
// compile, the index records the failure against the current sources and
// keeps answering from the rows of the last build that compiled. Problems
// returns that failure. The same sources are not compiled again until
// they change.
```

What happens, what still works, where the reader reads the reason, and
what the index will not do. Each is one sentence.

## A condition documented as a condition

```rust
/// Whether the project could hold this name, judged from its last
/// generation without compiling. True when that generation held the name
/// or the name's package. True for a project that was never indexed, since
/// it could hold any name.
fn project_may_hold(&self, name: &str) -> bool
```

"Whether" and "True when" keep it a test. The last reason is the one the
code shows: no generation, no evidence either way.

## One thread, one wait bound, two counters

```java
/// Effect handlers run at the same time. They may only emit. Only the
/// dispatching thread reads and writes the state. The effects of one step
/// share a wait bound ([#patience]). The default is to wait as long as they
/// take. If a step stops waiting, it leaks the remaining threads, it counts
/// them in [#abandoned], and it drops anything they emit later ([#fenced]).
```

Actor named. Hedge kept ("may only emit" is a rule, not a guess). Leak
stated as fact.

## Two policies, two sentences

```typescript
/**
 * Two updates for the same message type both run, in registration order.
 * Two effect handlers for the same type do not. The second replaces the
 * first. That is how a test swaps a handler.
 */
```

Compose and replace split. The test sentence attaches only to replace.

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
method. Caller procedure. One topic per paragraph. No private helper named.

## Markdown: a lead-in that says when

```markdown
Use search when you do not know the full symbol name:

$ tuul docs --search "event stream"
```

The sentence before the command is the condition for running it. Nothing
else.

## Markdown: a paragraph that opens with the reader's action

```markdown
Run `tuul docs` while the project is broken or mid-edit. A question about
the standard library does not wait for the project to compile. A question
about the project is answered from the last build that compiled, with the
compiler's messages after a `warning:` on standard error.
```

Action first. Then what happens on each kind of question. Then where the
reader sees the reason.
