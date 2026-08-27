package json;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A pull parser over a [Reader]. [#next()] returns one [Event] at a time and
/// never holds more than the current token, so a document is consumed as it
/// arrives.
///
/// [#readValue()] is the convenience on top: it drains events into an in-memory
/// [Json] value, for callers who want the whole thing.
public final class JsonReader implements Closeable {

    /// One step of the document.
    public sealed interface Event {

        record BeginObject() implements Event {}

        record EndObject() implements Event {}

        record BeginArray() implements Event {}

        record EndArray() implements Event {}

        /// The name of the object field that the next value belongs to.
        record Name(String name) implements Event {}

        /// A scalar: null, a boolean, a number or a string.
        record Value(Json value) implements Event {}

        /// Input is exhausted.
        record End() implements Event {}
    }

    private static final Event BEGIN_OBJECT = new Event.BeginObject();
    private static final Event END_OBJECT = new Event.EndObject();
    private static final Event BEGIN_ARRAY = new Event.BeginArray();
    private static final Event END_ARRAY = new Event.EndArray();
    private static final Event END = new Event.End();
    private static final int NOTHING = -2;

    private final Reader in;
    private final Deque<Boolean> containers = new ArrayDeque<>();
    private int pushback = NOTHING;
    private boolean nameExpected;

    public JsonReader(Reader in) {
        this.in = in;
    }

    public Event next() {
        while (true) {
            var c = read();
            if (c == -1) return END;
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            if (c == ',') {
                nameExpected = Boolean.TRUE.equals(containers.peek());
                continue;
            }
            if (c == ':') {
                nameExpected = false;
                continue;
            }
            return token(c);
        }
    }

    /// Reads one complete value — scalar, array or object — from the current
    /// position.
    public Json readValue() {
        var open = new ArrayDeque<Object>();
        var names = new ArrayDeque<String>();
        while (true) {
            var done = switch (next()) {
                case Event.BeginObject _ -> push(open, new LinkedHashMap<String, Json>());
                case Event.BeginArray _ -> push(open, new ArrayList<Json>());
                case Event.Name(var name) -> {
                    names.push(name);
                    yield null;
                }
                case Event.Value(var value) -> attach(open, names, value);
                case Event.EndObject _ -> attach(open, names, object(open.pop()));
                case Event.EndArray _ -> attach(open, names, array(open.pop()));
                case Event.End _ -> throw new JsonException("unexpected end of input");
            };
            if (done != null) return done;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private Event token(int c) {
        return switch (c) {
            case '{' -> {
                containers.push(true);
                nameExpected = true;
                yield BEGIN_OBJECT;
            }
            case '[' -> {
                containers.push(false);
                nameExpected = false;
                yield BEGIN_ARRAY;
            }
            case '}' -> close(END_OBJECT);
            case ']' -> close(END_ARRAY);
            case '"' -> {
                var text = string();
                yield nameExpected ? new Event.Name(text) : new Event.Value(new Json.Str(text));
            }
            case 't' -> literal("rue", Json.TRUE);
            case 'f' -> literal("alse", Json.FALSE);
            case 'n' -> literal("ull", Json.NULL);
            default -> new Event.Value(number(c));
        };
    }

    private Event close(Event event) {
        containers.poll();
        nameExpected = false;
        return event;
    }

    private Event literal(String rest, Json value) {
        for (var i = 0; i < rest.length(); i++) {
            if (read() != rest.charAt(i)) throw new JsonException("malformed literal, expected " + rest);
        }
        return new Event.Value(value);
    }

    private Json number(int first) {
        var digits = new StringBuilder().append((char) first);
        while (true) {
            var c = read();
            if (c == -1) break;
            if (!isNumber(c)) {
                pushback = c;
                break;
            }
            digits.append((char) c);
        }
        try {
            return new Json.Num(Double.parseDouble(digits.toString()));
        } catch (NumberFormatException e) {
            throw new JsonException("not a number: " + digits, e);
        }
    }

    private static boolean isNumber(int c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    private String string() {
        var text = new StringBuilder();
        while (true) {
            var c = read();
            if (c == -1) throw new JsonException("unterminated string");
            if (c == '"') return text.toString();
            text.append(c == '\\' ? escape() : (char) c);
        }
    }

    private char escape() {
        var c = read();
        return switch (c) {
            case '"', '\\', '/' -> (char) c;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> unicode();
            default -> throw new JsonException("unknown escape: \\" + (char) c);
        };
    }

    private char unicode() {
        var digits = new char[4];
        for (var i = 0; i < 4; i++) digits[i] = (char) read();
        try {
            return (char) Integer.parseInt(new String(digits), 16);
        } catch (NumberFormatException e) {
            throw new JsonException("malformed \\u escape: " + new String(digits), e);
        }
    }

    private int read() {
        if (pushback != NOTHING) {
            var c = pushback;
            pushback = NOTHING;
            return c;
        }
        try {
            return in.read();
        } catch (IOException e) {
            throw new JsonException("cannot read: " + e.getMessage(), e);
        }
    }

    private static Json push(Deque<Object> open, Object container) {
        open.push(container);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Json object(Object container) {
        return new Json.Object((Map<String, Json>) container);
    }

    @SuppressWarnings("unchecked")
    private static Json array(Object container) {
        return new Json.Array((List<Json>) container);
    }

    /// Adds a finished value to the container that encloses it, or returns it
    /// when there is no enclosing container — that value is the document.
    @SuppressWarnings("unchecked")
    private static Json attach(Deque<Object> open, Deque<String> names, Json value) {
        var container = open.peek();
        if (container == null) return value;
        if (container instanceof List<?> items) ((List<Json>) items).add(value);
        else ((Map<String, Json>) container).put(names.pop(), value);
        return null;
    }
}
