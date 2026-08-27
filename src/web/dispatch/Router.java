package web.dispatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import uritemplates.Template;

/// A route table, read in both directions.
///
/// ```
/// var routes = Router.of()
///         .get("symbols", "/symbols")
///         .get("symbol", "/symbols/{name}")
///         .post("index", "/index");
///
/// routes.recognise("GET", "/symbols/json.Json");   // Match(symbol, {name=json.Json})
/// routes.path("symbol", Map.of("name", "json.Json"));   // /symbols/json.Json
/// ```
///
/// Both directions come from the one definition, which is the point: a router
/// that recognises URLs from one list and builds them from another has two
/// lists to keep in step, and they will not stay in step.
///
/// **Which route wins.** More than one route can match a path, so the order is
/// stated rather than left to whoever wrote the table first: the route with
/// more fixed text wins, then the one with fewer variables, then the one
/// defined first. That is why `/users/new` beats `/users/{id}` no matter which
/// was written first, and why the rule can be read off the table instead of
/// being discovered by moving lines around.
///
/// **Methods.** A route table is written by hand and a request arrives off a
/// wire, so both are compared uppercased. A GET route answers HEAD.
///
/// A path here is a path: strip the query before asking, because a query is not
/// part of a route — its parameters may arrive in any order, and RFC 6570
/// cannot read one backwards anyway.
public final class Router {

    private final List<Route> routes;
    private final Map<String, Template> named;

    private Router(List<Route> routes, Map<String, Template> named) {
        this.routes = routes;
        this.named = named;
    }

    public static Router of() {
        return new Router(List.of(), Map.of());
    }

    public Router route(String name, String method, String template) {
        return with(Route.of(name, method, template));
    }

    public Router get(String name, String template) {
        return route(name, "GET", template);
    }

    public Router post(String name, String template) {
        return route(name, "POST", template);
    }

    public Router put(String name, String template) {
        return route(name, "PUT", template);
    }

    public Router patch(String name, String template) {
        return route(name, "PATCH", template);
    }

    public Router delete(String name, String template) {
        return route(name, "DELETE", template);
    }

    /// Adds a route, refusing the two mistakes a table can make about names: the
    /// same name for two different URLs, which leaves nothing to build from,
    /// and the same name and method twice, which is a copy that was not
    /// finished.
    public Router with(Route route) {
        var existing = named.get(route.name());
        if (existing != null && !existing.text().equals(route.template().text())) {
            throw new DispatchException("route " + route.name() + " is already " + existing.text()
                    + " and cannot also be " + route.template().text());
        }
        if (routes.stream().anyMatch(defined -> defined.name().equals(route.name())
                && defined.method().equals(route.method()))) {
            throw new DispatchException("route " + route.name() + " is already defined for " + route.method());
        }

        var next = new ArrayList<>(routes);
        next.add(route);
        next.sort(Router::specificity);

        var names = new LinkedHashMap<>(named);
        names.put(route.name(), route.template());
        return new Router(List.copyOf(next), Map.copyOf(names));
    }

    /// Which route this is, if it is one.
    public Recognised recognise(String method, String path) {
        var wanted = method.toUpperCase(Locale.ROOT);
        var allowed = new TreeSet<String>();
        for (var route : routes) {
            var variables = route.template().match(path);
            if (variables.isEmpty()) continue;
            if (route.accepts(wanted)) return new Recognised.Match(route, variables.get());
            allowed.addAll(route.answers());
        }
        return allowed.isEmpty()
                ? new Recognised.NotFound(wanted, path)
                : new Recognised.NotAllowed(wanted, path, List.copyOf(allowed));
    }

    /// The URL of a named route. Every variable the template mentions has to be
    /// given one: an expansion with a value missing quietly leaves a hole where
    /// it should have been, and the URL that comes out is wrong in a way nobody
    /// notices until it is followed.
    public String path(String name, Map<String, ?> variables) {
        var template = named.get(name);
        if (template == null) {
            throw new DispatchException("no route named " + name
                    + " — there is " + String.join(", ", new TreeSet<>(named.keySet())));
        }
        for (var variable : template.names()) {
            if (variables.get(variable) == null) {
                throw new DispatchException("route " + name + " needs " + variable + " to build " + template.text());
            }
        }
        return template.expand(variables);
    }

    public String path(String name) {
        return path(name, Map.of());
    }

    /// The table, most specific first — the order [#recognise] reads it in.
    public List<Route> routes() {
        return routes;
    }

    public Optional<Route> route(String name) {
        return routes.stream().filter(route -> route.name().equals(name)).findFirst();
    }

    private static int specificity(Route left, Route right) {
        var fixed = Integer.compare(right.literals(), left.literals());
        return fixed != 0 ? fixed : Integer.compare(left.variables(), right.variables());
    }
}
