package tcl;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// Converts JVM objects only when a Tcl operation requires a specific form.
public final class Values {

    private Values() {}

    /// Returns the interpolation form of a JVM object.
    public static String string(Object value) {
        return switch (value) {
            case null -> "";
            case String text -> text;
            case Boolean flag -> flag ? "1" : "0";
            case Byte n -> Byte.toString(n);
            case Short n -> Short.toString(n);
            case Integer n -> Integer.toString(n);
            case Long n -> Long.toString(n);
            case BigInteger n -> n.toString();
            case Float n -> Float.toString(n);
            case Double n -> Double.toString(n);
            case BigDecimal n -> n.toPlainString();
            case List<?> list -> formatList(list);
            case Map<?, ?> map -> {
                var words = new ArrayList<>();
                map.forEach((key, item) -> {
                    words.add(key);
                    words.add(item);
                });
                yield formatList(words);
            }
            default -> String.valueOf(value);
        };
    }

    /// Returns the Tcl truth value of an accepted JVM value.
    public static boolean bool(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) return number.doubleValue() != 0.0;
        if (!(value instanceof String text)) throw error("expected boolean value but got " + type(value));
        return switch (text.strip().toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off", "" -> false;
            default -> {
                try {
                    yield numeric(text) != 0.0;
                } catch (NumberFormatException | TclException e) {
                    throw error("expected boolean value but got \"" + text + "\"");
                }
            }
        };
    }

    /// Returns a signed 64-bit integer.
    public static long integer(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger integer) {
            try {
                return integer.longValueExact();
            } catch (ArithmeticException e) {
                throw error("integer value is too large: " + integer);
            }
        }
        if (value instanceof Number number) {
            var result = number.doubleValue();
            if (Double.isFinite(result) && result == Math.rint(result)
                    && result >= Long.MIN_VALUE && result <= Long.MAX_VALUE) return (long) result;
        }
        try {
            return Long.decode(string(value).strip());
        } catch (NumberFormatException e) {
            throw error("expected integer but got \"" + string(value) + "\"");
        }
    }

    /// Returns a double-precision number.
    public static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return numeric(string(value));
        } catch (NumberFormatException e) {
            throw error("expected number but got \"" + string(value) + "\"");
        }
    }

    /// Returns a list without changing its elements.
    public static List<Object> list(Object value) {
        if (value instanceof Stream<?>) throw error("a Stream is not a Tcl list. Call toList first.");
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            var same = (List<Object>) list;
            return same;
        }
        if (value instanceof String text) return ListParser.parse(text);
        throw error("expected list but got " + type(value));
    }

    /// Returns a dictionary with insertion order.
    public static Map<Object, Object> dict(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            var same = (Map<Object, Object>) map;
            return same;
        }
        var list = list(value);
        if ((list.size() & 1) != 0) throw error("expected an even number of dictionary elements");
        var result = new LinkedHashMap<Object, Object>();
        for (var index = 0; index < list.size(); index += 2) result.put(list.get(index), list.get(index + 1));
        return result;
    }

    /// Returns a parsed script or a script made from command lists.
    public static Script script(Object value) {
        if (value instanceof Script script) return script;
        if (value instanceof String text) return Script.parse(text);
        if (value instanceof List<?> commands && commands.stream().allMatch(List.class::isInstance)) {
            @SuppressWarnings("unchecked")
            var lists = (List<? extends List<?>>) commands;
            return Script.lists(lists);
        }
        throw error("expected script but got " + type(value));
    }

    private static String formatList(List<?> values) {
        var result = new StringBuilder();
        for (var value : values) {
            if (!result.isEmpty()) result.append(' ');
            result.append(quote(string(value)));
        }
        return result.toString();
    }

    private static String quote(String value) {
        if (value.isEmpty()) return "{}";
        var simple = value.chars().noneMatch(c -> Character.isWhitespace(c)
                || "{}[]$;\\\"".indexOf(c) >= 0 || c == '#');
        if (simple) return value;
        if (balanced(value) && value.indexOf("\\\n") < 0) return "{" + value + "}";
        var result = new StringBuilder();
        for (var character : value.toCharArray()) {
            switch (character) {
                case ' ', '\t', '\r', '\n', '{', '}', '[', ']', '$', ';', '\\', '"' -> result.append('\\');
                default -> { }
            }
            result.append(character);
        }
        return result.toString();
    }

    private static boolean balanced(String value) {
        var depth = 0;
        var escaped = false;
        for (var character : value.toCharArray()) {
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '{') depth++;
            if (character == '}' && --depth < 0) return false;
        }
        return depth == 0;
    }

    private static String type(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static double numeric(String value) {
        var text = value.strip();
        try {
            return Long.decode(text);
        } catch (NumberFormatException ignored) {
            return Double.parseDouble(text);
        }
    }

    static TclException.Error error(String message) {
        return new TclException.Error(message);
    }

    private static final class ListParser {

        private final String source;
        private int at;

        private ListParser(String source) {
            this.source = source;
        }

        static List<Object> parse(String source) {
            return new ListParser(source).parse();
        }

        private List<Object> parse() {
            var result = new ArrayList<Object>();
            while (true) {
                blanks();
                if (at == source.length()) return result;
                result.add(word());
                if (at < source.length() && !Character.isWhitespace(source.charAt(at))) {
                    throw error("A space must follow the list element. Found \"" + source.charAt(at) + "\".");
                }
            }
        }

        private String word() {
            return switch (source.charAt(at)) {
                case '{' -> braced();
                case '"' -> quoted();
                default -> bare();
            };
        }

        private String braced() {
            at++;
            var result = new StringBuilder();
            var depth = 1;
            while (at < source.length()) {
                var character = source.charAt(at++);
                if (character == '\\' && at < source.length()) {
                    if (source.charAt(at) == '\n') {
                        at++;
                        while (at < source.length() && (source.charAt(at) == ' ' || source.charAt(at) == '\t')) at++;
                        result.append(' ');
                    } else {
                        result.append(character).append(source.charAt(at++));
                    }
                    continue;
                }
                if (character == '{') depth++;
                if (character == '}' && --depth == 0) return result.toString();
                result.append(character);
            }
            throw error("unclosed brace in list");
        }

        private String quoted() {
            at++;
            var result = new StringBuilder();
            while (at < source.length() && source.charAt(at) != '"') appendEscaped(result);
            if (at == source.length()) throw error("unclosed quote in list");
            at++;
            return result.toString();
        }

        private String bare() {
            var result = new StringBuilder();
            while (at < source.length() && !Character.isWhitespace(source.charAt(at))) appendEscaped(result);
            return result.toString();
        }

        private void appendEscaped(StringBuilder result) {
            var character = source.charAt(at++);
            if (character != '\\' || at == source.length()) {
                result.append(character);
                return;
            }
            var next = source.charAt(at++);
            if (next == '\n') {
                while (at < source.length() && (source.charAt(at) == ' ' || source.charAt(at) == '\t')) at++;
                result.append(' ');
                return;
            }
            var simple = switch (next) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'a' -> '\u0007';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'v' -> '\u000b';
                default -> next;
            };
            if (next == 'x') simple = digits(16, 2, 'x');
            else if (next == 'u') simple = digits(16, 4, 'u');
            else if (next >= '0' && next <= '7') {
                at--;
                simple = digits(8, 3, '0');
            }
            result.append(simple);
        }

        private char digits(int radix, int limit, char fallback) {
            var start = at;
            var value = 0;
            while (at < source.length() && at - start < limit) {
                var digit = Character.digit(source.charAt(at), radix);
                if (digit < 0) break;
                value = value * radix + digit;
                at++;
            }
            return at == start ? fallback : (char) value;
        }

        private void blanks() {
            while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++;
        }
    }
}
