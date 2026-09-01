package fetch;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.URI;
import java.util.List;
import java.util.Map;

/// Stores cookies between requests in one session.
///
/// [Session] asks the jar for request headers before each request. It gives the
/// jar the response headers after each response, including redirect hops.
public interface CookieJar {
    /// Creates a thread-safe, in-memory cookie jar that accepts cookies from all URIs.
    ///
    /// The jar has no persistence. It is normally created by [Fetch#session()].
    static CookieJar memory() { return new Jdk(new CookieManager(null, CookiePolicy.ACCEPT_ALL)); }

    /// Creates a jar that never returns or stores cookies.
    static CookieJar none() { return new CookieJar() { public Headers request(URI uri) { return Headers.NONE; } public void response(URI uri, Headers headers) {} }; }

    /// Creates a cookie jar backed by the caller's JDK `store`.
    ///
    /// The jar accepts cookies from all URIs. The caller owns the store and its
    /// persistence or lifetime. A null `store` asks [CookieManager] to create
    /// its default in-memory store.
    static CookieJar of(CookieStore store) { return new Jdk(new CookieManager(store, CookiePolicy.ACCEPT_ALL)); }

    /// Returns the headers that the caller should add when it sends a request to `uri`.
    ///
    /// The result can be empty. The caller must not replace an explicitly supplied request `Cookie` header with this result.
    Headers request(URI uri);

    /// Gives the jar the response headers received from `uri` so it can store cookies.
    ///
    /// The headers can be empty. The jar decides which `Set-Cookie` values to store.
    void response(URI uri, Headers headers);

    /// A cookie jar backed by a JDK [CookieManager].
    final class Jdk implements CookieJar {
        private final CookieManager manager;
        Jdk(CookieManager manager) { this.manager = manager; }

        /// Returns cookies selected by the JDK cookie policy for `uri`.
        ///
        /// An I/O failure causes `IllegalStateException`.
        public synchronized Headers request(URI uri) {
            try { return from(manager.get(uri, Map.of())); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }

        /// Stores every cookie accepted by the JDK cookie policy from `headers`.
        ///
        /// An I/O failure causes `IllegalStateException`.
        public synchronized void response(URI uri, Headers headers) {
            try { manager.put(uri, headers.values()); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
        private static Headers from(Map<String, List<String>> map) { return new Headers(map); }
    }
}
