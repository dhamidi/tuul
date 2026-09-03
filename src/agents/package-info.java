/// Proposed actor hierarchy for LLM agents whose runs are browsed and shared
/// inside a workspace.
///
/// This package is design-only. It has no implementation or public types. Read
/// [the README](README.md) for the hierarchy and the lifetime and size table,
/// [the tutorial](tutorial.md), [the how-to](howto.md), [the
/// reference](reference.md), and [the explanation](explanation.md).
///
/// A workspace actor owns members and roles. A catalogue actor per year owns
/// which runs exist, their visibility, and whether they are hot, cold, or
/// deleted. A run actor owns one transcript. Its journal is the transcript,
/// and the model's answers and tool results are the commands it records.
///
/// A durable actor is one whose log is the only record: workspace, member,
/// agent, budget, catalogue, run, and archive. An ephemeral actor is one whose
/// state has another home or may be lost on restart: search, stream, tool,
/// and presence. No actor is both.
///
/// Nothing is deleted on its own. A run leaves the hot store through an
/// explicit archive and leaves the system through an explicit delete. Both are
/// recorded in the catalogue.
package agents;
