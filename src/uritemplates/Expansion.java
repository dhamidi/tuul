package uritemplates;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// The expansion algorithm of RFC 6570 section 3.2, written to a [Writer].
///
/// The shape of it is one loop over the variables of an expression, asking the
/// [Operator] table what to put between them. What a value *is* — a string, a
/// list, a map — decides the rest, and the three cases differ enough that they
/// are three methods rather than one with flags.
///
/// A value that is undefined contributes nothing, and that includes an empty
/// list or an empty map. An empty *string* is defined, which is the difference
/// between `X{.empty}` giving `X.` and `X{.undef}` giving `X`.
final class Expansion {

    private Expansion() {}

    static void write(List<Part> parts, Map<String, ?> variables, Writer out) throws IOException {
        for (var part : parts) {
            switch (part) {
                case Part.Literal(var text) -> out.write(text);
                case Part.Expression expression -> write(expression, variables, out);
            }
        }
    }

    private static void write(Part.Expression expression, Map<String, ?> variables, Writer out) throws IOException {
        var operator = expression.operator();
        var first = true;
        for (var variable : expression.variables()) {
            var value = variables.get(variable.name());
            if (undefined(value)) continue;
            out.write(first ? operator.first() : operator.separator());
            first = false;
            value(operator, variable, value, out);
        }
    }

    private static void value(Operator operator, Varspec variable, Object value, Writer out) throws IOException {
        switch (value) {
            case Map<?, ?> pairs -> pairs(operator, variable, pairs, out);
            case Collection<?> items -> items(operator, variable, items, out);
            case Object scalar -> scalar(operator, variable, String.valueOf(scalar), out);
        }
    }

    /// A string, or anything that is not a list or a map. The prefix modifier
    /// belongs to this case alone.
    private static void scalar(Operator operator, Varspec variable, String value, Writer out) throws IOException {
        var text = variable.truncated() ? Encoder.prefix(value, variable.maxLength()) : value;
        name(operator, variable.name(), text.isEmpty(), out);
        Encoder.write(text, operator.reserved(), out);
    }

    private static void items(Operator operator, Varspec variable, Collection<?> items, Writer out) throws IOException {
        composite(variable);
        if (!variable.explode()) {
            name(operator, variable.name(), false, out);
            join(items.stream().map(String::valueOf).toList(), ",", operator, out);
            return;
        }
        var separator = "";
        for (var item : items) {
            if (item == null) continue;
            out.write(separator);
            separator = operator.separator();
            scalar(operator, variable, String.valueOf(item), out);
        }
    }

    /// An exploded map writes its own keys as the names, which is why a named
    /// operator makes no difference to it: `{;keys*}` is `;semi=%3B`, not
    /// `;keys=semi=%3B`.
    private static void pairs(Operator operator, Varspec variable, Map<?, ?> pairs, Writer out) throws IOException {
        composite(variable);
        if (!variable.explode()) {
            name(operator, variable.name(), false, out);
            var flattened = pairs.entrySet().stream()
                    .flatMap(pair -> Stream.of(String.valueOf(pair.getKey()), String.valueOf(pair.getValue())))
                    .toList();
            join(flattened, ",", operator, out);
            return;
        }
        var separator = "";
        for (var pair : pairs.entrySet()) {
            var value = String.valueOf(pair.getValue());
            out.write(separator);
            separator = operator.separator();
            Encoder.write(String.valueOf(pair.getKey()), operator.reserved(), out);
            if (operator.named() && value.isEmpty()) out.write(operator.ifEmpty());
            else out.write('=');
            Encoder.write(value, operator.reserved(), out);
        }
    }

    /// `name=`, `name`, or nothing at all, depending on the operator and on
    /// whether what follows is empty.
    private static void name(Operator operator, String name, boolean empty, Writer out) throws IOException {
        if (!operator.named()) return;
        Encoder.write(name, false, out);
        out.write(empty ? operator.ifEmpty() : "=");
    }

    private static void join(List<String> values, String separator, Operator operator, Writer out) throws IOException {
        var between = "";
        for (var value : values) {
            out.write(between);
            between = separator;
            Encoder.write(value, operator.reserved(), out);
        }
    }

    /// A prefix takes the first characters of a string; a list has no first
    /// characters. The RFC says the modifier is not applicable, and a template
    /// that asks for it is asking for something that has no meaning.
    private static void composite(Varspec variable) {
        if (variable.truncated()) {
            throw new TemplateException("{" + variable.written() + "} asks for a prefix of a composite value");
        }
    }

    /// Undefined is the RFC's word for "contributes nothing": no value, an
    /// empty list, or an empty map. An empty string is a value.
    private static boolean undefined(Object value) {
        return switch (value) {
            case null -> true;
            case Collection<?> items -> items.isEmpty();
            case Map<?, ?> pairs -> pairs.isEmpty();
            default -> false;
        };
    }
}
