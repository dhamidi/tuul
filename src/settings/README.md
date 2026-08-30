# settings

Durable, schema-validated application settings held by one actor. This
directory has no implementation yet. These documents are the design.

Each component contributes one top-level namespace and its JSON Schema. The
package composes those contributions into the definition of
`settings/application`. Ordinary actor messages change the settings document.
The actor log is the settings history.

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
