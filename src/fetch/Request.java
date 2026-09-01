package fetch;

import eventstream.Signal;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

/// An immutable HTTP request bound to a session.
///
/// Each modifier returns a new request and leaves this request unchanged. The
/// method is uppercased, the URI must be an absolute HTTP or HTTPS URI, and the
/// body defaults to [Body#empty()]. A request can be sent again when its body
/// is repeatable.
public final class Request {
    private final Session session; private final String method; private final URI uri; private final Headers headers; private final Body body; private final Duration timeout;
    Request(Session session, String method, URI uri, Headers headers, Body body, Duration timeout) {
        this.session = session; this.method = checkMethod(method); this.uri = checkUri(uri); this.headers = headers; this.body = body; this.timeout = timeout;
    }
    /// Returns the uppercased HTTP method.
    public String method() { return method; }

    /// Returns the absolute HTTP or HTTPS target URI.
    public URI uri() { return uri; }

    /// Returns the request-specific headers.
    ///
    /// Session default headers are applied when the request is sent. A request
    /// header with the same name takes precedence over a session default.
    public Headers headers() { return headers; }

    /// Returns the request body.
    public Body body() { return body; }

    /// Returns a request with all values for `name` replaced by `value`.
    public Request header(String name, String value) { return new Request(session, method, uri, headers.with(name, value), body, timeout); }

    /// Returns a request with exactly `headers` as its request-specific headers.
    ///
    /// `headers` must not be null.
    public Request headers(Headers headers) { return new Request(session, method, uri, headers, body, timeout); }

    /// Returns a request with `body` as its body.
    ///
    /// The body must not be null. A one-shot body can support only one send.
    public Request body(Body body) { return new Request(session, method, uri, headers, java.util.Objects.requireNonNull(body), timeout); }

    /// Returns a request with a UTF-8 text body and a matching `Content-Type` header.
    public Request text(String value) { return text(value, StandardCharsets.UTF_8); }

    /// Returns a request with a body encoded using `charset` and a matching `Content-Type` header.
    public Request text(String value, Charset charset) { return body(Body.text(value, charset)).header("Content-Type", "text/plain; charset=" + charset.name()); }

