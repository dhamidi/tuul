package web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import web.dispatch.Recognised;
import web.dispatch.Route;
import web.dispatch.Routes;

/// An immutable router that defines paths, dispatches handlers, and builds
/// links from the same definitions.
///
/// Call an HTTP verb with a [RouteRef] and a [Handler] to add a route. The
/// router recognises the most specific matching path. It parses each declared
/// [Parameter] before it calls the handler. Call [Parameter#get(Request)] in
/// that handler to read the typed value.
///
/// ```
/// var id = new IDParameter("id");
/// var post = RouteRef.of("post", "/posts/{id}", id);
/// var router = Router.of()
///         .get(post, (request, response) ->
///                 Responses.text("post " + id.get(request), response));
///
/// router.path(post.with(id, 7L));
/// ```
///
/// A GET route also answers HEAD. A path that exists for another method
/// returns 405 with `Allow`. An unmatched path returns 404. Middleware wraps
/// all three outcomes.
public final class Router implements Handler {

    /// The matched [RouteRef] on a request.
    public static final String ROUTE = "web.route";

    /// The raw path values on a request.
    public static final String VARIABLES = "web.variables";

    /// The parsed path values on a request.
    public static final String PARAMETERS = "web.parameters";

    private record Key(String name, String method) {

        private Key {
            method = method.toUpperCase(Locale.ROOT);
        }

        static Key of(Route route) {
            return new Key(route.name(), route.method());
        }
    }

    private final Routes definitions;
    private final Map<Key, Handler> handlers;
    private final Handler missing;
    private final Middleware wrapping;
    private final Handler answering;

    private Router(Routes definitions, Map<Key, Handler> handlers, Handler missing, Middleware wrapping) {
        this.definitions = definitions;
        this.handlers = Map.copyOf(handlers);
        this.missing = missing;
        this.wrapping = wrapping;
        this.answering = wrapping.wrap(this::dispatch);
    }

    public static Router of() {
        return new Router(Routes.of(), Map.of(),
                (request, response) -> Responses.empty(Status.NOT_FOUND, response), Middleware.of(List.of()));
    }

    /// Defines a route and binds its handler in one call.
    public Router route(RouteRef reference, String method, Handler handler) {
        var routes = definitions.route(reference, method);
        var next = new LinkedHashMap<>(handlers);
        next.put(new Key(reference.name(), method), handler);
        return new Router(routes, next, missing, wrapping);
    }

    /// Defines a route without a handler. [#on(RouteRef, Handler)] can bind it
    /// later. Prefer the three-argument overload when the handler is available.
    public Router route(RouteRef reference, String method) {
        return new Router(definitions.route(reference, method), handlers, missing, wrapping);
    }

    public Router get(RouteRef reference, Handler handler) {
        return route(reference, "GET", handler);
    }

    public Router get(RouteRef reference) {
        return route(reference, "GET");
    }

    public Router post(RouteRef reference, Handler handler) {
        return route(reference, "POST", handler);
    }

    public Router post(RouteRef reference) {
        return route(reference, "POST");
    }

    public Router put(RouteRef reference, Handler handler) {
        return route(reference, "PUT", handler);
    }

    public Router put(RouteRef reference) {
        return route(reference, "PUT");
    }

    public Router patch(RouteRef reference, Handler handler) {
        return route(reference, "PATCH", handler);
    }

    public Router patch(RouteRef reference) {
        return route(reference, "PATCH");
    }

    public Router delete(RouteRef reference, Handler handler) {
        return route(reference, "DELETE", handler);
    }

    public Router delete(RouteRef reference) {
        return route(reference, "DELETE");
    }

    /// Binds one handler to every method registered for a reference.
    public Router on(RouteRef reference, Handler handler) {
        if (definitions.route(reference).isEmpty()) {
            throw new IllegalArgumentException("no route named " + reference.name() + " — routes are " + names());
        }
        var next = new LinkedHashMap<>(handlers);
        definitions.routes().stream()
                .filter(route -> route.name().equals(reference.name()))
                .forEach(route -> next.put(Key.of(route), handler));
        return new Router(definitions, next, missing, wrapping);
    }

