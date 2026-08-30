/// Defines the design for durable, schema-validated application settings held
/// by one actor.
///
/// This package currently contains a documentation contract and no public Java
/// types. Read [the tutorial](tutorial.md) for the first settings actor. Read
/// [the how-to](howto.md) for individual tasks. Read
/// [the reference](reference.md) for the intended API and protocol. Read
/// [the guide](guide.md) for the design reasons.
///
/// Each installed component contributes one top-level namespace. Environment
/// variables and files can initialize missing non-secret values. They do not
/// override durable state. The settings API accepts references at secret paths.
/// Callers must not put resolved secret bytes in an actor message.
package settings;
