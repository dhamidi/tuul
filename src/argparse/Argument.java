package argparse;

/// A word on the command line that is not an option: the thing being acted on.
///
/// Arguments are filled in the order they were declared, which is the order
/// they are written in — the first bare word is the first argument, and so on
/// until a variadic one takes what is left.
public record Argument(String name, String description, Kind kind) {

    public enum Kind {

        /// Has to be there.
        REQUIRED,

        /// May be there.
        OPTIONAL,

        /// Takes everything that is left.
        VARIADIC,

        /// Takes everything after `--`, whatever it looks like. An option is
        /// only an option until somebody says it is not.
        PASSTHROUGH
    }

    /// How the argument is written in a usage line.
    public String written() {
        return switch (kind) {
            case REQUIRED -> "<" + name + ">";
            case OPTIONAL -> "[<" + name + ">]";
            case VARIADIC -> "[<" + name + ">...]";
            case PASSTHROUGH -> "-- <" + name + ">...";
        };
    }
}
