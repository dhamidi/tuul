package uritemplates;

import java.util.Optional;

/// The operator table of RFC 6570, as data.
///
/// Section 3.2.1 gives it as a grid — what to write first, what to write
/// between values, whether values carry their variable's name, what an empty
/// value looks like, and which characters survive unencoded. Every difference
/// between the eight kinds of expansion is in these five columns, so the
/// expander reads them rather than asking which operator it has.
public enum Operator {

    /// `{var}` — the value, and nothing around it.
    SIMPLE("", "", ",", false, "", false),

    /// `{+var}` — reserved characters pass through unencoded.
    RESERVED("+", "", ",", false, "", true),

    /// `{#var}` — a fragment, reserved characters and all.
    FRAGMENT("#", "#", ",", false, "", true),

    /// `{.var}` — a dot-prefixed label.
    LABEL(".", ".", ".", false, "", false),

    /// `{/var}` — path segments.
    PATH("/", "/", "/", false, "", false),

    /// `{;var}` — path-style parameters, which carry their names.
    PARAMETER(";", ";", ";", true, "", false),

    /// `{?var}` — a form-style query.
    QUERY("?", "?", "&", true, "=", false),

    /// `{&var}` — more of a query that has already begun.
    CONTINUATION("&", "&", "&", true, "=", false);

    /// Reserved for extensions RFC 6570 never made. A template using one is an
    /// error rather than a guess, because whatever they come to mean, it will
    /// not be what this would have done with them.
    static final String FUTURE = "=,!@|";

    private final String symbol;
    private final String first;
    private final String separator;
    private final boolean named;
    private final String ifEmpty;
    private final boolean reserved;

    Operator(String symbol, String first, String separator, boolean named, String ifEmpty, boolean reserved) {
        this.symbol = symbol;
        this.first = first;
        this.separator = separator;
        this.named = named;
        this.ifEmpty = ifEmpty;
        this.reserved = reserved;
    }

    /// How the operator is written inside the braces; empty for the operator
    /// that has no symbol.
    public String symbol() {
        return symbol;
    }

    /// What goes in front of the first value that is defined.
    public String first() {
        return first;
    }

    /// What goes between values.
    public String separator() {
        return separator;
    }

    /// Whether a value is written as `name=value`.
    public boolean named() {
        return named;
    }

    /// What stands in for `=value` when the value is an empty string.
    public String ifEmpty() {
        return ifEmpty;
    }

    /// Whether reserved characters and existing percent-triples survive
    /// unencoded.
    public boolean reserved() {
        return reserved;
    }

    public static Optional<Operator> of(char symbol) {
        for (var operator : values()) {
            if (!operator.symbol.isEmpty() && operator.symbol.charAt(0) == symbol) return Optional.of(operator);
        }
        return Optional.empty();
    }
}
