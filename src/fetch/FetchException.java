package fetch;

import java.io.IOException;
import java.net.URI;

/// Reports a failure that occurs before a complete HTTP response arrives.
///
/// [Request#send()] throws this exception as an `IOException`. [Request#sendAsync()]
/// completes exceptionally with this exception when transport work fails.
public final class FetchException extends IOException {
    /// Identifies the transport phase where a failure occurred.
    public enum Phase {
        /// The client could not establish a connection.
        CONNECT,

        /// The client could not send the request.
        SEND,

        /// The client could not receive a complete HTTP response.
        RECEIVE,

        /// The client could not close transport resources.
        CLOSE
    }
    private final URI uri; private final Phase phase; private final boolean retryable;

    /// Creates a transport failure for `uri`.
    ///
    /// `phase` identifies the failed operation. `retryable` tells the caller
    /// whether retrying does not require replaying a one-shot request body.
    public FetchException(URI uri, Phase phase, boolean retryable, Throwable cause) { super(cause); this.uri = uri; this.phase = phase; this.retryable = retryable; }

    /// Returns the URI involved in the failed operation.
    public URI uri() { return uri; }

    /// Returns the transport phase that failed.
    public Phase phase() { return phase; }

    /// Returns whether the request method is considered retryable by the client.
    ///
    /// This flag does not recreate a one-shot body. The caller must still
    /// provide a new body when the retry sends request bytes.
    public boolean retryable() { return retryable; }
}
