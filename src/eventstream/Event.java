package eventstream;

import java.util.Objects;

/// One complete event in an event stream.
///
/// `type`, `data`, and `id` are never null. An empty `type` becomes
/// [#MESSAGE]. Use an empty `id` when no id is present.
///
/// A parser sets `id` to the stream's most recent valid `id:` value. The value
/// can therefore come from an earlier event. This type does not validate wire
/// characters. [EventStream#write] validates them when it writes the event.
public record Event(
        /// The dispatch type. An empty value becomes [#MESSAGE].
        String type,
        /// The event data. A line feed separates data lines on the wire.
        String data,
        /// The last event id in force, or an empty string when no id is in force.
        String id) implements Signal {

    /// The default type for an event with no `event:` field.
    public static final String MESSAGE = "message";

    /// Creates an event and replaces an empty `type` with [#MESSAGE].
    public Event {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(id, "id");
        if (type.isEmpty()) type = MESSAGE;
    }

    /// Creates a [#MESSAGE] event with the given data and an empty id.
    public static Event of(String data) {
        return new Event(MESSAGE, data, "");
    }

    /// Creates an event with the given type and data and an empty id.
    public static Event of(String type, String data) {
        return new Event(type, data, "");
    }

    /// Returns an event with the same type and data and the given non-null id.
    public Event withId(String id) {
        return new Event(type, data, id);
    }
}
