package eventstream;

/// How long a client should wait before reconnecting, in milliseconds.
///
/// It is not a property of any one event: a server can send it before any event
/// at all, and it stays in force afterwards. That is why it arrives in the
/// stream on its own rather than hanging off the next [Event].
public record Retry(long milliseconds) implements Signal {}
