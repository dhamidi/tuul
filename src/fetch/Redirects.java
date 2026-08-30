package fetch;

/// The automatic redirect policy for a session.
public enum Redirects {
    /// Return the redirect response without sending a request to its target.
    NEVER,

    /// Follow a redirect only when its target has the same scheme, host, and port as the current URI.
    SAME_ORIGIN,

    /// Follow redirects across origins, follow HTTPS upgrades, and refuse HTTPS downgrades to HTTP.
    /// For 301, 302, and 303 responses, a POST becomes a GET. For 307 and 308 responses, the method and
    /// body stay unchanged only when the body is repeatable.
    BROWSER
}
