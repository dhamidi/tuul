# settings

Schema-validated application settings with immutable runtime values and an
optional durable actor.

`Configuration` composes component contributions and validates a JSON document.
`Values` holds the immutable runtime snapshot. `Settings` adds the durable
`settings/application` actor when the application needs history and changes.

Each component contributes one top-level namespace and its JSON Schema. The
application composes those contributions into a `Configuration` and validates
runtime values without an actor. It can also pass the same contributions to
`Settings.of` for the durable `settings/application` actor. Ordinary actor
messages change the durable document. The actor log is the settings history.

An environment variable, file, or constant value can initialize a missing
setting. It is a source for one actor effect. A successful result returns as an
ordinary message and becomes durable state. The source does not override that
state on a later boot.

Secret bytes are not settings. The settings document can hold a secret
reference. The effect that uses the secret resolves that reference outside the
actor log.

## Documents

Each document answers one kind of question.

| You want | Read |
|---|---|
| To learn by building one settings actor | [tutorial.md](tutorial.md) |
| To complete a task | [howto.md](howto.md) |
| A fact, a message shape, or an API rule | [reference.md](reference.md) |
| To know why the design is this way | [guide.md](guide.md) |

Start with the tutorial if you did not use this package before. Use the
reference when you implement the package or a component that contributes
settings.
