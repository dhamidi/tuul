package web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// Reading what a client sent back, and saying what to keep.
///
/// A request carries every cookie in one header, separated by semicolons; a
/// response sets them one header each, which is why [web.Headers] keeps values
/// that repeat. It does not decode an application value: it only removes the
/// optional HTTP quotes around one and ignores legacy `$` parameters. A caller
/// that wants to store something a cookie cannot hold encodes it. See
/// [web.sessions.Signature], which sessions and CSRF use for cookie values.
public final class Cookies {

    private Cookies() {}

    public static Map<String, String> of(Request request) {
        var cookies = new LinkedHashMap<String, String>();
        for (var header : request.headers().all("Cookie")) {
            for (var pair : header.split(";")) {
                var split = pair.indexOf('=');
                if (split < 0) continue;
                var name = pair.substring(0, split).strip();
                if (name.isEmpty() || name.startsWith("$")) continue;
                var value = pair.substring(split + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                cookies.putIfAbsent(name, value);
            }
        }
        return Map.copyOf(cookies);
    }

    public static Optional<String> first(Request request, String name) {
        return Optional.ofNullable(of(request).get(name));
    }

    public static void set(Response response, Cookie cookie) {
        response.add("Set-Cookie", cookie.header());
    }

    /// Removes one, which is done by setting it to nothing and telling the
    /// browser it expired an hour ago — there is no other way to say it.
    public static void clear(Response response, String name, String path) {
        set(response, new Cookie(name, "", path, Optional.of(Duration.ZERO), true, false, "Lax"));
    }
}
