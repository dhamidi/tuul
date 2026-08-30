package fetch;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;

/// Owns the HTTP transport and creates independent sessions that share it.
///
/// A fetch client owns one connection pool and one [Execution]. Sessions share
/// the pool but keep separate cookies, default headers, and redirect policies.
/// Close the client after closing or cancelling the work created from it.
public final class Fetch implements AutoCloseable {
    private final Execution execution; private final boolean ownsExecution; private final HttpClient client;
    private final Options options; private final Set<Session> sessions = ConcurrentHashMap.newKeySet(); private volatile boolean closed;

    private Fetch(Execution execution, boolean ownsExecution, Options options) {
        this.execution = execution; this.ownsExecution = ownsExecution; this.options = options.copy();
        var builder = HttpClient.newBuilder().executor(execution::execute).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(this.options.connectTimeout);
        if (this.options.proxy != null) builder.proxy(this.options.proxy);
        if (this.options.sslContext != null) builder.sslContext(this.options.sslContext);
        client = builder.build();
    }

    /// Creates a client that runs transport work on the calling thread.
    ///
    /// Use this mode for sequential blocking calls and tests. The client owns
    /// the no-worker execution returned by [Execution#currentThread()].
    public static Fetch sequential() { return sequential(options()); }

    /// Creates a current-thread client with a copy of `options`.
    public static Fetch sequential(Options options) { return new Fetch(Execution.currentThread(), true, options); }

    /// Creates a client with one platform-thread event loop.
    ///
    /// Use [Request#sendAsync()] and non-blocking body consumption from that
    /// event loop. The client owns the event-loop thread.
    public static Fetch flow() { return flow(options()); }

    /// Creates a flow client with a copy of `options`.
    public static Fetch flow(Options options) { return new Fetch(Execution.flow(), true, options); }

    /// Creates a client that runs transport work on virtual threads.
    ///
    /// Use this mode for many blocking requests at the same time. The client
    /// owns the virtual-thread executor.
    public static Fetch virtualThreads() { return virtualThreads(options()); }

    /// Creates a virtual-thread client with a copy of `options`.
    public static Fetch virtualThreads(Options options) { return new Fetch(Execution.virtualThreads(), true, options); }

    /// Creates a client that borrows an application-owned `execution`.
    ///
    /// Closing the client does not close `execution`. The caller must keep the
    /// execution open until all client work has finished.
    public static Fetch of(Execution execution) { return of(execution, options()); }

    /// Creates a client that borrows `execution` and uses a copy of `options`.
    public static Fetch of(Execution execution, Options options) { return new Fetch(execution, false, options); }

    /// Creates an options builder with the package defaults.
    public static Options options() { return new Options(); }

    /// Creates a session with a new in-memory cookie jar.
    public Session session() { return session(CookieJar.memory()); }

    /// Creates a session that uses `cookies` and shares this client's transport.
    ///
    /// The caller can create several sessions from one client. The client must
    /// be open when this method runs.
    public Session session(CookieJar cookies) { if (closed) throw new IllegalStateException("fetch is closed"); var session = new Session(this, cookies); sessions.add(session); return session; }
    HttpClient client() { if (closed) throw new IllegalStateException("fetch is closed"); return client; }
    int maxRedirects() { return options.maxRedirects; }
    void remove(Session session) { sessions.remove(session); }
    /// Closes all sessions, cancels their active requests, and closes owned execution resources.
    ///
    /// This method is idempotent. A client created with [#of(Execution)] does
    /// not close the supplied execution.
    public void close() { if (closed) return; closed = true; sessions.forEach(Session::close); sessions.clear(); if (ownsExecution) execution.close(); }

    /// Construction options for a fetch client.
    ///
    /// The defaults are a ten-second connection timeout, 32 configured
    /// connections per origin, 10 redirect hops, the default proxy selector,
    /// and the default TLS context. [Fetch] copies these values when it is
    /// created, so later builder changes do not affect an existing client.
    public static final class Options {
        private Duration connectTimeout = Duration.ofSeconds(10); private int maxConnectionsPerOrigin = 32; private int maxRedirects = 10; private ProxySelector proxy; private SSLContext sslContext;

        /// Creates an options builder with the package defaults.
        public Options() {}

        /// Sets the timeout for establishing a connection.
        ///
        /// `timeout` must be positive. The default is ten seconds.
        public Options connectTimeout(Duration timeout) { if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive"); connectTimeout = timeout; return this; }

        /// Sets the maximum configured number of connections for one origin.
        ///
        /// `maximum` must be positive. HTTP/2 streams can share one connection.
        public Options maxConnectionsPerOrigin(int maximum) { if (maximum <= 0) throw new IllegalArgumentException("maximum must be positive"); maxConnectionsPerOrigin = maximum; return this; }

        /// Sets the maximum number of automatic redirect hops.
        ///
        /// `maximum` must not be negative. Zero returns the first redirect
        /// response without following it.
        public Options maxRedirects(int maximum) { if (maximum < 0) throw new IllegalArgumentException("maximum must not be negative"); maxRedirects = maximum; return this; }

        /// Sets the JDK proxy selector used for connections.
        ///
        /// `selector` must not be null.
        public Options proxy(ProxySelector selector) { proxy = java.util.Objects.requireNonNull(selector); return this; }

        /// Sets the TLS context used for HTTPS connections.
        ///
        /// `context` must not be null.
        public Options sslContext(SSLContext context) { sslContext = java.util.Objects.requireNonNull(context); return this; }
        private Options copy() { var copy = new Options(); copy.connectTimeout = connectTimeout; copy.maxConnectionsPerOrigin = maxConnectionsPerOrigin; copy.maxRedirects = maxRedirects; copy.proxy = proxy; copy.sslContext = sslContext; return copy; }
    }
}
