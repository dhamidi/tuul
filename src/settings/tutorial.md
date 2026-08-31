# Tutorial: initialize and change settings

This tutorial designs one settings actor for an application. An image
component contributes a schema. An environment variable supplies the first
value. A later actor message changes that value.

For exact behavior, read
[reference.md](reference.md).

You need the `actors`, `json`, `jsonschema`, and `settings` packages. You do not
need a database or a configuration file.

## Define the component settings

The image component owns the top-level name `images`. Its schema applies to
the value under that name.

```java
var schema = Json.parse("""
    {
      "type": "object",
      "properties": {
        "maximumBytes": {
          "type": "integer",
          "minimum": 1
        }
      },
      "required": ["maximumBytes"],
      "additionalProperties": false
    }
    """);
```

Create one contribution. The initializer says how to fill
`/images/maximumBytes` when that setting has no durable decision.

```java
var images = Contribution.named("images", schema)
        .initially("/maximumBytes",
                Initial.environment("IMAGES_MAXIMUM_BYTES", Initial.number()));
```

The pointer passed to `initially` is relative to the contribution. The full
pointer is `/images/maximumBytes`.

`Initial.number()` parses the source text as a JSON number. It does not coerce
arbitrary JSON after validation.

## Compose the actor definition

Compose all installed contributions before the actor system starts.

```java
var settings = Settings.of(images);
```

`Settings` is an `actors.Definition`. Its actor type is `settings`. Its
application instance has the address `settings/application`.

Composition compiles every contributed schema. A duplicate namespace or an
invalid schema fails here. It does not fail after the actor starts.

## Register the actor and initializer

Register the definition as a durable actor. Register the external effect
handler before you summon the actor.

```java
var environment = Initializers.environment(name -> switch (name) {
    case "IMAGES_MAXIMUM_BYTES" -> "20971520";
    default -> null;
});

try (var system = ActorSystem.named("image-shop")
        .rooted(Path.of("state"))
        .define(settings)
        .effect(Settings.RESOLVE, environment)) {
    system.summon(Settings.address());
}
```

The function is a test source. A deployed application uses
`System::getenv`.

`summon` loads the actor without writing a command. The runtime replays its
history and then sends `actors.resume`. The settings actor emits one
`settings.resolve` effect for each eligible missing setting.

The initializer reads the source and emits `initialize`. The runtime
records that message before the actor changes its state. Replay does not read
the environment.

## Inspect the settings

Inspect the actor after initialization settles.

```java
var document = system.inspect(Settings.address());
```

The result has this shape:

```json
{
  "revision": 1,
  "values": {
    "images": {
      "maximumBytes": 20971520
    }
  }
}
```

Inspection does not send a message. It does not append a read to the actor
log.

The actor serializes writes. The `revision` counts accepted changes. Rejected
messages and missing initializer sources do not increment it.

## Change one setting

Build the message through `Settings`. Do not write the protocol string at the
call site.

```java
system.tell(Settings.address(),
        settings.set("/images/maximumBytes", Json.of(31457280)));
```

The actor applies the change to a copy of its document. It validates the
candidate `images` value. It commits the candidate only when validation
succeeds.

After the actor settles, inspection reports revision `2` and value `31457280`.

This invalid change leaves revision `2` and the prior value unchanged:

```java
system.tell(Settings.address(),
        settings.set("/images/maximumBytes", Json.of(-1)));
```

The actor replies with `settings.rejected` when the delivery has a reply
address. A `tell` has no reply address, so it gives no direct response. The
state stays unchanged and the actor continues.

## Start the application again

Close the first actor system. Start another system with the same actor log.
Register a source that now says `1`.

```java
var changedEnvironment = Initializers.environment(name -> "1");

try (var system = ActorSystem.named("image-shop")
        .rooted(Path.of("state"))
        .define(settings)
        .effect(Settings.RESOLVE, changedEnvironment)) {
    system.summon(Settings.address());
    var document = system.inspect(Settings.address());
}
```

The inspected value is still `31457280`. Replay restored the accepted
messages. The actor does not emit an initialization effect for a value that is
present.

The environment was an initializer. It was not an overlay.

## Next

- [howto.md](howto.md) shows composition, unsetting, reinitialization, file
  sources, and secret references.
- [reference.md](reference.md) specifies the API, state, messages, effects, and
  failure rules.
- [guide.md](guide.md) explains the actor model and the package boundaries.
