package fetch;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/// Holds cookies and request defaults for one user agent.
///
/// A session shares its [Fetch] connection pool but keeps its own default
/// headers, [CookieJar], and [Redirects] policy. Configure it before creating
/// the first request. After that point, configuration is fixed and the session
/// can create concurrent requests.
///
/// A new session starts with no default headers, an in-memory cookie jar, and
/// the [Redirects#NEVER] policy.
public final class Session implements AutoCloseable {
    private final Fetch fetch; private Headers defaults = Headers.NONE; private CookieJar cookies; private Redirects redirects = Redirects.NEVER;
    private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet(); private boolean started; private volatile boolean closed;
    Session(Fetch fetch, CookieJar cookies) { this.fetch = fetch; this.cookies = java.util.Objects.requireNonNull(cookies); }
    /// Replaces the default values for `name` with `value`.
    ///
    /// The session must not have created a request. This method returns the same session.
    public synchronized Session header(String name, String value) { mutable(); defaults = defaults.with(name, value); return this; }

    /// Replaces all session default headers with `defaults`.
    ///
    /// The session must not have created a request. This method returns the same session.
    public synchronized Session headers(Headers defaults) { mutable(); this.defaults = java.util.Objects.requireNonNull(defaults); return this; }

    /// Sets the automatic redirect policy for future requests.
    ///
    /// The session must not have created a request. This method returns the same session.
    public synchronized Session redirects(Redirects policy) { mutable(); redirects = java.util.Objects.requireNonNull(policy); return this; }

    /// Replaces the cookie jar used for future requests.
    ///
    /// The session must not have created a request. This method returns the same session.
    public synchronized Session cookies(CookieJar jar) { mutable(); cookies = java.util.Objects.requireNonNull(jar); return this; }

    /// Creates an immutable request with `method` and `uri` and an empty body.
    ///
    /// This call starts the session and fixes its configuration. The method is
    /// uppercased by [Request]. The URI must be absolute and use HTTP or HTTPS.
    public Request request(String method, URI uri) { start(); return new Request(this, method, uri, Headers.NONE, Body.empty(), null); }

    /// Creates a GET request with an empty body.
    public Request get(URI uri) { return request("GET", uri); }

    /// Creates a HEAD request with an empty body.
    public Request head(URI uri) { return request("HEAD", uri); }

    /// Creates a POST request with `body`.
    public Request post(URI uri, Body body) { return request("POST", uri).body(body); }
    synchronized Headers defaults() { return defaults; } CookieJar jar() { return cookies; } Redirects redirectPolicy() { return redirects; } Fetch fetch() { return fetch; }
    <T> CompletableFuture<T> track(CompletableFuture<T> future) { active.add(future); future.whenComplete((result, error) -> active.remove(future)); return future; }
    private synchronized void start() { if (closed) throw new IllegalStateException("session is closed"); fetch.client(); started = true; }
    private void mutable() { if (started) throw new IllegalStateException("session configuration is fixed"); if (closed) throw new IllegalStateException("session is closed"); }
    /// Closes the session and cancels its active requests.
    ///
    /// This method is idempotent. It rejects later request creation and does
    /// not close the shared [Fetch] connection pool.
    public void close() { if (closed) return; closed = true; active.forEach(future -> future.cancel(true)); active.clear(); fetch.remove(this); }
}
