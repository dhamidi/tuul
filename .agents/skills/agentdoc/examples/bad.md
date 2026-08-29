# Bad examples

These comments fail agentdoc. Each block names the failure. Do not copy them.

## Slogan, then the idea

```java
/// A mini-framework for The Elm Architecture.
///
/// Applications compose. Fail open. One thread of dispatch.
```

Failures: sells (“mini-framework”), names a school instead of the type,
three slogans with no mechanics. An agent cannot tell what to call.

## Metaphor first

```java
/// An application is a fold over a stream of messages, with effects as the
/// residue of each step; think Redux, but the store does not run I/O.
```

Failures: metaphor, semicolon, “think X,” two architectures in one breath.
The agent still does not know `#dispatch` from `#advance`.

## Two policies in one sentence

```java
/// Registering a second update runs both, and registering a second effect
/// handler replaces the first, which is how tests and composition work.
```

Failures: updates compose and effects replace are different rules. “Which is
how tests and composition work” explains two audiences with one clause.

## Synonym rotation

```java
/// Handlers execute effects, carry out I/O, and then fire messages back so
/// the runtime can process them and apply the result to state.
```

Failures: execute / carry out / fire / process / apply for one pipeline.
Pick run and emit and return, and keep them.

## Hedge upgraded, actor missing

```java
/// Effects are performed concurrently; the state is updated safely.
```

Failures: passive, semicolon, “safely” is a marketing adjective. The code
says only the dispatching thread writes state. Say that.

## Narrates the signature

```java
/// @param type the type of the message
/// @param update the update to run
/// @return this application
```

Failures: restates names. If a tag adds nothing the identifier does not say,
delete the tag. Prose belongs in the `///` block above the method.

## Example that is not a program

```java
/// Typical usage: wire updates, then effects, then dispatch in a loop
/// until idle.
```

Failures: tells the agent to imagine a loop. Show the loop, or show
`#dispatch`, in a code fence.

## Implementation tour

```java
/// perform() creates a virtual-thread executor, shuts it down, waits on
/// patience, then interrupts stragglers because StructuredTaskScope is still
/// preview and would pin class files to one JDK build.
```

Failures: how, not what. The class comment must not justify the JDK. Put
that next to `#perform` only if a caller must know the leak, and then state
the leak: abandoned threads, fenced messages.

## Collapsed types, omitted default

```java
/// Runs the effects. Returns when they finish or when the wait bound runs out.
/// Failures become an error message.
```

Failures: “wait bound” is a second name for `#patience`. Null patience does
not run out. `error` and `error.timeout` are two types. Empty input has no
sentence. The return list is not said to freeze.

## Two topics in one paragraph

```java
/// If the wait bound runs out, remaining threads are interrupted and leaked,
/// counted in `#abandoned`, and anything they emit later is dropped.
```

Failures: timeout path, leak, and fence in one sentence. Passive “are
counted.” Split. Name the actor: the step counts them.
