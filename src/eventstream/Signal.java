package eventstream;

/// A value emitted by an event-stream parser.
///
/// A parser emits an [Event] when a complete event ends. It emits a [Retry]
/// when the stream contains a valid retry directive. It emits both values in
/// their arrival order.
///
/// A caller that handles every protocol value must switch over both permitted
/// implementations. A caller that only needs events can use
/// [EventStream#events] instead.
public sealed interface Signal permits Event, Retry {}
