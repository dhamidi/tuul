/// Defines one durable, schema-validated settings actor per actor system.
///
/// [Settings] composes installed [Contribution] values into one actor
/// definition and one settings document per [actors.ActorSystem].
/// [Initializers] supplies source handlers for missing values.
/// [Secret] keeps secret bytes outside the actor log.
///
/// Read [the tutorial](tutorial.md) for the first settings actor. Read
/// [the how-to](howto.md) for individual tasks. Read
/// [the reference](reference.md) for the API and protocol. Read
/// [the guide](guide.md) for the design reasons.
///
/// Each installed component contributes one top-level namespace. Environment
/// variables and files can initialize missing non-secret values. They do not
/// override durable state. The settings API accepts references at secret paths.
/// Callers must not put resolved secret bytes in an actor message.
///
/// The actor has the reserved address `settings/application`. Run separate
/// actor systems when separate self-contained applications need separate
/// settings documents.
package settings;
