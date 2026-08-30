package web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/// The wrappers every hypermedia application needs, and none of which need a
/// server.
///
/// There is one. There were two, and the other one — a middleware that took a
/// mount prefix off the path on the way in — is gone: it moved where a request
/// was recognised and could not move where a link was written, so an
/// application that used it answered correctly and rendered every URL on every
/// page wrong. [web.dispatch.Router#mount(String, Router)] moves the templates
/// instead, which moves both. Mounting is a thing you do to a route table, not
/// to a request.
public final class Middlewares {

    /// The parameter Rails taught browsers to send, because a form element can
    /// only be a GET or a POST.
    public static final String METHOD = "_method";

    private static final Set<String> OVERRIDABLE = Set.of("PUT", "PATCH", "DELETE");

    /// The content type a browser sends a form in.
    private static final String FORM = "application/x-www-form-urlencoded";

    /// How much of a body this middleware reads to look for `_method`.
    ///
    /// A bound is necessary, because the body is a stream of any length and
    /// reading all of it into memory is how a server is used up. This is the
    /// same number as the default [web.uploads.Limits#fieldBytes()], which
    /// is what one form field is allowed to be — and `_method` is one form
    /// field, near the front of the body. The number is repeated rather than
    /// read, because `web` does not depend on `web.uploads`.
    ///
    /// A body longer than this is not searched, and it reaches the handler
    /// whole.
    private static final int OVERRIDE_BYTES = 64 * 1024;

    private Middlewares() {}

    /// Believes a form that says it meant DELETE.
    ///
    /// A browser cannot send anything but GET and POST from a form, so a
    /// hypermedia application either gives up half of HTTP or agrees on a
    /// parameter that means "no, really". Only a POST may be overridden, and
    /// only into a method that changes something — otherwise this is a way to
    /// turn a link into a delete.
    ///
    /// The body is read to find `_method` and then put back, so a handler
    /// downstream still reads the form the browser sent. Reading a body
    /// consumes it — the stream is read once — and this middleware used to
    /// hand the emptied request on, which made a form that says PUT and a
    /// handler that reads that form impossible to combine. See
    /// [Request#body(java.io.InputStream)], which exists for this.
    public static Middleware methodOverride() {
        return next -> (request, response) -> {
            if (!request.method().equals("POST")) {
                next.handle(request, response);
                return;
            }
            var query = request.query().first(METHOD, "").toUpperCase(Locale.ROOT);
            if (!query.isEmpty()) {
                next.handle(OVERRIDABLE.contains(query) ? request.method(query) : request, response);
                return;
            }
            next.handle(overridden(request), response);
        };
    }

    /// The same request, with the method the form asked for and with its body
    /// back.
    ///
    /// Only a form body is read, and only up to [#OVERRIDE_BYTES]. What was
    /// read is put in front of whatever is left, so the handler reads the
    /// whole body whatever its length, and this middleware never holds more
    /// than the bound.
    private static Request overridden(Request request) throws IOException {
        if (!request.type().equals(FORM)) return request;
        var body = request.body();
        var read = body.readNBytes(OVERRIDE_BYTES + 1);
        var restored = request.body(restore(read, body));
        if (read.length > OVERRIDE_BYTES) return restored;
        var wanted = Parameters.parse(new String(read, StandardCharsets.UTF_8))
                .first(METHOD, "").toUpperCase(Locale.ROOT);
        return OVERRIDABLE.contains(wanted) ? restored.method(wanted) : restored;
    }

    /// What was read, and then the rest of it.
    private static InputStream restore(byte[] read, InputStream rest) {
        var buffered = new ByteArrayInputStream(read);
        return read.length > OVERRIDE_BYTES ? new SequenceInputStream(buffered, rest) : buffered;
    }
}
