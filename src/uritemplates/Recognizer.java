package uritemplates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/// Reading a template backwards: given a URI it could have produced, which
/// values would have produced it.
///
/// RFC 6570 specifies expansion and stops there, for a good reason — most
/// expansions are not reversible. This offers the direction anyway for the two
/// operators where it is not a guess, because a router built on named routes
/// needs both directions of one definition and nothing else will do.
///
/// The test is whether the encoder guarantees a separator cannot appear inside
/// a value. `{var}` separates with `,` and `{/var}` with `/`, and both become
/// `%2C` and `%2F` inside a simple expansion, so the boundaries are real.
/// `{.var}` separates with `.`, which is unreserved and passes through
/// untouched — `X.foo.bar` could be one value or two and no regular expression
/// settles it. The reserved operators let anything through, the named ones
/// describe a query whose order is not the template's business, and a prefix
/// modifier has already thrown away what it would have to give back.
///
/// Like the parse, the pattern is built when the template is read, so a router
/// that matches a thousand requests compiles nothing a thousand times.
record Recognizer(Pattern pattern, List<String> names) {

    /// The characters a value can consist of once encoded for an operator that
    /// encodes everything but the unreserved set.
    private static final String VALUE = "((?:[A-Za-z0-9\\-._~]|%[0-9A-Fa-f]{2})*)";

    /// The pattern for a template that can be read backwards, or nothing for
    /// one that cannot.
    static Optional<Recognizer> of(List<Part> parts) {
        if (!parts.stream().allMatch(Recognizer::reversible)) return Optional.empty();

        var pattern = new StringBuilder();
        var names = new ArrayList<String>();
        for (var part : parts) {
            switch (part) {
                case Part.Literal(var text) -> pattern.append(Pattern.quote(text));
                case Part.Expression expression -> {
                    var separator = expression.operator().first();
                    for (var variable : expression.variables()) {
                        pattern.append(Pattern.quote(separator)).append(VALUE);
                        separator = expression.operator().separator();
                        names.add(variable.name());
                    }
                }
            }
        }
        return Optional.of(new Recognizer(Pattern.compile(pattern.toString()), names));
    }

    /// Assumes every variable the template mentions was given a value, which is
    /// what a route is: an expansion that omitted one left no trace of having
    /// omitted it, so nothing downstream could tell.
    Optional<Map<String, String>> match(String uri) {
        var found = pattern.matcher(uri);
        if (!found.matches()) return Optional.empty();
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < names.size(); index++) {
            values.put(names.get(index), Encoder.decode(found.group(index + 1)));
        }
        return Optional.of(values);
    }

    private static boolean reversible(Part part) {
        if (!(part instanceof Part.Expression expression)) return true;
        if (expression.operator() != Operator.SIMPLE && expression.operator() != Operator.PATH) return false;
        return expression.variables().stream().noneMatch(variable -> variable.explode() || variable.truncated());
    }
}