    /// Returns a request with a positive per-exchange timeout.
    ///
    /// The original request remains unchanged. The timeout must not be zero or negative.
    public Request timeout(Duration timeout) { if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive"); return new Request(session, method, uri, headers, body, timeout); }

    /// Returns a request with a UTF-8 URL-encoded form body and matching content type.
    public Request form(Form form) { return body(Body.form(form)).header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"); }

    /// Returns a request with a form created from `fields`.
    public Request form(Map<String, ?> fields) { return form(Form.of(fields)); }

    /// Returns a request with a one-shot UTF-8 event stream body.
    ///
    /// Signals are sent in encounter order. The request sets `Content-Type`
    /// to `text/event-stream`. A later [#header] call can replace that value.
    /// [Body#eventStream(Stream)] closes `signals` when body consumption ends.
    /// A redirect cannot resend this body.
    public Request eventStream(Stream<? extends Signal> signals) { return body(Body.eventStream(signals)).header("Content-Type", "text/event-stream"); }

    /// Sends the request and waits until the final response headers arrive.
    ///
    /// The method follows the session's [Redirects] policy. It returns HTTP
    /// error statuses as responses. Call [Response#requireSuccess()] when a
    /// non-2xx status must be an exception. The caller must consume or close
    /// the returned response body.
    ///
    /// A transport failure throws [FetchException]. A runtime failure from
    /// request construction or redirect processing is propagated. An
    /// interruption throws `InterruptedException`.
    public Response send() throws IOException, InterruptedException {
        try { return sendAsync().get(); }
        catch (java.util.concurrent.ExecutionException e) { if (e.getCause() instanceof IOException io) throw io; if (e.getCause() instanceof RuntimeException runtime) throw runtime; throw new IOException(e.getCause()); }
    }

    /// Starts the request and returns a future for the final response headers.
    ///
    /// The future does not wait for the response body. A transport failure
    /// completes the future exceptionally with [FetchException]. Redirects are
    /// followed before the future completes. The caller must consume or close
    /// the response body.
    public CompletableFuture<Response> sendAsync() { return session.track(exchange(this, new ArrayList<>(), 0)); }

    private CompletableFuture<Response> exchange(Request request, ArrayList<Response.Hop> history, int count) {
        var outgoing = request.headers;
        for (var name : session.defaults().names()) if (!outgoing.has(name)) for (var value : session.defaults().all(name)) outgoing = outgoing.add(name, value);
        var cookieHeaders = session.jar().request(request.uri);
        if (!outgoing.has("Cookie")) for (var value : cookieHeaders.all("Cookie")) outgoing = outgoing.add("Cookie", value);
        if (!outgoing.has("Accept-Encoding")) outgoing = outgoing.with("Accept-Encoding", "gzip, deflate");
        var builder = HttpRequest.newBuilder(request.uri);
        if (timeout != null) builder.timeout(timeout);
        outgoing.values().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        builder.method(request.method, publisher(request.body));
        return session.fetch().client().sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                .handle((response, failure) -> {
                    if (failure != null) throw new CompletionException(new FetchException(request.uri, FetchException.Phase.RECEIVE, retryable(request.method), unwrap(failure)));
                    return response;
                }).thenCompose(response -> received(request, response, history, count));
    }

    private CompletableFuture<Response> received(Request request, HttpResponse<java.io.InputStream> incoming, ArrayList<Response.Hop> history, int count) {
        var responseHeaders = new Headers(incoming.headers().map());
        session.jar().response(request.uri, responseHeaders);
        var location = responseHeaders.first("Location");
        if (location.isEmpty() || !redirectStatus(incoming.statusCode()) || !allowed(request.uri, request.uri.resolve(location.get())))
            return CompletableFuture.completedFuture(Response.create(request, incoming.statusCode(), request.uri, responseHeaders, incoming.body(), history));
        var target = request.uri.resolve(location.get());
        var redirected = redirected(request, incoming.statusCode(), target);
        if (redirected == null || count >= session.fetch().maxRedirects()) return CompletableFuture.completedFuture(Response.create(request, incoming.statusCode(), request.uri, responseHeaders, incoming.body(), history));
        try { incoming.body().close(); } catch (IOException ignored) {}
        var nextHistory = new ArrayList<>(history); nextHistory.add(new Response.Hop(request.uri, incoming.statusCode(), responseHeaders));
        return exchange(redirected, nextHistory, count + 1);
    }

    private Request redirected(Request request, int status, URI target) {
        var changeToGet = status == 303 || ((status == 301 || status == 302) && request.method.equals("POST"));
        if (!changeToGet && !request.body.repeatable()) return null;
        var nextHeaders = sameOrigin(request.uri, target) ? request.headers : request.headers.without("Authorization").without("Cookie");
        return new Request(session, changeToGet ? "GET" : request.method, target, nextHeaders, changeToGet ? Body.empty() : request.body, request.timeout);
    }
    private boolean allowed(URI from, URI to) { return switch (session.redirectPolicy()) { case NEVER -> false; case SAME_ORIGIN -> sameOrigin(from, to); case BROWSER -> !(from.getScheme().equals("https") && to.getScheme().equals("http")); }; }
    private static HttpRequest.BodyPublisher publisher(Body body) {
        if (body instanceof Body.PublisherBody) return body.length().isPresent()
                ? HttpRequest.BodyPublishers.fromPublisher(body.publisher(), body.length().getAsLong())
                : HttpRequest.BodyPublishers.fromPublisher(body.publisher());
        return HttpRequest.BodyPublishers.ofInputStream(() -> { try { return body.stream(); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } });
    }
    private static boolean sameOrigin(URI a, URI b) { return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b); }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80; }
    private static boolean redirectStatus(int status) { return status == 301 || status == 302 || status == 303 || status == 307 || status == 308; }
    private static boolean retryable(String method) { return method.equals("GET") || method.equals("HEAD") || method.equals("PUT") || method.equals("DELETE"); }
    private static Throwable unwrap(Throwable failure) { while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause(); return failure; }
    private static String checkMethod(String method) { var value = method.toUpperCase(java.util.Locale.ROOT); if (value.isBlank() || value.chars().anyMatch(c -> c <= 32 || c >= 127)) throw new IllegalArgumentException("invalid method"); return value; }
    private static URI checkUri(URI uri) { if (!uri.isAbsolute() || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) throw new IllegalArgumentException("HTTP URI must be absolute"); return uri; }
}
