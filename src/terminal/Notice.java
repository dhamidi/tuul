package terminal;

import java.util.Objects;

/// One notice that the progress actor groups by its stable key.
public record Notice(String key, String text) {

    public Notice {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) throw new IllegalArgumentException("a notice key cannot be blank");
        text = text == null ? "" : text;
    }
}
