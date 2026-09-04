/// Connects HTTP contributions and revision uploads to the generation coordinator.
///
/// [JdkReloadHandler] is the stable JDK ingress. It serves either a raw
/// `com.sun.net.httpserver.HttpHandler` or Tuul's [web.Handler] from the active
/// generation. [JdkGenerationFactory] reads the compiled root descriptor and
/// accepts exactly one of those contribution kinds.
package web.reload;