    /// Uses this handler when no route matches or a matched route has no
    /// handler.
    public Router otherwise(Handler handler) {
        return new Router(definitions, handlers, handler, wrapping);
    }

    /// Adds one middleware outside the existing stack. The first middleware
    /// added sees a request first.
    public Router wrappedBy(Middleware middleware) {
        return new Router(definitions, handlers, missing, wrapping.then(middleware));
    }

    /// Mounts another router under a path prefix. Its route references keep
    /// their names. [#path(RouteRef)] returns the mounted paths afterwards.
    public Router mount(String prefix, Router mounted) {
        var routes = definitions.mount(prefix, mounted.definitions);
        var next = new LinkedHashMap<>(handlers);
        for (var handler : mounted.handlers.entrySet()) {
            if (next.putIfAbsent(handler.getKey(), handler.getValue()) != null) {
                throw new IllegalArgumentException("mounting at " + prefix + ": route "
                        + handler.getKey().name() + " already has a handler for " + handler.getKey().method());
            }
        }
        return new Router(routes, next, missing, wrapping);
    }

    /// Builds the path for a route reference. Bind every path parameter with
    /// [RouteRef#with] first.
    public String path(RouteRef reference) {
        return definitions.path(reference);
    }

    /// Recognises without running a handler. Invalid typed path parameters do
    /// not match.
    public Recognised recognise(String method, String path) {
        return definitions.recognise(method, path);
    }

    /// The definitions in recognition order.
    public List<Route> routes() {
        return definitions.routes();
    }

    public Optional<Route> route(RouteRef reference) {
        return definitions.route(reference);
    }

    @Override
    public void handle(Request request, Response response) throws Exception {
        answering.handle(request, response);
    }

    /// The reference that matched this request.
    public static Optional<RouteRef> route(Request request) {
        return request.attribute(ROUTE, RouteRef.class);
    }

    /// The raw path parameters. Empty means the request did not pass through a
    /// router or the matched route has no parameters.
    @SuppressWarnings("unchecked")
    public static Parameters params(Request request) {
        var found = request.attribute(VARIABLES, Map.class);
        if (found.isEmpty()) return Parameters.NONE;
        var variables = new LinkedHashMap<String, java.util.List<String>>();
        ((Map<String, String>) found.get()).forEach((name, value) -> variables.put(name, java.util.List.of(value)));
        return new Parameters(variables);
    }

    @SuppressWarnings("unchecked")
    static <T> Optional<T> parameter(Request request, Parameter<T> parameter) {
        var found = request.attribute(PARAMETERS, Map.class);
        if (found.isEmpty()) return Optional.empty();
        var value = ((Map<String, Object>) found.get()).get(parameter.name());
        return value == null ? Optional.empty() : Optional.of((T) value);
    }

    private void dispatch(Request request, Response response) throws Exception {
        switch (definitions.recognise(request.method(), request.path())) {
            case Recognised.Match match -> answer(match, request, response);
            case Recognised.NotAllowed refused -> {
                response.header("Allow", String.join(", ", refused.allowed()));
                Responses.empty(Status.NOT_ALLOWED, response);
            }
            case Recognised.NotFound ignored -> missing.handle(request, response);
        }
    }

    private void answer(Recognised.Match match, Request request, Response response) throws Exception {
        var handler = handlers.get(Key.of(match.route()));
        var routed = request
                .with(ROUTE, match.route().reference())
                .with(VARIABLES, match.variables())
                .with(PARAMETERS, match.parameters());
        if (handler == null) missing.handle(routed, response);
        else handler.handle(routed, response);
    }

    private String names() {
        return definitions.routes().stream().map(Route::name).distinct().toList().toString();
    }
}
