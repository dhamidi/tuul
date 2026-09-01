package fetch;

import java.io.IOException;
import java.net.URI;

/// Reports an HTTP status that a caller rejected with [Response#requireSuccess()].
///
/// The server returned a complete response. The exception keeps the response
/// status, final URI, and headers so a caller can inspect them. The exception
/// does not own the response. The caller must still close it.
public final class HttpException extends IOException {
    private final int status; private final URI uri; private final Headers headers;

    /// Creates an exception for `status` received from `uri` with `headers`.
    ///
    /// The constructor does not read or close the response body.
    public HttpException(int status, URI uri, Headers headers) { super("HTTP " + status + " from " + uri); this.status = status; this.uri = uri; this.headers = headers; }

    /// Returns the rejected HTTP status code.
    public int status() { return status; }

    /// Returns the final URI of the rejected response.
    public URI uri() { return uri; }

    /// Returns the headers of the rejected response.
    public Headers headers() { return headers; }
}
