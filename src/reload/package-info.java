/// Reloads application definitions as validated generations.
///
/// A revision source submits immutable revisions to one coordinator. The
/// host compiles a candidate module closure, and the coordinator defines,
/// validates, and activates each surface at its safe work boundary.
/// A rejected candidate leaves the active generation unchanged.
///
/// [Generation] contains typed capabilities, application definitions, actor
/// definitions, effect handlers, and closeable resources that must change
/// together. Work already in progress finishes at its unit-of-work boundary.
///
/// A directory watcher or artifact receiver implements [RevisionSource]. These
/// sources submit revisions. They do not activate code.
///
/// A Tuul-aware candidate provides [Program] through its named module. An
/// external HTTP candidate provides `com.sun.net.httpserver.HttpHandler`; the
/// `web.reload` package adapts that JDK service without a Tuul import.
///
/// [JdkServices] loads supported adapter-oriented JDK services from the
/// candidate root module and stores their providers in the generation.
/// [JdkToolCatalog] lists and runs `java.util.spi.ToolProvider` values while
/// holding one [Lease]. These adapters do not use a global JDK registry.
///
/// A tool candidate declares its provider in the compiled root descriptor:
///
/// ```java
/// module example.tools {
///     provides java.util.spi.ToolProvider with example.tools.EchoTool;
/// }
/// ```
///
/// The host module `tuul` owns the matching `uses` declaration. The compiler
/// reads the compiled descriptor, and [CandidateContext] loads its providers.
/// Provider instances and products stay inside the [Lease] that invokes them.
///
/// The supported services are `java.util.spi.ToolProvider`,
/// `java.nio.file.spi.FileSystemProvider`, `javax.script.ScriptEngineFactory`,
/// the public JAXP factories, JMX connector providers,
/// `javax.annotation.processing.Processor`, and `com.sun.source.util.Plugin`.
/// JVM-global or one-shot SPIs are rejected. These include selector and
/// asynchronous channel providers, URL and content-handler factories,
/// charset and time-zone providers, logger finders, JDBC drivers, and
/// security providers.
///
/// A plugin candidate declares `provides com.sun.source.util.Plugin with ...`.
/// The host passes the loaded plugin to a later host-owned `JavacTask` inside
/// one [Lease]. Loading a processor or plugin does not change the compilation
/// that creates its generation.
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
