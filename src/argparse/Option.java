package argparse;

import java.util.List;
import java.util.Optional;
import json.Json;

/// Something with a leading dash: a switch, or a name with a value after it.
///
/// An option knows its own default, so nothing downstream has to remember what
/// absent means. A flag that was not given is `false`; a repeated option that
/// was not given is an empty array; a value option with no default is simply
/// not there, which lets whoever reads it decide.
public record Option(String name, char letter, String description, Kind kind, Optional<Json> fallback) {

    /// An option with no short form.
    public static final char NONE = '\0';

    public enum Kind {

        /// Given or not given, and nothing more to say.
        FLAG,

        /// Takes a value. The last one given wins.
        VALUE,

        /// Takes a value, and keeps every one it is given.
        REPEATED
    }

    public boolean takesValue() {
        return kind != Kind.FLAG;
    }

    public String written() {
        return "--" + name;
    }

    public Optional<String> abbreviated() {
        return letter == NONE ? Optional.empty() : Optional.of("-" + letter);
    }

    /// What the option is worth when nobody mentioned it.
    public Optional<Json> absent() {
        return switch (kind) {
            case FLAG -> Optional.of(Json.FALSE);
            case REPEATED -> Optional.of(Json.Array.of(List.of()));
            case VALUE -> fallback;
        };
    }
}
