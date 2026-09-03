package reload;

import java.time.Instant;
import java.util.Map;

/// An immutable lifecycle observation.
public record Event(Instant time, String revision, String kind, Map<String, String> detail) {

    public Event {
        time = time == null ? Instant.now() : time;
        revision = revision == null ? "" : revision;
        kind = require(kind, "kind");
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public Event(String revision, String kind) {
        this(Instant.now(), revision, kind, Map.of());
    }

    public Event(String revision, String kind, Map<String, String> detail) {
        this(Instant.now(), revision, kind, detail);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
