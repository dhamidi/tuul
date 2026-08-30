package web.dispatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import uritemplates.Template;
import web.RouteRef;

/// The route definitions that a [web.Router] reads in both directions.
///
/// Most applications use [web.Router]. This type exposes recognition for a
/// diagnostic or a test that needs to inspect matching without running a
/// handler.
public final class Routes {

    private final List<Route> routes;
    private final Map<String, Template> named;
    private final Map<String, RouteRef> references;

    private Routes(List<Route> routes, Map<String, Template> named, Map<String, RouteRef> references) {
        this.routes = routes;
        this.named = named;
        this.references = references;
    }

    public static Routes of() {
        return new Routes(List.of(), Map.of(), Map.of());
    }

    public Routes route(RouteRef reference, String method) {
        return with(Route.of(reference, method));
    }

    public Routes get(RouteRef reference) {
        return route(reference, "GET");
    }

    public Routes post(RouteRef reference) {
        return route(reference, "POST");
    }

    public Routes put(RouteRef reference) {
        return route(reference, "PUT");
    }

    public Routes patch(RouteRef reference) {
        return route(reference, "PATCH");
    }

    public Routes delete(RouteRef reference) {
        return route(reference, "DELETE");
    }

    /// Adds one definition. A name can answer several methods at one path. The
    /// same method twice and one name for different definitions are refused.
    public Routes with(Route route) {
        var existing = references.get(route.name());
        if (existing != null && !existing.equals(route.reference())) {
            throw new DispatchException("route " + route.name() + " is already " + existing.template()
                    + " and cannot also be " + route.reference().template());
        }
        var at = named.get(route.name());
        if (at != null && !at.text().equals(route.template().text())) {
            throw new DispatchException("route " + route.name() + " is already " + at.text()
                    + " and cannot also be " + route.template().text());
        }
        if (routes.stream().anyMatch(defined -> defined.name().equals(route.name())
                && defined.method().equals(route.method()))) {
            throw new DispatchException("route " + route.name() + " is already defined for " + route.method());
        }

        var next = new ArrayList<>(routes);
        next.add(route);
        next.sort(Routes::specificity);

        var names = new LinkedHashMap<>(named);
        names.put(route.name(), route.template());
        var refs = new LinkedHashMap<>(references);
        refs.put(route.name(), route.reference());
        return new Routes(List.copyOf(next), Map.copyOf(names), Map.copyOf(refs));
    }

    /// Adds every route from another table under a path prefix. References keep
    /// their names. [#path(RouteRef)] uses the prefixed templates afterwards.
    public Routes mount(String prefix, Routes mounted) {
        if (!prefix.isEmpty() && !prefix.startsWith("/")) {
            throw new DispatchException("a mount prefix is a path and starts with /, unlike " + prefix);
        }
        var result = this;
        for (var route : mounted.routes()) {
            var moved = Route.of(route.reference(), route.method(), under(prefix, route.template().text()));
            try {
                result = result.with(moved);
            } catch (DispatchException collision) {
                throw new DispatchException("mounting at " + (prefix.isEmpty() ? "/" : prefix)
                        + ": " + collision.getMessage());
            }
        }
        return result;
    }

    /// Recognises a method and path. A path variable that its [Parameter]
    /// refuses does not match that route.
    public Recognised recognise(String method, String path) {
        var wanted = method.toUpperCase(Locale.ROOT);
        var allowed = new TreeSet<String>();
        for (var route : routes) {
            var variables = route.template().match(path);
            if (variables.isEmpty()) continue;
            var parameters = route.reference().parse(variables.get());
            if (parameters.isEmpty()) continue;
            if (route.accepts(wanted)) return new Recognised.Match(route, variables.get(), parameters.get());
            allowed.addAll(route.answers());
        }
        return allowed.isEmpty()
                ? new Recognised.NotFound(wanted, path)
                : new Recognised.NotAllowed(wanted, path, List.copyOf(allowed));
    }

    /// Builds the mounted path for a reference. Every declared parameter must
    /// be bound with [RouteRef#with] before this call.
    public String path(RouteRef reference) {
        var template = named.get(reference.name());
        if (template == null) {
            throw new DispatchException("no route named " + reference.name()
                    + " — there is " + String.join(", ", new TreeSet<>(named.keySet())));
        }
        var defined = references.get(reference.name());
        if (!sameDefinition(defined, reference)) {
            throw new DispatchException("route " + reference.name() + " is not the registered reference");
        }
        for (var variable : template.names()) {
            if (reference.values().get(variable) == null) {
                throw new DispatchException("route " + reference.name() + " needs " + variable
                        + " to build " + template.text());
            }
        }
        return template.expand(reference.values());
    }

    /// The definitions, most specific first.
    public List<Route> routes() {
        return routes;
    }

    public Optional<Route> route(RouteRef reference) {
        return routes.stream().filter(route -> route.name().equals(reference.name())).findFirst();
    }

    public Optional<Route> route(String name) {
        return routes.stream().filter(route -> route.name().equals(name)).findFirst();
    }

    private static boolean sameDefinition(RouteRef left, RouteRef right) {
        return left.name().equals(right.name())
                && left.template().equals(right.template())
                && left.parameters().equals(right.parameters());
    }

    private static String under(String prefix, String template) {
        var base = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (base.isEmpty()) return template;
        if (template.equals("/")) return base;
        return base + (template.startsWith("/") ? template : "/" + template);
    }

    private static int specificity(Route left, Route right) {
        var fixed = Integer.compare(right.literals(), left.literals());
        return fixed != 0 ? fixed : Integer.compare(left.variables(), right.variables());
    }
}
