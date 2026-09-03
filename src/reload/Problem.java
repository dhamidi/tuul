package reload;

import java.nio.file.Path;

/// One immutable failure that prevents a revision from becoming active.
public record Problem(String phase, String source, long line, String message) {

    public Problem {
        phase = require(phase, "phase");
        source = source == null ? "" : source;
        if (line < 0) throw new IllegalArgumentException("line must not be negative");
        message = require(message, "message");
    }

    public Problem(String phase, Path source, long line, String message) {
        this(phase, source == null ? "" : source.toString(), line, message);
    }

    public Problem(String phase, String message) {
        this(phase, "", 0, message);
    }

    /// Converts one thrown failure into a problem without a source location.
    public static Problem exception(String phase, Throwable failure) {
        var message = failure.getMessage();
        return new Problem(phase, "", 0,
                failure.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
