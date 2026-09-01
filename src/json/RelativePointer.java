package json;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.UnaryOperator;

/// A Relative JSON Pointer from
/// [draft-bhutton-relative-json-pointer-00](https://datatracker.ietf.org/doc/html/draft-bhutton-relative-json-pointer-00).
///
/// Call [#parse(String)] to compile a relative pointer. Each operation also
/// takes the complete document and an absolute [Pointer] that selects the
/// starting value. [#get(Json, Pointer)] returns either the selected JSON
/// value or the member name or array index requested by `#`.
///
/// ```
/// var document = Json.parse("{\"foo\":[\"bar\",\"baz\"]}");
/// var from = Pointer.parse("/foo/1");
/// var previous = RelativePointer.parse("0-1").get(document, from);
/// var index = RelativePointer.parse("0#").get(document, from);
/// ```
///
/// Relative pointers are JSON string values. They are not URI fragments.
public final class RelativePointer {

    private final BigInteger up;
    private final BigInteger shift;
    private final boolean manipulatesIndex;
    private final boolean returnsIndex;
    private final Pointer tail;
    private final String text;

    private RelativePointer(BigInteger up, BigInteger shift, boolean manipulatesIndex,
            boolean returnsIndex, Pointer tail, String text) {
        this.up = up;
        this.shift = shift;
        this.manipulatesIndex = manipulatesIndex;
        this.returnsIndex = returnsIndex;
        this.tail = tail;
        this.text = text;
    }

    /// Parses a relative JSON pointer.
    ///
    /// The parser rejects leading zeroes and malformed index manipulation.
    public static RelativePointer parse(String pointer) {
        Objects.requireNonNull(pointer, "pointer");
        var prefixEnd = digits(pointer, 0);
        if (prefixEnd == 0 || leadingZero(pointer, 0, prefixEnd)) throw malformed(pointer);
        var up = new BigInteger(pointer.substring(0, prefixEnd));
        if (prefixEnd < pointer.length() && pointer.charAt(prefixEnd) == '#') {
            if (prefixEnd + 1 != pointer.length()) throw malformed(pointer);
            return new RelativePointer(up, BigInteger.ZERO, false, true, Pointer.root(), pointer);
        }

        var at = prefixEnd;
        var shift = BigInteger.ZERO;
        var manipulates = false;
        if (at < pointer.length() && (pointer.charAt(at) == '+' || pointer.charAt(at) == '-')) {
            manipulates = true;
            var sign = pointer.charAt(at++);
            var end = digits(pointer, at);
            if (end == at || leadingZero(pointer, at, end)) throw malformed(pointer);
            shift = new BigInteger(pointer.substring(at, end));
            if (sign == '-') shift = shift.negate();
            at = end;
        }
        var remainder = pointer.substring(at);
        if (!remainder.isEmpty() && remainder.charAt(0) != '/') throw malformed(pointer);
        return new RelativePointer(up, shift, manipulates, false, Pointer.parse(remainder), pointer);
    }

    /// Returns the selected value, member name, or array index.
    ///
    /// The starting pointer must select a concrete value. Moving above the
    /// root, moving outside an array, or requesting `#` at the root fails.
    public Json get(Json document, Pointer from) {
        var base = base(document, from);
        if (!returnsIndex) return append(base, tail).get(document);
        if (base.isRoot()) throw new JsonException("a relative JSON Pointer cannot return the root index: " + text);
        var parent = base.parent().orElseThrow().get(document);
        var token = base.last().orElseThrow();
        return switch (parent) {
            case Json.Object _ -> Json.of(token);
            case Json.Array(var items) -> Json.of(arrayIndex(items.size(), token));
            default -> throw new JsonException("relative JSON Pointer has no containing value: " + text);
        };
    }

    /// Returns the absolute pointer selected by this relative pointer.
    ///
    /// A relative pointer ending in `#` returns a name or index instead. This
    /// method rejects that form. Call [#get(Json, Pointer)] for it.
    public Pointer resolve(Json document, Pointer from) {
        if (returnsIndex) throw new JsonException("an index relative JSON Pointer has no target pointer: " + text);
        var resolved = append(base(document, from), tail);
        resolved.get(document);
        return resolved;
    }

    /// Returns a document with the relative location set to `replacement`.
    public Json set(Json document, Pointer from, Json replacement) {
        return resolve(document, from).set(document, replacement);
    }

    /// Applies `change` to the relative location and returns the edited document.
    public Json update(Json document, Pointer from, UnaryOperator<Json> change) {
        return resolve(document, from).update(document, change);
    }

    /// Returns a document without the relative location.
    public Json remove(Json document, Pointer from) {
        return resolve(document, from).remove(document);
    }

    @Override
    public String toString() {
        return text;
    }

    private Pointer base(Json document, Pointer from) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(from, "from").get(document);
        if (up.compareTo(BigInteger.valueOf(from.tokens().size())) > 0) {
            throw new JsonException("relative JSON Pointer moves above the root: " + text);
        }
        var kept = from.tokens().size() - up.intValueExact();
        var tokens = new ArrayList<>(from.tokens().subList(0, kept));
        var base = pointer(tokens);
        if (!manipulatesIndex) return base;
        if (base.isRoot()) throw new JsonException("relative JSON Pointer index manipulation needs an array item: " + text);
        var parent = base.parent().orElseThrow().get(document);
        if (!(parent instanceof Json.Array(var items))) {
            throw new JsonException("relative JSON Pointer index manipulation needs an array item: " + text);
        }
        var index = BigInteger.valueOf(arrayIndex(items.size(), base.last().orElseThrow())).add(shift);
        if (index.signum() < 0 || index.compareTo(BigInteger.valueOf(items.size())) >= 0) {
            throw new JsonException("relative JSON Pointer index is outside the array: " + text);
        }
        tokens.set(tokens.size() - 1, index.toString());
        return pointer(tokens);
    }

    private static Pointer append(Pointer base, Pointer tail) {
        var result = base;
        for (var token : tail.tokens()) result = result.append(token);
        return result;
    }

    private static Pointer pointer(ArrayList<String> tokens) {
        var pointer = Pointer.root();
        for (var token : tokens) pointer = pointer.append(token);
        return pointer;
    }

    private static int arrayIndex(int size, String token) {
        if (token.isEmpty() || token.length() > 1 && token.charAt(0) == '0') {
            throw new JsonException("invalid array index in relative JSON Pointer: " + token);
        }
        for (var character : token.toCharArray()) {
            if (!Character.isDigit(character)) throw new JsonException("invalid array index in relative JSON Pointer: " + token);
        }
        try {
            var index = Integer.parseInt(token);
            if (index >= size) throw new JsonException("array index is outside the array: " + token);
            return index;
        } catch (NumberFormatException tooLarge) {
            throw new JsonException("array index is outside the array: " + token);
        }
    }

    private static int digits(String value, int start) {
        var at = start;
        while (at < value.length() && value.charAt(at) >= '0' && value.charAt(at) <= '9') at++;
        return at;
    }

    private static boolean leadingZero(String value, int start, int end) {
        return end - start > 1 && value.charAt(start) == '0';
    }

    private static JsonException malformed(String pointer) {
        return new JsonException("invalid relative JSON Pointer: " + pointer);
    }
}
