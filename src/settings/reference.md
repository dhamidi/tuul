# Reference

This document specifies the first `settings` package. It is an implementation
contract. The package implements it in the public types in this directory.

For a first program, read [tutorial.md](tutorial.md). For tasks, read
[howto.md](howto.md). For design reasons, read [guide.md](guide.md).

## Scope

The package defines runtime value validation, one durable settings actor,
contribution composition, initializer declarations, standard initializer
handlers, and secret references.

The package does not define an engine lifecycle, a dependency-injection
container, a database, a remote settings service, a secret store, or a
classpath scanner.

## Terms

| Term | Meaning |
|---|---|
| setting | One value at a JSON Pointer in the settings document. |
| settings document | The JSON object owned by `settings/application`. |
| contribution | One component's namespace, schema, initializer declarations, and secret-reference declarations. |
| namespace | One top-level property owned by one contribution. |
| initializer | A declared source for a setting that has no durable decision. |
| initialization | A recorded state transition from no decision to a value. |
| explicit absence | The durable result of an accepted unset. |
| secret reference | A durable descriptor that an external effect can resolve to secret bytes. |
| revision | The count of accepted state changes. |

Environment initialization is not an override. In this package, an override
is an explicit `set` command.

## Actor identity

| Item | Value |
|---|---|
| actor type | `settings` |
| actor id | `application` |
| local address | `settings/application` |
| default spawn | durable |
| public address factory | `Settings.address()` |

An application has one settings actor. `Settings.of(...)` supplies the
definition for that actor; it does not create a second address. The actor
system keeps one definition per actor type, so this package does not provide
multiple independent settings documents in one actor system. A multi-tenant
deployment that runs one self-contained application stack for each tenant has
one settings actor in each stack.

`Settings` implements `Definition<Settings.State>`. The state type does not
leave the actor system. `Definition.inspect` returns JSON.

## Public types

| Type | Purpose |
|---|---|
| `Configuration` | Composes contributions and validates runtime documents. |
| `Values` | Holds one immutable validated runtime document. |
| `Settings` | Uses a configuration, defines the actor, and builds protocol messages. |
| `Settings.State` | Holds the current document, explicit absences, reinitialization request IDs, and revision. |
| `Contribution` | Describes one owned namespace. |
| `Initial` | Describes one initializer source and its decoder. |
| `Initializers` | Builds external handlers for supported source schemes. |
| `Initializers.Source` | Names one source scheme and implements `Effect.Handler`. |
| `Initializers.Failure` | Describes a source or decoding failure without its source value. |
| `Secret` | Builds and recognizes durable secret references. |

The first implementation keeps the small supporting types nested when that
makes the public surface smaller. The semantic names in this document remain
the same.

## `Configuration`

### `Configuration.of(Contribution...)`

Returns one immutable composition of the supplied contributions. It performs
the same schema, namespace, initializer, and secret declaration checks as
`Settings.of`. It does not create an actor or read an initializer source.

Passing no contributions creates a configuration with no namespaces.

### `values(Json.Object document)`

Validates `document` and returns one immutable `Values` snapshot. The document
must contain only installed namespaces. Each present namespace must satisfy its
schema. A present secret path must contain a secret reference. A missing
namespace is allowed. The complete document must not exceed 8 MiB.

The method does not require every namespace to be present. An application can
use this behavior while it resolves optional runtime values. The method throws
`IllegalArgumentException` for an unknown namespace, an invalid namespace
value, an invalid secret value, or an oversized document.

## `Values`

### `document()`

Returns the validated JSON document. It does not change after the snapshot is
created.

### `find(String pointer)`

Returns the value at an absolute JSON Pointer. An absent value returns an empty
`Optional`. The pointer must name an installed namespace. The method does not
read an actor or a source.

## `Settings`

### `Settings.of(Contribution...)`

Returns one immutable actor definition.

The method preserves no behavior from argument order. Inspection field order
is namespace order by name.

The method compiles each schema with `jsonschema.Store.of()`. It fails before
actor registration when:

- a namespace is empty;
- a namespace is not one JSON object property name;
- two contributions own the same namespace;
- a contribution schema document is not a JSON object;
- a contribution schema does not declare `"type":"object"` at its root;
- a schema does not compile;
- an initializer pointer is outside its contribution;
- two initializers target the same full pointer;
- a secret declaration points outside its contribution.

