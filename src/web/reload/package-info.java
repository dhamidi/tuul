/// Connects HTTP handlers and revision uploads to the generation coordinator.
///
/// [ReloadHandler] serves Tuul's [web.Handler]. [JdkReloadHandler] serves an
/// external named module's `com.sun.net.httpserver.HttpHandler` service. The
/// external module does not import Tuul or `web`; [JdkGenerationFactory]
/// discovers exactly one provider in each candidate [ModuleLayer].
package web.reload;
