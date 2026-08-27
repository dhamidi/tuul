package web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import web.dispatch.Recognised;
import web.dispatch.Router;

/// A router, as a handler.
///
/// `web.dispatch` answers what a method and a path turned out to be; this is
/// what a server does with that answer. Keeping them apart is why the router
/// can be tested without a request and this can be tested without a socket.
///
/// The three outcomes the router distinguishes stay distinguished: a route with
/// no handler and a path in no route are both 404, but a path that is a route
/// and refuses the method is a 405 carrying `Allow` — which is the header that
/// tells a client the resource exists and this is not how you ask it something.
public final class Routing implements Handler {

    /// Where the name of the route that matched is left for whoever handles it.
    public static final String ROUTE = "web.route";

    /// Where the values recovered from the path are left.
    public static final String VARIABLES = "web.variables";

    private final Router routes;
    private final Map<String, Handler> handlers;
    private final Handler missing;

    private Routing(Router routes, Map<String, Handler> handlers, Handler missing) {
        this.routes = routes;
        this.handlers = Map.copyOf(handlers);
        this.missing = missing;
    }

    public static Routing of(Router routes) {
        return new Routing(routes, Map.of(), (request, response) -> Responses.empty(Status.NOT_FOUND, response));
    }

    /// What answers a route, by the name the route was given.
    public Routing on(String name, Handler handler) {
        if (routes.route(name).isEmpty()) {
            throw new IllegalArgumentException("no route named " + name + " — routes are " + names());
        }
        var next = new LinkedHashMap<>(handlers);
        next.put(name, handler);
        return new Routing(routes, next, missing);
    }

    /// What answers when nothing else does.
    public Routing otherwise(Handler handler) {
        return new Routing(routes, handlers, handler);
    }

    public Router routes() {
        return routes;
    }

    @Override
    public void handle(Request request, Response response) throws Exception {
        switch (routes.recognise(request.method(), request.path())) {
            case Recognised.Match match -> answer(match, request, response);
            case Recognised.NotAllowed refused -> {
                response.header("Allow", String.join(", ", refused.allowed()));
                Responses.empty(Status.NOT_ALLOWED, response);
            }
            case Recognised.NotFound ignored -> missing.handle(request, response);
        }
    }

    /// The name of the route that brought a request here, for a handler that
    /// serves more than one — and for [Requests], which makes it the type of
    /// the message.
    public static Optional<String> route(Request request) {
        return request.attribute(ROUTE, String.class);
    }

    /// What the path was carrying. Empty for a request that reached a handler
    /// without passing a router, which is a legitimate way to mount one.
    @SuppressWarnings("unchecked")
    public static Parameters variables(Request request) {
        var found = request.attribute(VARIABLES, Map.class);
        if (found.isEmpty()) return Parameters.NONE;
        var variables = new LinkedHashMap<String, java.util.List<String>>();
        ((Map<String, String>) found.get()).forEach((name, value) -> variables.put(name, java.util.List.of(value)));
        return new Parameters(variables);
    }

    private void answer(Recognised.Match match, Request request, Response response) throws Exception {
        var handler = handlers.get(match.route().name());
        var routed = request.with(ROUTE, match.route().name()).with(VARIABLES, match.variables());
        if (handler == null) missing.handle(routed, response);
        else handler.handle(routed, response);
    }

    private String names() {
        return routes.routes().stream().map(route -> route.name()).distinct().toList().toString();
    }
}
