package web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// A request, as everything above a server sees it.
///
/// This is a record rather than an interface, which is the one place this
/// package departs from Go: a request is data, and having it be data is what
/// makes the two rewrites a hypermedia framework actually needs — the `_method`
/// override that lets a browser form say DELETE, and stripping a mount prefix —
/// into one call rather than a wrapper class. A server binding builds one; it
/// implements nothing.
///
/// The body is a stream and is read once. An upload is not something to hold in
/// memory, and pretending otherwise here would force every server binding to
/// buffer whatever arrives before a handler has had the chance to refuse it.
///
/// `attributes` is where a handler leaves something for the handler it wraps:
/// authentication puts who is asking there, routing puts the variables it
/// recovered from the path. It is a map rather than a field per concern because
/// this package cannot know what a middleware two libraries away will want to
/// say.
public record Request(
        String method,
        String path,
        Parameters query,
        Headers headers,
        InputStream body,
        String remote,
        Map<String, Object> attributes) {

    public Request {
        method = method.toUpperCase(java.util.Locale.ROOT);
        attributes = Map.copyOf(attributes);
    }

    public static Request of(String method, String path) {
        return of(method, path, Headers.NONE, InputStream.nullInputStream());
    }

    public static Request of(String method, String path, Headers headers, InputStream body) {
        var question = path.indexOf('?');
        var only = question < 0 ? path : path.substring(0, question);
        var query = question < 0 ? Parameters.NONE : Parameters.parse(path.substring(question + 1));
        return new Request(method, only, query, headers, body, "", Map.of());
    }

    /// The method, rewritten. A browser can only send GET and POST, so a form
    /// that means DELETE says so in a `_method` parameter and something has to
    /// believe it — see [web.Middlewares#methodOverride].
    public Request method(String method) {
        return new Request(method, path, query, headers, body, remote, attributes);
    }

    /// The path, rewritten — what stripping a mount prefix does.
    public Request path(String path) {
        return new Request(method, path, query, headers, body, remote, attributes);
    }

    public Request remote(String remote) {
        return new Request(method, path, query, headers, body, remote, attributes);
    }

    public Request headers(Headers headers) {
        return new Request(method, path, query, headers, body, remote, attributes);
    }

    /// Leaves something for whoever this request is passed to.
    public Request with(String name, Object value) {
        var next = new LinkedHashMap<>(attributes);
        next.put(name, value);
        return new Request(method, path, query, headers, body, remote, next);
    }

    /// What somebody left, if it is there and is what you expected. A wrong type
    /// is nothing rather than a cast failure, because an attribute is a message
    /// between strangers.
    public <T> Optional<T> attribute(String name, Class<T> type) {
        var value = attributes.get(name);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    public Optional<String> header(String name) {
        return headers.first(name);
    }

    /// The content type without its parameters, folded — `text/html` out of
    /// `text/HTML; charset=utf-8`.
    public String type() {
        var value = headers.first("Content-Type", "");
        var end = value.indexOf(';');
        return (end < 0 ? value : value.substring(0, end)).strip().toLowerCase(java.util.Locale.ROOT);
    }

    public Optional<Long> length() {
        return headers.first("Content-Length").map(Long::valueOf);
    }

    /// Reads the whole body as text. Only for bodies that are known to be small
    /// — a form, a JSON document — since it does exactly what it says.
    public String text() throws IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    public byte[] bytes() throws IOException {
        return body.readAllBytes();
    }

    /// The body read as a form. Answers nothing when the content type is not a
    /// form, rather than parsing whatever arrived as though it were one.
    public Parameters form() throws IOException {
        if (!type().equals("application/x-www-form-urlencoded")) return Parameters.NONE;
        return Parameters.parse(text());
    }

    /// A body that has already been read, for building a request by hand.
    public static InputStream body(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return method + " " + path + (query.isEmpty() ? "" : "?" + query.encoded());
    }
}
