package terminal;

import java.util.Objects;

/// One grouped notice in an immutable [ProgressFrame].
public record NoticeLine(String key, String text, int count) {

    public NoticeLine {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) throw new IllegalArgumentException("a notice key cannot be blank");
        text = text == null ? "" : text;
        if (count < 1) throw new IllegalArgumentException("a notice count must be positive");
    }
}
