package jsonschema;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/// The regular expressions behind `pattern`, `patternProperties` and the
/// `regex` format, and how far they are from what the specification asks for.
///
/// **The limit, stated precisely.** The specification says these patterns are
/// ECMA-262 regular expressions. This package compiles them with
/// [java.util.regex.Pattern], which is not ECMA-262. The two agree on the
/// syntax that schemas normally use — literals, classes, `*`, `+`, `?`, `{n,m}`,
/// groups, alternation, anchors, backreferences and lookahead — and they differ
/// in these ways:
///
/// - `\d`, `\w`, `\s` and `\b` are ASCII-only in ECMA-262 without the `u` flag.
///   Java matches `\d` against ASCII digits as well, but `\w` and `\b` follow
///   Java's own rules and `\s` includes characters ECMA-262 leaves out.
/// - Java has syntax that ECMA-262 does not: `\p{...}` properties, possessive
///   quantifiers such as `a*+`, atomic groups `(?>...)`, `\A`, `\z`, `\Z`,
///   `\Q...\E` and `[a&&[^b]]` class intersection. A pattern that uses one of
///   them is accepted here and rejected by an ECMA-262 engine.
/// - Java rejects a few things ECMA-262 accepts. A `]` or a `{` that is not part
///   of a valid quantifier stands for itself in ECMA-262 and raises a syntax
///   error in Java.
/// - `$` matches before a trailing newline in Java and only at the very end in
///   ECMA-262.
///
/// A pattern is not anchored. Both languages ask whether the pattern occurs
/// anywhere in the string, so this package uses [java.util.regex.Matcher#find]
/// and never `matches`.
///
/// A pattern that does not compile makes no instance fail. The specification
/// puts pattern syntax in the schema author's hands, and a broken pattern is a
/// broken schema, not an invalid instance, so [#of(String)] answers with a
/// pattern that matches nothing and evaluation carries on.
public final class Patterns {

    private static final Pattern NOTHING = Pattern.compile("(?!)");
    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    private Patterns() {}

    /// The compiled form of this pattern, cached. Compiling the same pattern
    /// once per property of every instance would dominate the cost of
    /// validation.
    public static Pattern of(String pattern) {
        return COMPILED.computeIfAbsent(pattern, text -> {
            try {
                return Pattern.compile(text);
            } catch (RuntimeException broken) {
                return NOTHING;
            }
        });
    }

    /// Whether the pattern occurs anywhere in the value.
    public static boolean found(String pattern, String value) {
        return of(pattern).matcher(value).find();
    }

    /// Whether this text compiles as a regular expression at all. The `regex`
    /// format asks this.
    public static boolean valid(String pattern) {
        try {
            Pattern.compile(pattern);
            return true;
        } catch (RuntimeException broken) {
            return false;
        }
    }
}
