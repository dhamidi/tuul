package fetch;

/// The automatic redirect policy for a session.
///
/// The session applies this policy to 301, 302, 303, 307, and 308 responses.
public enum Redirects {
    /// Return the redirect response without sending a request to its target.
    NEVER,

    /// Follow a redirect only when its target has the same scheme, host, and port as the current URI.
    ///
    /// The client returns the redirect response for a target outside that origin.
    SAME_ORIGIN,

    /// Follow redirects across origins, follow HTTPS upgrades, and refuse HTTPS downgrades to HTTP.
    /// A POST becomes a GET after 301 or 302. Every method becomes GET after
    /// 303. A 307 or 308 keeps the method and body.
    ///
    /// The client removes `Authorization` and `Cookie` headers when the target changes origin.
    /// It returns a redirect response when following it requires resending a
    /// one-shot body.
    BROWSER
}
