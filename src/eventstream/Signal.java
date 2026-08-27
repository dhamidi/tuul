package eventstream;

/// Everything an event stream can say.
///
/// The format carries two kinds of thing, not one: events to dispatch, and the
/// reconnection time the server wants a client to use. A parser that modelled
/// only the events would drop the other on the floor without saying so, which
/// is why this is a `switch` and not a single record.
public sealed interface Signal permits Event, Retry {}
