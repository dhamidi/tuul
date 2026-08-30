# Guide

This document explains the settings design. It is not a tutorial or an API
list. For a first program, read [tutorial.md](tutorial.md). For tasks, read
[howto.md](howto.md). For exact rules, read [reference.md](reference.md).

## Settings are actor state

Tuul has one durable primitive: the actor.

Application settings must survive a process restart. They must have an order
when two people change them. They must also show how the current value came to
exist. A durable actor already supplies those properties.

The settings package therefore defines `settings/application`. Its state is a
JSON document. Its messages are changes. Its log is history.

There is no settings database beside the actor. An ephemeral actor can manage
a database when a component needs one, but the settings package does not need
that component.

## One actor is enough

Settings change infrequently. The largest intended Tuul deployment has about
10,000 daily active users, but request traffic does not become settings write
traffic.

One actor serializes administrative changes without locks in application code.
It also makes two nodes agree through the actor ownership and transport rules
that already exist.

Request handlers do not ask the actor for every value. They inspect once and
keep immutable read-side state. An ephemeral projection can receive changes
when the application needs live updates. That projection is not durable.

## Contributions are data

A component needs to say what settings it owns. It does not need an engine
lifecycle.

A contribution contains one namespace, one schema, initializer declarations,
and secret-reference declarations. The application explicitly passes its
installed contributions to `Settings.of`.

This shape lets an image uploader contribute `images`, blob storage contribute
`blobs`, and OAuth contribute `oauth`. It does not make any of them own the
settings actor.

Explicit composition has one visible step. It also lets an application omit a
component without loading it or satisfying its settings.

Classpath discovery would hide that step. A lifecycle registry would add a
second runtime model beside actors. The design uses neither.

## Namespaces make composition finishable

Merging arbitrary JSON Schemas is not a small operation. `$id`, `$ref`,
`allOf`, unevaluated properties, and dialect changes can make two root schemas
interact in ways that a package cannot guess.

One contribution therefore owns one top-level property. Its schema validates
the value under that property. Duplicate ownership is an error.

The rule gives each component a stable place and makes validation local. It
also lets the package derive a combined schema later for a settings UI without
making that derived document the validation mechanism.

## JSON Schema validates; it does not initialize

The JSON Schema `default` keyword is an annotation. The existing
`jsonschema` package correctly leaves the instance unchanged.

A default that matters to the application must become actor history. An
explicit initializer does that. Its result arrives as a normal message, passes
validation, and increments revision.

This makes the origin visible. It also prevents a schema renderer, validator,
and actor from inventing different default behavior.

## Environment variables are sources

An environment variable can supply a useful first value during deployment.
It must not remain a second settings layer.

The actor update cannot call `System.getenv`. Replay must depend only on the
recorded messages and the current definition. Reading process state during
replay would produce a different actor from the same history.

After replay, the runtime sends `actors.resumed`. The actor finds eligible
missing paths and emits `settings.resolve`. An external handler reads the
environment. Its successful result comes back as `settings.initialized` and is
recorded.

The sequence is:

```text
first summon
    -> replay
    -> actors.resumed
    -> settings.resolve effect
    -> environment read
    -> recorded settings.initialized
    -> validated durable value

later summon
    -> replay restores the value
    -> actors.resumed finds nothing to initialize
```

Changing the environment after the first success changes nothing. A person can
send `settings.set` or `settings.reinitialize` when a change is intended.

This is not a 12-factor precedence chain. There is no permanent merge of
defaults, files, environment variables, flags, and remote values.

## Explicit absence is a decision

A missing value can mean that initialization has not happened. It can also
mean that a person deliberately removed an optional value.

The state must distinguish those cases. An accepted unset records explicit
absence for matching initializer paths. A later `actors.resumed` respects that
decision.

`settings.reinitialize` clears the decision for selected paths. This makes the
source eligible again. The command is explicit because restoring a value can
change application behavior.

An absent source does not create explicit absence and does not emit a result
message. The actor can try it again after a later summon. It does not remain
active and retry in a loop.

## Initializers are effects, not a process phase

The word initializer can suggest a global startup framework. This package does
not add one.

An initializer declaration only tells one actor which effect to emit for one
missing path. The handler belongs to the actor system because it owns access to
the environment or file system. The definition stays pure.

This uses the same update/effect boundary as every other actor. A fake handler
can test it without I/O. Replay suppresses it. A crash can repeat it safely.

The application must register handlers and summon the singleton during
assembly. `ActorSystem.summon` is one direct operation. It avoids writing a
meaningless boot command only to make `actors.resumed` run.