A JSON property name may contain `/` or `~`. JSON Pointer escaping still
applies. Namespaces live inside the inspected `values` object, so an envelope
field name such as `revision` does not conflict with a namespace.

`Settings.of()` with no contributions is valid. The resulting actor accepts no
mutation namespace.

### `Settings.of(Configuration)`

Builds the durable actor from an existing [Configuration]. The actor and the
runtime snapshots created by that configuration share its compiled schemas and
declarations. The method does not register or summon the actor.

### `configuration()`

Returns the immutable [Configuration] used by this actor. The configuration can
create runtime snapshots with [Configuration#values].

### `values(Json.Object document)`

Delegates to the actor's [Configuration] and returns a validated runtime
snapshot. It does not read the actor state, read an initializer source, or
persist the document.

### `Settings.address()`

Returns `Address.of("settings", "application")`.

### `set(String pointer, Json value)`

Returns a `set` message from the composed `Settings` instance. The
pointer is an absolute RFC 6901 JSON Pointer. The empty pointer is invalid. The
first pointer token must name an installed contribution.

The actor creates missing intermediate JSON objects. It does not create or
insert array elements. A caller replaces an array as one value.

The actor validates the complete candidate value of the affected contribution.
A valid candidate replaces the prior document atomically. An invalid candidate
does not change state.

Before it builds a message, the method checks each secret declaration that
overlaps the write. An exact secret-path value must be a `Secret` reference. An
ancestor replacement must contain a reference at each declared secret
descendant that it supplies. A write below a secret path is invalid.

An accepted set clears explicit absence and reinitialization requests that
overlap the path. Two pointers overlap when either pointer is the other
pointer or its ancestor. The change increments revision once.

### `unset(String pointer)`

Returns an `unset` message.

The factory records the full pointers of the initializer declarations that
overlap the requested pointer. Replay uses that recorded list. A later
initializer declaration does not change the meaning of an earlier unset.

The actor removes the value if it exists. It validates the candidate
contribution. A required property cannot be removed.

An accepted unset records explicit absence for every declared initializer that
overlaps the path. It clears overlapping reinitialization requests. This
prevents a later summon from restoring the value. It increments revision once,
including when the value was already absent but the command created a new
explicit-absence decision.

### `reinitialize(String pointer)`

Returns a `reinitialize` message.

The pointer must name one declared initializer or an ancestor of one or more
declared initializers. The actor clears explicit absence for the selected
initializers, records a new random request ID for each selected initializer,
and emits resolution effects. The factory puts those request IDs in the
command, so replay uses the same IDs.

For a missing optional value, the next valid result inserts the value. For a
present or required value, the actor keeps the old value while resolution is
in flight. It replaces that value only if no later command changed the path.

The command increments revision when it changes a durable decision. A second
reinitialization gets a different request ID even when the prior value is
unchanged. Each accepted initializer result increments revision and clears its
request.

### Constants

| Constant | Value | Use |
|---|---|---|
| `Settings.TYPE` | `settings` | Actor type. |
| `Settings.APPLICATION` | `application` | Singleton actor id. |
| `Settings.RESOLVE` | `settings.resolve` | External initializer effect type. |

Call sites use factories for message types. Protocol tests can use the literal
values in the message table.

## `Contribution`

### `Contribution.named(String namespace, Json schema)`

Creates an immutable contribution with no initializers and no secret paths.
The schema validates the value under the namespace. The package does not wrap
it in another object before validation.

A namespace value is a JSON object. Values inside that object can have any JSON
type that the contribution schema accepts.

The package uses the built-in draft 2020-12 store. A schema that declares an
unregistered dialect fails during composition. Schema resolution never uses
the network.

The schema can require properties inside its namespace. The namespace itself
is optional until one message or initializer creates it.

### `initially(String pointer, Initial initial)`

Returns a contribution with one more initializer. The pointer is relative to
the contribution root. It is empty or starts with `/`. The empty pointer
initializes the complete namespace value.

An initializer does not change the schema. Its decoded result must still pass
the contributed schema.

### `secret(String pointer)`

Returns a contribution with one more secret-reference path. A set or
initializer result at that path must be a value recognized by
`Secret.isReference`.

The contribution schema must accept `Secret.schema()` at this path. The secret
declaration does not replace schema validation.

This declaration does not resolve or encrypt a secret. It lets the composed
message factory prevent ordinary JSON from entering the declared path. An
environment or file value initializer cannot target a secret path because its
result would enter the actor log before actor validation.

## `Initial`

An `Initial` is immutable data in the actor definition. It has a source scheme,
a source name, and a decoder name. It does not hold an open resource.

### Source factories

| Factory | Source descriptor | Result |
|---|---|---|
| `Initial.environment(name, decoder)` | `environment:<name>` | Reads one environment variable through the registered handler. |
| `Initial.file(path, decoder)` | `file:<path>` | Reads one permitted file through the registered handler. |
| `Initial.value(value)` | internal constant | Emits the supplied JSON value without external I/O. |

An environment name must not be empty. A file path must not be empty. Only
`Initial.value(Secret.reference(descriptor))` can target a secret path.

### Decoders

| Factory | Accepted source text | JSON result |
|---|---|---|
| `Initial.string()` | any text | string |
| `Initial.number()` | one finite JSON number | number |
| `Initial.booleanValue()` | `true` or `false` | boolean |
| `Initial.json()` | exactly one JSON value | parsed value |

Decoding does not use the schema to guess a type. Leading or trailing
whitespace follows the selected decoder. `string()` preserves it.

## `Initializers`

### `Initializers.Source`

`Initializers.Source` is an `Effect.Handler` with one additional accessor:

```java
String scheme()
```

The scheme is non-empty and does not contain a colon. `run` accepts a
`settings.resolve` effect for that scheme and emits at most one
`initialize` message. It emits nothing when the source is missing or
invalid. The source invokes its failure sink for an invalid value or an I/O
failure.

`Initializers.source(String scheme, Effect.Handler handler)` wraps a custom
source. The handler must follow the same emission and secret rules. The
standard environment and file factories return this type.

### `Initializers.Failure`

`Initializers.Failure` is this record:

```java
record Failure(
        String pointer,
        String scheme,
        String name,
        String decoder,
        String reason) {}
```

`pointer` is the full settings pointer. `scheme` and `name` identify the
descriptor that failed. `decoder` is the decoder name from the effect.
`reason` is one of `unreadable`, `too-large`, `invalid-value`, or
`unknown-scheme` for a standard source.

A standard source does not put source contents, decoded values, exception
messages, or resolved secret bytes in a failure. A custom source must provide
the same guarantee.

### `Initializers.environment(Function<String,String> read)`

Returns an `Initializers.Source` for `settings.resolve` effects whose source
scheme is `environment`. `Initializers.Source` implements `Effect.Handler`, so
a single source can be registered directly on an actor system.

The function returns null for a missing variable. A missing value emits no
message. It does not change settings state and does not suppress a future
attempt.

A decoder failure goes to the injected `Consumer<Initializers.Failure>`. It
emits no actor message. The failure does not include the source text.

The two-argument overload accepts that failure sink. The one-argument overload
uses a sink that does nothing.

### `Initializers.files(Path root)`

Returns an `Initializers.Source` for sources whose scheme is `file`.

The handler normalizes the declared path under `root`. It rejects a lexical
path that escapes `root`. It follows symbolic links because initializer paths
come from installed contribution code, not request input. The root organizes
files; it is not a security sandbox.

A missing file emits no message. An unreadable file invokes the injected
failure sink without file contents.

The handler rejects a source value larger than 1 MiB before it emits that value.

The two-argument overload accepts the failure sink. The one-argument overload
uses a sink that does nothing.

### `Initializers.sources(Initializers.Source...)`

Returns one handler that delegates by source scheme. Duplicate schemes fail at
construction. An unknown scheme invokes the injected failure sink.

The overload with a leading `Consumer<Initializers.Failure>` uses that sink for
dispatcher failures. The overload with only sources uses a sink that does
nothing.

Handlers receive no actor state. They emit ordinary messages only. They can
run more than once after a crash. Source reads must therefore have no required
one-time side effect.

The overloads that accept a failure sink are for production reporting. File
handler success and path behavior belong in an integration test. Fast tests
use the environment handler with an injected function and test decoders without
file-system I/O.

## `Secret`

### `Secret.reference(String descriptor)`

Returns this JSON shape:

```json
{"$secret":"environment:AWS_SECRET_ACCESS_KEY"}
```

The descriptor must contain a non-empty scheme, a colon, and a non-empty name.
The method does not check whether the source exists.

### `Secret.isReference(Json value)`

Returns true only for an object with one `$secret` string field and no other
fields.

### `Secret.schema()`

Returns the draft 2020-12 schema fragment for the exact reference shape. A
contribution uses this fragment at each path passed to `Contribution.secret`.

The package can show the descriptor during inspection. It never resolves the
descriptor. A consumer resolves it inside the external effect that needs the
secret and must not emit the bytes as a message.

## Inspected state

`ActorSystem.inspect(Settings.address())` returns:

```json
{
  "revision": 3,
  "values": {
    "images": {"maximumBytes": 20971520},
    "blobs": {"provider": "s3"}
  }
}
```

The result omits explicit-absence markers, reinitialization request IDs, and
initializer source details. Those are implementation state.

Secret references stay references. Resolved secret bytes cannot be present in
state and therefore cannot be present in inspection.

Inspection of an unloaded actor replays its log without effects. It does not
run initializers. `ActorSystem.summon` is the startup operation that loads the
actor and permits `actors.resume` effects.

## Message protocol

Every payload field in this section is under `body`. The message `type` is in
the envelope, as specified by `application.Message`.

The protocol shapes specify replay and tests. They are not a supported message
construction API. Application code must use the composed `Settings` instance.
The runtime logs an inbound message before the actor validates it, so the
package cannot protect a caller that puts secret bytes in a hand-built
`set` or `initialize` message.

### Commands

| Type | Payload | State behavior | Reply |
|---|---|---|---|
| `set` | `pointer`, `value` | Validate and replace one value. | `settings.changed` or `settings.rejected`. |
| `unset` | `pointer`, `initializers` | Validate and remove one value. | `settings.changed` or `settings.rejected`. |
| `reinitialize` | `pointer`, `requests` | Select declared initializers and emit effects. | `settings.initializing` or `settings.rejected`. |

### Initializer results

| Type | Payload | State behavior |
|---|---|---|
| `initialize` | `pointer`, `condition`, `value` | Validate and commit only when the durable condition still holds. |

The public initializer handler builds these result messages. Application code
does not build them.

A missing source emits no result. A source or decoder failure invokes the
handler's injected failure sink. Neither case writes an actor-log entry.

`initializers` is an array of full pointer strings. `requests` is an object
whose keys are full initializer pointers and whose values are random request ID
strings. The composed factory fixes both values before delivery.

### Initializer conditions

The `condition` payload has one of these shapes:

```json
{"kind":"missing"}
```

```json
{"kind":"requested", "request":"6c1c...", "previous":20971520}
```

The `request` field is the ID recorded by `reinitialize`. The
`previous` field is absent when the requested path was absent. It is present
with the prior JSON value otherwise. JSON null is a present value.

The resolver copies the condition from the effect to its result without
changing it. A `missing` result is current only while the path is missing and
has no explicit-absence decision. A `requested` result is current only while
the same reinitialization request ID exists and the prior value is unchanged.

### Replies

| Type | Payload |
|---|---|
| `settings.changed` | `pointer`, `revision` |
| `settings.initializing` | `pointer` |
| `settings.rejected` | `pointer`, `reason`, `errors` when validation failed |

The actor emits a reply only when the current delivery has a reply address.
An `errors` value is the JSON Schema basic output represented as JSON data. It
does not contain source input for a failed initializer.

The protocol has no `settings.get` message.

## Initialization sequence

On `actors.resume`, the actor examines initializer declarations in full
pointer order.

For each declaration:

1. A recorded reinitialization request is eligible.
2. A present value without a request is not eligible.
3. An explicitly absent value without a request is not eligible.
4. Every other path is eligible.

An eligible external source gets one `settings.resolve` effect. The effect
carries the full pointer, source descriptor, decoder name, and a condition
derived from durable state. The effect does not carry another message
envelope.

An eligible `Initial.value` produces the same `initialize` message
through `application.send`. It needs no external handler.

The handler emits one result message. The runtime sends that message back to
the actor. A durable runtime appends it before the update sees it.

For an ordinary missing value, the condition requires that the value is still
missing and is not explicitly absent. For a requested reinitialization, the
condition requires that the recorded request ID is still active and that any
prior value is unchanged.

The actor ignores a result whose condition no longer holds. This prevents a
slow initializer from overwriting a later set, unset, or reinitialization. The
condition is derived entirely from recorded messages. A recorded initializer
result therefore makes the same decision during replay.

Replay suppresses effects. It applies recorded initializer result messages.
After replay, `actors.resume` can retry an eligible path whose source was
missing or failed.

The settings definition uses ordinary `Spawn.durable()` behavior. It does not
enable effect redelivery. A reinitialization request stays in replay-derived
state until a valid result clears it. `actors.resume` therefore emits its
effect again after the actor is summoned following a crash.

## Validation

Each namespace has one compiled `jsonschema.Schema`.

A set, unset, or initializer result performs these operations in order:

1. Check the pointer and contribution ownership.
2. Check secret-reference rules.
3. Apply the proposed change to an immutable candidate.
4. Validate the affected namespace value.
5. Commit the candidate and increment revision when validation succeeds.
6. Reject the message and keep state when validation fails.

The package does not apply JSON Schema annotations as mutations. In
particular, `default` does not insert a value.

If a namespace is absent, the package does not validate its subschema. This is
how an optional component can start before initialization. Once created, the
namespace value must validate.

## JSON Pointer rules

Paths use RFC 6901 JSON Pointer syntax.

- The empty string means the complete settings document and is not accepted by
  mutation factories.
- `/images/maximumBytes` selects a nested property.
- `/a~1b` selects the property `a/b`.
- `/a~0b` selects the property `a~b`.
- An invalid escape is rejected.
- A missing intermediate object is created for `set` only.
- Array indexes are not mutation steps in the first implementation.
- An array can be replaced as one complete value.

## Ordering and concurrency

The actor mailbox defines write order. Two tells are applied in accepted
delivery order. The first implementation has no compare-and-set revision
parameter.

Durable initializer conditions order asynchronous results. A stale result
changes no state and increments no revision.

Inspection of a loaded actor follows all messages that the system accepted
before the inspection turn. Inspection of an unloaded actor reports replayed
state.

## Failure behavior

| Failure | Result |
|---|---|
| duplicate contribution | `Settings.of` throws `IllegalArgumentException` |
| schema compilation failure | `Settings.of` propagates `SchemaException` |
| malformed pointer factory argument | message factory throws `IllegalArgumentException` |
| unknown namespace command | actor rejects command |
| invalid candidate | actor rejects command and preserves state |
| absent initializer source | no result message; retry on a later summon |
| initializer decode failure | failure sink called; no result message or state change |
| initializer handler throw | actor runtime emits its normal `error` message |
| stale initializer result | actor ignores result because its durable condition is false |
| raw value passed through composed factory for a secret path | factory throws before a message exists |
| unresolved secret at point of use | consuming effect fails; settings state does not change |

An update failure does not stop the actor system. The implementation must use
explicit rejection branches for expected input failures. It must not throw to
report ordinary validation errors.

## Bounds

An initializer handler rejects a decoded value larger than 1 MiB. A mutation
that makes the prospective complete settings document larger than 8 MiB is
rejected before commit. Implementations apply the candidate, validate the
affected namespace, stream the complete candidate through a counting writer,
and then commit. They do not build a second byte array.

An initializer applies both bounds. Its handler enforces the 1 MiB decoded
value bound before it emits a message. The actor enforces the 8 MiB complete
document bound after schema validation and before commit.

The first implementation does not mutate through an array index. A caller can
replace an array as one value.

## Replay and compatibility

The actor log stores commands and initializer results. It does not store a
database row or serialized `Settings.State` as the source of truth.

The registered definition interprets retained messages during every replay.
A schema or protocol change must remain compatible with retained messages, or
the new definition must explicitly interpret the older shape.

Removing a contribution removes its namespace from the current fold. It does
not erase historical messages. Reinstalling a compatible contribution can
make that history active again.

An unset command records the initializer paths that it made explicitly absent.
A reinitialize command records request IDs and selected paths. Adding an
initializer later does not change those earlier command payloads.

A recorded `initialize` result is interpreted for its pointer when
the owning contribution remains installed, even if that contribution no
longer declares the initializer. Removing the owning contribution makes its
historical mutations state no-ops. A live unknown-namespace command gets a
rejection reply; replay suppresses that reply effect.

Changing a contribution schema, secret declaration, or message interpretation
can still change replay. The new definition must remain compatible with every
retained value that the application must keep.

`actors.resume` is not recorded. Initializer effects are not recorded.
Successful initializer result messages are recorded.

## Required actor-runtime addition

The actor runtime must expose:

```java
public void summon(Address address)
```

`summon` loads an actor without delivering or recording an application
message. It is idempotent for a loaded actor. It replays a durable log, sends
`actors.resume` with effects enabled, and returns after the actor accepts the
summon request. It does not wait for all emitted effects to settle.

The runtime already has this operation internally. The settings implementation
must expose and document it before the tutorial can compile.
