package eventstream;

import java.util.Objects;

/// One event, as it is dispatched.
///
/// `id` is the stream's last event id at the moment of dispatch, not only what
/// this event's own `id:` line said. The format carries that value forward
/// until something changes it, and a client that reconnects sends it back — so
/// an event that never mentioned an id still has the one in force.
///
/// Absent values are empty strings rather than nulls: every event has a type,
/// and a missing id is nothing to reason about.
public record Event(String type, String data, String id) implements Signal {

    /// What an event is called when it does not say.
    public static final String MESSAGE = "message";

    public Event {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(id, "id");
        if (type.isEmpty()) type = MESSAGE;
    }

    /// A `message` event carrying this data, which is what most events are.
    public static Event of(String data) {
        return new Event(MESSAGE, data, "");
    }

    public static Event of(String type, String data) {
        return new Event(type, data, "");
    }

    public Event withId(String id) {
        return new Event(type, data, id);
    }
}