## Writes are messages; reads are inspection

A setting change is an event in durable history. It enters the mailbox and the
actor log.

A settings read is not an event. Sending `settings.get` would append a command
on every page load and bury the few changes in read noise.

`ActorSystem.inspect` reads the definition's JSON view without adding a user
message. It serializes with an already loaded actor. It replays an unloaded
actor without effects.

The separation also keeps the state type private. Callers receive immutable
JSON and cannot mutate actor state around the mailbox.

## Validation happens before commit

Each change produces an immutable candidate document. The actor validates the
affected namespace and commits only a valid candidate.

The old value remains visible after a malformed pointer, an unknown namespace,
a wrong JSON type, or a required-property removal. Expected validation failures
produce `settings.rejected`. They do not throw through the actor runtime.

Validation applies to initializer results too. An environment string cannot
bypass the component contract.

The first implementation replaces arrays as complete values. Array insertion
has edge cases around indexes, `-`, concurrent intent, and schema validation.
It can be added later without changing object-pointer behavior.

## The actor mailbox is the concurrency rule

The settings actor applies one delivered message at a time. The last accepted
write to one pointer wins.

An administrator UI can show the inspected revision, but the first protocol
does not require compare-and-set revisions. Settings writes are rare, and a
revision precondition would add a conflict protocol before a demonstrated
need.

Initializer work is asynchronous. Each reinitialization has a random request
ID recorded in its command. Each result carries that ID and the durable
condition that was true when the effect was emitted. The actor ignores the
result when a later set, unset, or reinitialization made that condition false.

The condition is not transient state. Replay reconstructs it from recorded
messages before it reaches the initializer result. The same result is therefore
accepted or ignored during replay as it was when live.

## Secret bytes do not belong in durable messages

An actor log is durable and inspectable. Telemetry and error reporting can see
message shapes. A raw access key in `settings.set` or `settings.initialized`
would therefore spread into places that are not secret stores.

Settings store a reference such as:

```json
{"$secret":"environment:AWS_SECRET_ACCESS_KEY"}
```

The contribution marks which paths require that shape and its schema accepts
that shape. The composed message factory checks a secret reference before it
creates a message. The external effect that calls S3 resolves the reference
immediately before use. It does not emit the result back to an actor.

The actor runtime records a message before the update validates it. A source
initializer must therefore never read raw bytes for a secret path. A caller
must also use the composed message factory rather than build a raw protocol
message. Under that supported API, secret bytes stay outside the logging
boundary. The package cannot scrub a hand-built message after the runtime has
logged it.

Rotating an environment-backed secret changes what the next external call
resolves. It does not rewrite settings history.

This design does not solve generated durable secrets. A future secret-store
package can use an actor and encrypted storage. The first settings package must
not pretend to be a key-management system.

## Replay is the evolution mechanism

The actor log holds messages. The current definition folds them into current
state. There is no settings row to migrate.

A schema change must accept each durable value that the application must
retain. A protocol change must continue to interpret retained message shapes.
The component tests replay before deployment.

If an application removes a contribution, the current definition does not
apply that namespace's historical settings. It does not delete the history.
Reinstalling a compatible contribution can reconstruct the namespace.

That behavior makes optional components possible without an orphan-settings
subsystem. Legal deletion remains an explicit actor-log operation.

## Failure stays local

A missing initializer source changes no state and emits no actor message. A
decoder failure goes to an external failure sink and changes no actor state. An
invalid candidate changes no state. The actor remains available.

Rejections identify the pointer and validation output. They do not include the
initializer source text. Secret resolution failures belong to the consuming
effect, not to settings.

The actor does not retry continuously. A later summon retries a source that
never produced a durable decision. A person uses reinitialization for an
explicitly absent or present value.

## The first implementation boundary

The first package needs:

- one `Settings` actor definition;
- namespaced `Contribution` values;
- JSON Pointer object replacement and removal;
- validation through `jsonschema`;
- constant, environment, and bounded-file initializers;
- explicit absence and reinitialization;
- secret-reference shape checks;
- actor inspection and history through existing runtime APIs;
- one public actor-runtime summon operation.

It does not need:

- engine lifecycle hooks;
- classpath scanning;
- environment precedence;
- a database;
- a remote configuration service;
- encrypted secret storage;
- array patch operations;
- a settings cache with its own durability;
- a second telemetry model.

These boundaries leave components free to contribute settings without forcing
the settings package, its standard handlers, or any component on an
application that does not use them.
