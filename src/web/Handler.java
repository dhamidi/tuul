package web;

/// Something that answers a request.
///
/// The whole contract of the framework: take a request, write a response. A
/// handler is given the interfaces rather than a server, so the same handler
/// runs on `jdk.httpserver`, in a test with no socket, and on whatever comes
/// next, unchanged.
///
/// It may throw. A handler that cannot do its job should say so in the way Java
/// says things, and the server binding turns it into a 500 — one place, rather
/// than a try-catch in every handler ever written.
@FunctionalInterface
public interface Handler {

    void handle(Request request, Response response) throws Exception;

    /// This handler, wrapped. Reads in the order the request travels:
    /// `handler.wrappedBy(authentication).wrappedBy(logging)` is logged first
    /// and authenticated second.
    default Handler wrappedBy(Middleware middleware) {
        return middleware.wrap(this);
    }
}
