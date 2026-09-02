# Bad examples

These comments fail agentdoc. Each block names the failure. Do not copy
them. The language of each example does not matter. The failure is the
same in any of them.

## Slogan, then the idea

```java
/// A mini-framework for The Elm Architecture.
///
/// Applications compose. Fail open. One thread of dispatch.
```

Failures: sells ("mini-framework"), names a school instead of the type,
three slogans with no mechanics. An agent cannot tell what to call.

## Metaphor first

```python
class Application:
    """An application is a fold over a stream of messages, with effects as
    the residue of each step; think Redux, but the store does not run I/O.
    """
```

Failures: metaphor, semicolon, "think X," two architectures in one breath.
The agent still does not know `advance` from `dispatch`.

## The identifier in other words

```go
// GetDocuments returns the documents of the given package.
func (c *Catalog) GetDocuments(pkg string) []Document
```

Failures: restates the signature. Nothing says when to call it, whether the
list is empty for an unknown package, or whether the call reads a file.
Compare: "The documents of one package, in file-name order. An unknown
package has none. This reads the index and never the disk."

## The constant that is not a default

```typescript
/** The default limit on how many matches search returns. */
export const MATCHES = 25;
```

Failures: `search(text, limit)` takes the limit as a parameter and has no
default. The comment describes what the author thought the value was for,
not what the code does. Say what it is and who passes it: "The number of
matches one search answers with. Pass it as `limit` to `search`."

## The wrong mechanism, well phrased

```rust
/// Tries each shape in order and returns the first that matches.
pub fn any(catalog: &Catalog, name: &str) -> Option<Answer>
```

Failures: the body reads the name as exactly one shape and never tries a
second. The sentence is fluent and false. It came from rewording an older
comment without reading the body. Step 6 of the process exists for this.

## The invented reason

```python
class Scope:
    """Setting recursive also sets members, since there is nothing to
    recurse into otherwise."""
```

Failures: the code sets `members` when `recursive` is set. It does not say
why, and the reason given is made up. State the rule: "`recursive` implies
`members`." Add a reason only when the code shows one.

## The condition that became a fact

```go
// The last generation held the name, or it held the name's package.
func (i *Index) projectMayHold(name string) bool
```

Failures: the function tests a condition. The comment asserts it. A reader
now believes the generation always holds the name. Write the test:
"Whether the project could hold this name: its last generation held the
name or the name's package."

## History

```typescript
/**
 * The CLI and the browser used to parse and describe a name separately,
 * and their two descriptions could drift apart. This class reads a name
 * once for both of them.
 */
```

Failures: narrates what the code no longer does. The reader calls the code
as it is. Keep the last sentence and delete the story.

## The slogan repeated across comments

```markdown
Each name in the list is a name you can ask for.
...
The name of a group is a name you can ask for next.
...
The symbol comes first because it is what a reader hands back.
```

Failures: one phrase, coined once, then used as filler in three places.
Each place should say the concrete action: "Pass any name in the list to
`tuul docs`."

## Justification glued to a rule

```rust
/// Prints every overload with its whole comment. The whole comment,
/// because the member is what was asked for.
```

Failures: the second sentence is an opinion about the first. The rule is
complete without it. Delete it.

## Two policies in one sentence

```java
/// Registering a second update runs both, and registering a second effect
/// handler replaces the first, which is how tests and composition work.
```

Failures: updates compose and effects replace are different rules. "Which
is how tests and composition work" explains two audiences with one clause.

## Synonym rotation

```python
def perform(self, effects):
    """Handlers execute effects, carry out I/O, and then fire messages back
    so the runtime can process them and apply the result to state."""
```

Failures: execute / carry out / fire / process / apply for one pipeline.
Pick run and emit and return, and keep them.

## Rotation across files

```
store.go:    // A failed build is recorded against the stamp.
index.go:    // A failed build is written down against the stamp.
catalog.go:  // The store keeps the failure under the stamp.
```

Failures: three files, three verbs for one event. Each comment is fine
alone. Together they make the reader wonder whether "recorded" and "kept"
are two things. This happens when three authors edit three files with no
shared verb list.

## Hedge upgraded, actor missing

```java
/// Effects are performed concurrently; the state is updated safely.
```

Failures: passive, semicolon, "safely" is a marketing adjective. The code
says only the dispatching thread writes state. Say that.

## Narrates the parameters

```typescript
/**
 * @param type the type of the message
 * @param update the update to run
 * @returns this application
 */
```

Failures: restates names. If a tag adds nothing the identifier does not
say, delete the tag. Prose belongs in the block above the member.

## Example that is not a program

```go
// Typical usage: wire updates, then effects, then dispatch in a loop
// until idle.
```

Failures: tells the agent to imagine a loop. Show the loop, or show
`Dispatch`, in a code block.

## Implementation tour

```java
/// perform() creates a virtual-thread executor, shuts it down, waits on
/// patience, then interrupts stragglers because StructuredTaskScope is still
/// preview and would pin class files to one JDK build.
```

Failures: how, not what. The class comment must not justify the platform.
Put that next to `perform` only if a caller must know the leak, and then
state the leak: abandoned threads, fenced messages.

## Collapsed types, omitted default

```rust
/// Runs the effects. Returns when they finish or when the wait bound runs
/// out. Failures become an error message.
```

Failures: "wait bound" is a second name for `patience`. A `None` patience
does not run out. `error` and `error.timeout` are two types. Empty input
has no sentence. The return list is not said to freeze.

## Two topics in one paragraph

```python
"""If the wait bound runs out, remaining threads are interrupted and
leaked, counted in `abandoned`, and anything they emit later is dropped."""
```

Failures: timeout path, leak, and fence in one sentence. Passive "are
counted." Split. Name the actor: the step counts them.

## Markdown lead-in that says nothing

```markdown
A member is a name too, and so is the source behind any name:

$ tuul docs invoicing.Invoice#compareTo --code
```

Failures: the line before the command is a flourish. It does not say when
to run the command. Write: "Ask for one member as `Type#member`. Add
`--code` to print its source:".

## Markdown that opens with the system instead of the reader

```markdown
Everything the command knows is in `build/index.db`, and it is refreshed
only when a question needs it.
```

Failures: a fact about storage where the reader wanted to know what to do.
Open with the action: "Run `tuul docs` while the project is broken or
mid-edit. A question about the standard library does not wait for the
project to compile."
