package web;

import java.util.LinkedHashMap;
import java.util.List;
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
///
/// A stack of middleware travels with it, and runs around all of that. It is
/// held here rather than wrapped around the outside by an application because
/// an application that has to remember a step is an application that will
/// forget it: [Features#routing()] hands back a routing that already carries
/// what the features asked for, and [#on(String, Handler)] and
/// [#otherwise(Handler)] carry it forward. The stack runs for a request that
/// matches nothing too — a session that is only read for paths that exist is
/// not a session, and a check that a 404 skips is not a check.
public final class Routing implements Handler {

    /// Where the name of the route that matched is left for whoever handles it.
    public static final String ROUTE = "web.route";

    /// Where the values recovered from the path are left.
    public static final String VARIABLES = "web.variables";

    private final Router routes;
    private final Map<String, Handler> handlers;
    private final Handler missing;
    private final Middleware wrapping;

    /// The stack, already wrapped around the dispatch. Everything here is
    /// immutable, so this is worked out once rather than per request.
    private final Handler answering;

    private Routing(Router routes, Map<String, Handler> handlers, Handler missing, Middleware wrapping) {
        this.routes = routes;
        this.handlers = Map.copyOf(handlers);
        this.missing = missing;
        this.wrapping = wrapping;
        this.answering = wrapping.wrap(this::dispatch);
    }

    public static Routing of(Router routes) {
        return new Routing(routes, Map.of(), (request, response) -> Responses.empty(Status.NOT_FOUND, response),
                Middleware.of(List.of()));
    }

    /// What answers a route, by the name the route was given.
    public Routing on(String name, Handler handler) {
        if (routes.route(name).isEmpty()) {
            throw new IllegalArgumentException("no route named " + name + " — routes are " + names());
        }
        var next = new LinkedHashMap<>(handlers);
        next.put(name, handler);
        return new Routing(routes, next, missing, wrapping);
    }

    /// What answers when nothing else does.
    public Routing otherwise(Handler handler) {
        return new Routing(routes, handlers, handler, wrapping);
    }

    /// The same routing, with one more wrapper outside everything it does.
    ///
    /// Added after what is already there, so the first middleware named is the
    /// outermost and sees a request first — the same order
    /// [Middleware#of(List)] reduces in, and the same order features are named
    /// in everywhere else.
    public Routing wrappedBy(Middleware middleware) {
        return new Routing(routes, handlers, missing, wrapping.then(middleware));
    }

    public Router routes() {
        return routes;
    }

    @Override
    public void handle(Request request, Response response) throws Exception {
        answering.handle(request, response);
    }

    private void dispatch(Request request, Response response) throws Exception {
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
