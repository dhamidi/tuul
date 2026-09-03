/// Proposed actor hierarchy for a team's LLM agent runs: one deployment per
/// workspace, every run browsable, shared, and streamed live to every surface.
///
/// This package is design-only. It has no implementation or public types. Read
/// [the README](README.md) for the hierarchy and the lifetime and size table,
/// [the tutorial](tutorial.md), [the how-to](howto.md), [the
/// reference](reference.md), and [the explanation](explanation.md).
///
/// A singleton workspace actor owns members and roles. A catalogue actor per
/// month owns which runs exist, their visibility, and whether they are hot,
/// cold, or deleted. A run actor owns one conversation. Its journal is the
/// transcript, and the model's answers and tool results are the commands it
/// records.
///
/// A durable actor is one whose log is the only record: workspace, member,
/// project, budget, catalogue, run, and archive. An ephemeral actor is one
/// whose state has another home or may be lost on restart: search, activity,
/// live, and tool. No actor is both.
///
/// Every surface reads one event stream. A live actor per run fans out
/// entries, tokens, tool output, presence, and typing. An activity actor fans
/// out what every member is doing now. Tools run in the deployment or on the
/// surface that opened the run.
///
/// Nothing is deleted on its own. A run leaves the hot store through an
/// explicit archive and leaves the system through an explicit delete. Both are
/// recorded in the catalogue.
package agents;
