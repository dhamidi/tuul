package eventstream;

/// A server's requested reconnect delay, in milliseconds.
///
/// A parser emits this value when it reads a non-negative decimal `retry:`
/// field. It emits the value independently of [Event], including before the
/// first event. The parser ignores an empty, signed, non-decimal, or
/// out-of-range `retry:` value. Direct construction accepts any `long`.
public record Retry(
        /// The requested reconnect delay. [EventStream#write] writes this value without validation.
        long milliseconds) implements Signal {}
