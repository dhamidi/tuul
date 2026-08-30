package fetch;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/// An HTTP result with a one-shot streaming body.
///
/// The response is available when its headers arrive. The body remains on the
/// network until the caller reads or closes it. Always close the response when
/// the caller does not consume the body to the end.
///
/// The client decodes `identity`, `gzip`, and `deflate` content encodings before
/// it exposes the body. An unsupported content encoding fails response creation.
public final class Response implements AutoCloseable {
    private final Request request; private final int status; private final URI uri; private final Headers headers; private final Body body; private final List<Hop> history;
    private Response(Request request, int status, URI uri, Headers headers, Body body, List<Hop> history) { this.request = request; this.status = status; this.uri = uri; this.headers = headers; this.body = body; this.history = List.copyOf(history); }
    static Response create(Request request, int status, URI uri, Headers headers, InputStream input, List<Hop> history) {
        try { input = switch (headers.first("Content-Encoding", "").toLowerCase(Locale.ROOT).trim()) { case "", "identity" -> input; case "gzip" -> new GZIPInputStream(input); case "deflate" -> new InflaterInputStream(input); default -> throw new IllegalArgumentException("unsupported Content-Encoding"); }; }
        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        var stream = input;
        return new Response(request, status, uri, headers, new Body.StreamBody(() -> stream, OptionalLong.empty(), false), history);
    }
    /// Returns the HTTP status code.
    public int status() { return status; }

    /// Returns the final response URI after followed redirects.
    public URI uri() { return uri; }

    /// Returns the request that produced this response.
    ///
    /// When redirects were followed, this is the final request in the chain.
    public Request request() { return request; }

    /// Returns the headers received for this response.
    ///
    /// The values include wire `Content-Encoding` and `Content-Length` headers.
    public Headers headers() { return headers; }

    /// Selects the character set from the `Content-Type` header.
    ///
    /// The method returns UTF-8 when the header has no charset or has a
    /// malformed charset name. It throws `UnsupportedCharsetException` when
    /// the name is valid but unsupported by the JDK.
    public Charset charset() {
        var contentType = headers.first("Content-Type", "");
        for (var part : contentType.split(";")) { var item = part.trim(); if (item.toLowerCase(Locale.ROOT).startsWith("charset=")) { var value = item.substring(8).trim().replaceAll("^\"|\"$", ""); try { return Charset.forName(value); } catch (java.nio.charset.IllegalCharsetNameException ignored) { return StandardCharsets.UTF_8; } } }
        return StandardCharsets.UTF_8;
    }

    /// Opens a reader for the body using [#charset()].
    ///
    /// Closing the reader closes the response body. The body is one-shot.
    public Reader reader() throws IOException { return body.reader(charset()); }

    /// Reads the complete body using [#charset()] and returns it as text.
    ///
    /// This method closes the body after reading it and is intended for small responses.
    public String text() throws IOException { return body.text(charset()); }

    /// Returns the one-shot response body.
    public Body body() { return body; }

    /// Returns metadata for earlier redirect responses in wire order.
    ///
    /// Each hop contains its request URI, status, and headers. Hop bodies are
    /// not available because the client closes them before following redirects.
    public List<Hop> history() { return history; }

    /// Returns whether the status is in the inclusive 200 through 299 range.
    public boolean successful() { return status >= 200 && status <= 299; }

    /// Returns this response when [#successful()] is true.
    ///
    /// Otherwise this method throws [HttpException] without reading the body.
    /// The caller must close the response when the exception is caught or leaves its scope.
    public Response requireSuccess() throws HttpException { if (!successful()) throw new HttpException(status, uri, headers); return this; }

    /// Closes the response body and releases the response resources.
    ///
    /// This method is idempotent. It can close an unread one-shot body.
    public void close() { try { body.stream().close(); } catch (IOException | IllegalStateException ignored) {} }

    /// Metadata for one response that the client followed during redirection.
    public record Hop(
            /// The request URI that received this redirect response.
            URI uri,
            /// The redirect status code.
            int status,
            /// The headers of this redirect response.
            Headers headers) {
        /// Creates redirect metadata from the request URI, status, and headers.
        public Hop {}

        /// Returns the request URI that received this redirect response.
        @Override
        public URI uri() { return uri; }

        /// Returns the redirect status code.
        @Override
        public int status() { return status; }

        /// Returns the headers of this redirect response.
        @Override
        public Headers headers() { return headers; }
    }
}
