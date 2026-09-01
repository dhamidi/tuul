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
import java.util.Optional;
import java.util.function.UnaryOperator;

/// A JSON value.
///
/// Values are immutable. Documents are read from a [Reader] and written to a
/// [Writer]. [JsonReader] and [JsonWriter] provide the streaming interfaces.
///
/// Call [#at(String)] to extract a value with an RFC 6901 JSON Pointer. Call
/// [#set(String, Json)], [#update(String, UnaryOperator)], or [#remove(String)]
/// to return an edited document. No operation changes its input.
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

    /// Returns the value selected by an RFC 6901 JSON Pointer.
    ///
    /// An empty pointer returns this value. A missing member, invalid array
    /// index, or scalar traversal throws [JsonException]. Use [#find(String)]
    /// when a missing value is expected.
    default Json at(String pointer) {
        return Pointer.parse(pointer).get(this);
    }

    /// Returns the value selected by a compiled pointer.
    default Json at(Pointer pointer) {
        return pointer.get(this);
    }

    /// Returns the selected value, or an empty result when it does not exist.
    default Optional<Json> find(String pointer) {
        return Pointer.parse(pointer).find(this);
    }

    /// Returns the selected value, or an empty result when it does not exist.
    default Optional<Json> find(Pointer pointer) {
        return pointer.find(this);
    }

    /// Returns a selection that retains this document and an absolute location.
    ///
    /// Use [Selection#at(String)] to evaluate Relative JSON Pointers from that
    /// location. The absolute pointer must select a concrete value.
    default Selection select(String pointer) {
        return new Selection(this, Pointer.parse(pointer));
    }

    /// Returns a selection from a compiled absolute pointer.
    default Selection select(Pointer pointer) {
        return new Selection(this, pointer);
    }

    /// Returns a document with `replacement` at `pointer`.
    ///
    /// This method creates missing object parents. It appends to an existing
    /// array when the final token is `-`. It does not create an inferred array.
    default Json set(String pointer, Json replacement) {
        return Pointer.parse(pointer).set(this, replacement);
    }

    /// Returns a document with `replacement` at a compiled pointer.
    default Json set(Pointer pointer, Json replacement) {
        return pointer.set(this, replacement);
    }

    /// Returns a document with a string at `pointer`.
    default Json set(String pointer, String replacement) {
        return set(pointer, Json.of(replacement));
    }

    /// Returns a document with a boolean at `pointer`.
    default Json set(String pointer, boolean replacement) {
        return set(pointer, Json.of(replacement));
    }

    /// Returns a document with a number at `pointer`.
    default Json set(String pointer, double replacement) {
        return set(pointer, Json.of(replacement));
    }

    /// Returns a document with each string pointer set in map iteration order.
    ///
    /// A later set sees all earlier sets. Use an ordered map when pointers
    /// overlap and order changes the result.
    default Json set(Map<String, ? extends Json> replacements) {
        var changed = this;
        for (var replacement : replacements.entrySet()) {
            changed = changed.set(replacement.getKey(), replacement.getValue());
        }
        return changed;
    }

    /// Applies `change` to the selected value and returns the edited document.
    ///
    /// The selected value must exist. The function must return a JSON value.
    default Json update(String pointer, UnaryOperator<Json> change) {
        return Pointer.parse(pointer).update(this, change);
    }

    /// Applies `change` at a compiled pointer and returns the edited document.
    default Json update(Pointer pointer, UnaryOperator<Json> change) {
        return pointer.update(this, change);
    }

    /// Returns a document without the selected object member or array item.
    ///
    /// The selected value must exist. The root cannot be removed.
    default Json remove(String pointer) {
        return Pointer.parse(pointer).remove(this);
    }

    /// Returns a document without the value at a compiled pointer.
    default Json remove(Pointer pointer) {
        return pointer.remove(this);
    }

    /// Returns this string value, or `fallback` when this is another JSON type.
    default String string(String fallback) {
        return this instanceof Str(var value) ? value : fallback;
    }

    /// Returns this numeric value, or `fallback` when this is another JSON type.
    default double number(double fallback) {
        return this instanceof Num(var value) ? value : fallback;
    }

    /// Returns this boolean value, or false when this is another JSON type.
    default boolean flag() {
        return this instanceof Bool(var value) && value;
    }

    /// Returns this array's items, or an empty list when this is another JSON type.
    default List<Json> list() {
        return this instanceof Array(var items) ? items : List.of();
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
