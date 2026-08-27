package web;

import java.util.List;

/// A handler wrapping a handler.
///
/// Everything a request needs on the way in and the way out that is not the
/// answer itself: authentication, logging, rewriting a method, stripping a
/// prefix. A middleware sees only the interfaces, so it cannot depend on which
/// server is underneath, and a stack of them is testable without one.
@FunctionalInterface
public interface Middleware {

    Handler wrap(Handler next);

    /// This one, then that one, then the handler. The order a request travels.
    default Middleware then(Middleware inner) {
        return next -> wrap(inner.wrap(next));
    }

    /// A stack, outermost first. An empty list is the middleware that does
    /// nothing, which is what makes a configurable stack easy to write.
    static Middleware of(List<Middleware> stack) {
        return stack.stream().reduce(Middleware::then).orElse(next -> next);
    }
}
