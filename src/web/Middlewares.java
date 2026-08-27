package web;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/// The wrappers every hypermedia application needs, and none of which need a
/// server.
public final class Middlewares {

    /// The parameter Rails taught browsers to send, because a form element can
    /// only be a GET or a POST.
    public static final String METHOD = "_method";

    private static final Set<String> OVERRIDABLE = Set.of("PUT", "PATCH", "DELETE");

    private Middlewares() {}

    /// Believes a form that says it meant DELETE.
    ///
    /// A browser cannot send anything but GET and POST from a form, so a
    /// hypermedia application either gives up half of HTTP or agrees on a
    /// parameter that means "no, really". Only a POST may be overridden, and
    /// only into a method that changes something — otherwise this is a way to
    /// turn a link into a delete.
    public static Middleware methodOverride() {
        return next -> (request, response) -> {
            if (!request.method().equals("POST")) {
                next.handle(request, response);
                return;
            }
            var wanted = wanted(request);
            next.handle(OVERRIDABLE.contains(wanted) ? request.method(wanted) : request, response);
        };
    }

    /// Serves an application mounted somewhere other than the root: the prefix
    /// comes off the path before anything downstream sees it, and a request
    /// that is not under the prefix is a 404 here rather than a surprise there.
    public static Middleware mountedAt(String prefix) {
        var mount = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return next -> (request, response) -> {
            var path = request.path();
            if (!path.equals(mount) && !path.startsWith(mount + "/")) {
                Responses.empty(Status.NOT_FOUND, response);
                return;
            }
            var stripped = path.substring(mount.length());
            next.handle(request.path(stripped.isEmpty() ? "/" : stripped), response);
        };
    }

    private static String wanted(Request request) {
        try {
            var override = request.query().first(METHOD).orElse("");
            if (!override.isEmpty()) return override.toUpperCase(Locale.ROOT);
            return request.form().first(METHOD, "").toUpperCase(Locale.ROOT);
        } catch (IOException e) {
            return "";
        }
    }
}
