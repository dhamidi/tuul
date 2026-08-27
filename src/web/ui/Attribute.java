package web.ui;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;
import java.util.Optional;

/// One attribute of an element.
///
/// An absent value is a boolean attribute — `checked`, `disabled`, `required` —
/// which HTML writes as the bare name, and whose presence is the whole meaning.
/// A value that is present and empty is `value=""`, which is a different thing
/// entirely, so this is an [Optional] rather than an empty string.
public record Attribute(String name, Optional<String> value) implements Node {

    public Attribute {
        Escape.name(name);
        Objects.requireNonNull(value, "value");
    }

    public static Attribute of(String name, String value) {
        return new Attribute(name, Optional.of(Objects.requireNonNull(value, "value")));
    }

    /// A boolean attribute: written or not written, never `true` or `false`.
    /// `disabled="false"` disables the element, which is the sort of thing this
    /// type exists to make unsayable.
    public static Attribute flag(String name) {
        return new Attribute(name, Optional.empty());
    }

    void write(Writer out) throws IOException {
        out.write(' ');
        out.write(name);
        if (value.isEmpty()) return;
        out.write("=\"");
        Escape.attribute(value.get(), out);
        out.write('"');
    }
}
