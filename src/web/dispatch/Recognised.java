package web.dispatch;

import java.util.List;
import java.util.Map;

/// What a method and a path turned out to be.
///
/// Three outcomes, because there are three different things a server does next:
/// call the route, refuse the method, or say there is nothing there. Keeping
/// them apart is the whole point — a router that answers "no" to both a wrong
/// path and a wrong method has thrown away the one thing the client needed to
/// hear, which is that the resource exists and this is not how you ask it
/// something.
public sealed interface Recognised {

    /// The route, its raw path values, and the values parsed by its parameters.
    record Match(Route route, Map<String, String> variables, Map<String, Object> parameters) implements Recognised {

        public Match {
            variables = Map.copyOf(variables);
            parameters = Map.copyOf(parameters);
        }

        /// A named value from the path, for the common case of reading one.
        public String value(String name) {
            var value = variables.get(name);
            if (value == null) throw new DispatchException(route.name() + " has no variable named " + name);
            return value;
        }
    }

    /// The path is a route, but not with this method — a 405, and `allowed` is
    /// what its `Allow` header must say. `OPTIONS` is not in it: whether the
    /// server answers one is the server's business, not the route table's.
    record NotAllowed(String method, String path, List<String> allowed) implements Recognised {

        public NotAllowed {
            allowed = List.copyOf(allowed);
        }
    }

    /// Nothing in the table matches this path at all — a 404.
    record NotFound(String method, String path) implements Recognised {}
}
