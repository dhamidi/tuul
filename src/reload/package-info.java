/// Reloads application definitions as validated generations.
///
/// A revision source submits immutable revisions to one coordinator. The
/// host compiles a candidate, and the coordinator calls [Program#define()],
/// runs validators, and activates each surface at its safe work boundary.
/// A rejected candidate leaves the active generation unchanged.
///
/// [Generation] contains the web handler, application definitions, actor
/// definitions, effect handlers, and closeable resources that must change
/// together. Work already in progress finishes at its request or turn boundary.
///
/// A directory watcher, HTTP upload, or artifact receiver implements
/// [RevisionSource]. These sources submit revisions. They do not activate code.
///
/// Stateful surfaces declare how state crosses the boundary. Durable actors
/// replay their command logs. Actor state currently supports replay,
/// restart, and refusal. Long-lived application state supports restart,
/// versioned JSON transfer, and refusal.
///
/// Start with `reload/tutorial`. Use `reload/howto` for tasks. Read
/// `reload/reference` for the complete contract and `reload/guide` for the
/// design.
package reload;
