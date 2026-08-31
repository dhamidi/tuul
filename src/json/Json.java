package json;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A JSON value.
///
/// Values are immutable and built by pattern matching, not by getters:
/// `if (value instanceof Json.Str(var text))`. Documents are read from a
/// [Reader] and written to a [Writer] — see [JsonReader] and [JsonWriter] for
/// the streaming interfaces underneath.
public sealed interface Json {

    Json NULL = new Null();
    Json TRUE = new Bool(true);
    Json FALSE = new Bool(false);

    record Null() implements Json {}

    record Bool(boolean value) implements Json {}

    record Num(double value) implements Json {}

    record Str(String value) implements Json {}

    record Array(List<Json> items) implements Json {

        public Array {
            items = List.copyOf(items);
        }

        public static Array of(List<Json> items) {
            return new Array(items);
        }

        public static Array strings(List<String> values) {
            return new Array(values.stream().map(Json::of).toList());
        }
    }

    /// A JSON object. Field order is the insertion order, so output reads the
    /// way it was built.
    record Object(Map<String, Json> fields) implements Json {

        public Object {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        public static Object of() {
            return new Object(Map.of());
        }

        /// Creates an object with one JSON field.
        public static Object of(String name, Json value) {
            return new Object(Map.of(name, value));
        }

        /// Creates an object with one string field.
        public static Object of(String name, String value) {
            return of(name, Json.of(value));
        }

        /// Creates an object with one boolean field.
        public static Object of(String name, boolean value) {
            return of(name, Json.of(value));
        }

        /// Creates an object with one numeric field.
        public static Object of(String name, double value) {
            return of(name, Json.of(value));
        }

        public Object with(String name, Json value) {
            var next = new LinkedHashMap<>(fields);
            next.put(name, value);
            return new Object(next);
        }

        public Object with(String name, String value) {
            return with(name, Json.of(value));
        }

        public Object with(String name, boolean value) {
            return with(name, Json.of(value));
        }

        public Object with(String name, double value) {
            return with(name, Json.of(value));
        }

        public Object without(String name) {
            var next = new LinkedHashMap<>(fields);
            next.remove(name);
            return new Object(next);
        }

        public Json get(String name) {
            return fields.get(name);
        }

        public String string(String name, String fallback) {
            return get(name) instanceof Str(var value) ? value : fallback;
        }

        public boolean flag(String name) {
            return get(name) instanceof Bool(var value) && value;
        }

        /// A numeric field, or the fallback when the field is missing or is
        /// not a number. JSON has one number type, so a count, an amount and a
        /// timestamp all arrive here.
        public double number(String name, double fallback) {
            return get(name) instanceof Num(var value) ? value : fallback;
        }

        /// The items of an array field, or an empty list when the field is
        /// missing or is not an array.
        public List<Json> list(String name) {
            return get(name) instanceof Array(var items) ? items : List.of();
        }
    }

    static Json of(String value) {
        return value == null ? NULL : new Str(value);
    }

    static Json of(double value) {
        return new Num(value);
    }

    static Json of(boolean value) {
        return value ? TRUE : FALSE;
    }

    static Json parse(Reader in) {
        return new JsonReader(in).readValue();
    }

    static Json parse(String text) {
        return parse(new StringReader(text));
    }

    /// Streams this value into `out` and flushes; the writer stays open.
    default void write(Writer out) throws IOException {
        new JsonWriter(out).value(this).flush();
    }

    /// Only for tests, messages and other places where the value is known to be
    /// small. Everything else writes to a [Writer].
    default String text() {
        var out = new StringWriter();
        try {
            new JsonWriter(out).value(this).flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }
}
