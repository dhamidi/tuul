# How-to

Each section is one task. Do the steps in order. One instruction per step.

Exact API and protocol rules are in [reference.md](reference.md). Design
reasons are in [guide.md](guide.md).

The package implements this design in `Configuration`, `Values`, `Settings`,
`Contribution`, `Initial`, `Initializers`, and `Secret`.

## Validate runtime settings

Use `Configuration` and `Values` when settings exist only for the current
process. This path does not start an actor and does not read environment
variables or files.

1. Compose the installed contributions.
2. Build one JSON document from explicit application values.
3. Call `Configuration.values`.
4. Pass the returned snapshot to the components that need it.

```java
var configuration = Configuration.of(images, cable);
var values = configuration.values(Json.Object.of()
        .with("browser", browserValues)
        .with("cable", cableValues));
```

The method rejects unknown namespaces, invalid namespace values, invalid
secret values, and documents larger than the settings limit. A missing
namespace is allowed so an application can resolve optional values separately.
Use `Values.find` for a pointer and check the result before building a typed
runtime option.

Use `Settings.of(configuration)` when the application needs durable history,
actor-ordered changes, or source initializers. Inspect the actor once and pass
its `values` object to `configuration.values` before passing settings to runtime
components. Do not inspect the actor from a request handler.

## Contribute settings from a component

1. Choose one top-level namespace that the component owns.
2. Write a JSON Schema for the value under that namespace.
3. Make the schema reject unknown fields when a spelling mistake must fail.
4. Create the contribution with `Contribution.named(namespace, schema)`.
5. Expose the contribution from the component as data.

```java
public final class Images {
    public static Contribution settings() {
        return Contribution.named("images", Json.parse("""
            {
              "type": "object",
              "properties": {
                "maximumBytes": {"type":"integer", "minimum":1},
                "formats": {
                  "type":"array",
                  "items":{"type":"string"}
                }
              },
              "required": ["maximumBytes"],
              "additionalProperties": false
            }
            """));
    }
}
```

Do not make the component start an actor system. Do not make the contribution
read files or environment variables. It only describes its owned namespace.

## Compose several contributions

1. Collect contributions from the components that are installed in this
   application.
2. Pass them to `Settings.of`.
3. Register the returned definition once.

```java
var settings = Settings.of(
        Images.settings(),
        Blobs.settings(),
        OAuth.settings());

system.define(settings);
```

Contribution order does not change validation or actor state. Two
contributions with the same namespace fail during `Settings.of`.

Composition is explicit. The package does not scan the class path.

## Initialize a missing setting from an environment variable

1. Add an `Initial.environment` declaration to the contribution.
2. Select the decoder for the JSON type.
3. Register an environment handler under `Settings.RESOLVE`.
4. Summon the settings actor after the handler is registered.

```java
var blobs = Contribution.named("blobs", schema)
        .initially("/provider",
                Initial.environment("BLOB_PROVIDER", Initial.string()));

var settings = Settings.of(blobs);

system.define(settings)
        .effect(Settings.RESOLVE,
                Initializers.environment(System::getenv));
system.summon(Settings.address());
```

The environment variable initializes only an eligible missing path. A present
value wins. An explicit unset also wins until a reinitialization command clears
that decision.

An absent variable changes no state. The actor tries that initializer again
after its next summon.

## Initialize a setting from a file

Use a file initializer only for a non-secret value that can safely enter the
actor log.

1. Declare the source with `Initial.file`.
2. Select `Initial.string()` or `Initial.json()`.
3. Compose the contribution into the settings definition.
4. Register the definition and a handler rooted at the intended directory.
5. Summon the settings actor.

```java
var prompts = Contribution.named("prompts", schema)
        .initially("/system",
                Initial.file(Path.of("system.txt"),
                        Initial.string()));

var settings = Settings.of(prompts);

system.define(settings)
        .effect(Settings.RESOLVE,
                Initializers.sources(
                        Initializers.environment(System::getenv),
                        Initializers.files(Path.of("configuration"))));
system.summon(Settings.address());
```

The file handler rejects a path outside its configured root. The actor never
opens the file.

`Initial.json()` parses exactly one JSON value. A parse failure goes to the
handler's injected failure sink. It emits no actor message and changes no
settings state.

## Supply a constant default

Use an initializer when a default must become visible durable state. Do not
depend on the JSON Schema `default` annotation.

```java
var images = Contribution.named("images", schema)
        .initially("/maximumBytes", Initial.value(Json.of(20971520)));
```

The actor emits a normal `initialize` message for the constant. Its
history therefore says when the default became the value.

JSON Schema `default` stays an annotation. Validation does not insert it.

When a schema requires several values, initialize their nearest common object
as one value. The actor does not commit a partial namespace that fails its
schema while it waits for the other initializers.

## Change a setting

1. Address the singleton through `Settings.address()`.
2. Use an RFC 6901 JSON Pointer.
3. Build the message through the composed `settings` definition.

```java
system.tell(Settings.address(),
        settings.set("/blobs/provider", Json.of("s3")));
```

The actor validates the complete candidate value of the affected namespace.
An invalid candidate leaves the settings document unchanged.

Use `ask` when the caller must receive `settings.changed` or
`settings.rejected`. Use a positive deadline. Do not use `ask` to read state.

## Remove an optional value

1. Build the unset message through the composed `settings` definition.
2. Inspect the result after the actor settles.

```java
system.tell(Settings.address(), settings.unset("/images/formats"));
```

The actor validates the candidate document before removal. It rejects removal
of a required value.

An accepted unset records an explicit absence. An initializer does not restore
that path on the next boot.

## Run an initializer again

Use reinitialization when a person wants the declared source to decide a path
again.

1. Make the source available.
2. Build the reinitialization message through the composed `settings`
   definition.
3. Wait for the initializer result.
4. Inspect the actor.

```java
system.tell(Settings.address(),
        settings.reinitialize("/images/maximumBytes"));
```

The command clears the explicit-absence marker and records a reinitialization
request. It keeps any current value while the initializer is in flight.

Reinitialization uses compare-and-replace semantics. The actor replaces a
present value only if the request ID is still active and no later setting
command changed that path.

## Read settings without recording a query

1. Call `ActorSystem.inspect(Settings.address())`.
2. Read the `values` field from the returned JSON object.
3. Keep the `revision` when a UI must show whether data changed.

```java
var inspected = system.inspect(Settings.address());
var values = inspected instanceof Json.Object object
        && object.get("values") instanceof Json.Object found
                ? found
                : Json.Object.of();
```

Inspection does not summon an unloaded actor and does not run initializers.
Call `summon` during application assembly. Do not make the first settings read
responsible for process initialization.

Do not send `settings.get`. The protocol has no read command.

## Serve many reads

The settings actor is for changes, initialization, inspection, and history.
It is not a request-time key-value service.

1. Inspect settings when the application starts.
2. Pass the immutable JSON value to request handlers that need it.
3. If live updates are required, project accepted changes into an ephemeral
   actor or another in-memory component.
4. Rebuild that projection from inspection after a restart.

The projection is disposable. The durable actor remains the source of truth.

## Test an initializer without process I/O

1. Inject a function instead of reading the real environment.
2. Use an actor system with `Logs.none()` when durability is not under test.
3. Register the fake before the actor is summoned.

```java
var source = new java.util.HashMap<String, String>();
source.put("BLOB_PROVIDER", "local");

var handler = Initializers.environment(source::get);

try (var system = ActorSystem.named("test")
        .storing(Logs.none())
        .define(settings, Spawn.ephemeral())
        .effect(Settings.RESOLVE, handler)) {
    system.summon(Settings.address());
}
```

Do not read the real environment, network, or file system in a fast suite.

## Store a secret reference

Raw secret bytes must not enter a settings message.

1. Mark the contribution path as a secret reference.
2. Set a `Secret.reference` value at that path.
3. Resolve the reference inside the external effect that uses the credential.
4. Do not emit the resolved secret back to an actor.

```java
var blobs = Contribution.named("blobs", schema)
        .secret("/s3/secretKey");

system.tell(Settings.address(), settings.set(
        "/blobs/s3/secretKey",
        Secret.reference("environment:AWS_SECRET_ACCESS_KEY")));
```

The durable value is a descriptor:

```json
{"$secret":"environment:AWS_SECRET_ACCESS_KEY"}
```

The S3 effect handler receives that descriptor. It reads the environment at
the moment it calls S3. When callers use the composed settings factory, the
actor log, inspection output, telemetry, and error messages do not contain the
resolved bytes.

The contribution schema must accept the reference object. Use
`Secret.schema()` at the declared property. The settings definition checks
secret descendants when a caller replaces an ancestor object.

Do not use an environment or file value initializer for a secret path. That
initializer would have to emit the secret bytes as an actor message. Use
`Initial.value(Secret.reference(descriptor))` when the reference has an initial
value.

Do not build raw settings protocol messages. The actor runtime records a
message before the settings update can inspect it. The package cannot remove a
secret from a hand-built message that already entered the actor system.

Generated application secrets need a durable secret store. That is a separate
package and is not part of the first settings implementation.

## Evolve a contribution schema

1. Read the current settings history and inspected state.
2. Make the new schema accept every durable value that must remain valid.
3. Teach the actor definition to interpret an old message shape when a message
   protocol changed.
4. Evict and summon the actor to test replay under the new definition.
5. Remove compatibility code only when no retained actor history needs it.

The actor does not store a state snapshot as the source of truth. The current
definition folds the retained messages. There is no settings database
migration.

## Remove a component

1. Remove its contribution from `Settings.of`.
2. Remove request handlers and effects that use it.
3. Evict and summon the settings actor.

The current definition no longer applies historical messages for that
namespace. The retained history is not deleted. If the contribution returns,
replay can reconstruct its settings again.

Use the actor log erasure API only when a legal or product requirement calls
for deletion of the complete settings history.

## Inspect settings history

Use the actor runtime. The settings package does not add a second history API.

```java
system.history(Settings.address(), 0, 100)
        .forEach(message -> audit(message.json()));
```

History contains accepted and rejected command attempts as delivered. It also
contains successful initializer results. It does not contain `actors.resume`
or initializer effects.
