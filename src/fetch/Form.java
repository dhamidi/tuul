package fetch;

import java.util.LinkedHashMap;
import java.util.Map;

/// Immutable repeated fields for an `application/x-www-form-urlencoded` body.
///
/// A field can have zero or more values. [Body#form(Form)] writes one encoded
/// field pair for each value, in map order and then array order.
public final class Form {
    private final Map<String, String[]> fields;

    /// Creates an immutable form from `fields`.
    ///
    /// The constructor copies the map and every value array. Field names,
    /// arrays, and array elements must not be null.
    public Form(Map<String, String[]> fields) { this.fields = copy(fields); }

    /// Creates a form from a map whose values are `String` or `String[]`.
    ///
    /// A `String` becomes one field value. A `String[]` preserves all values.
    /// Any other value type throws `IllegalArgumentException`.
    public static Form of(Map<String, ?> fields) {
        var result = new LinkedHashMap<String, String[]>();
        fields.forEach((name, value) -> {
            if (value instanceof String text) result.put(name, new String[] {text});
            else if (value instanceof String[] texts) result.put(name, texts);
            else throw new IllegalArgumentException("form value must be String or String[]: " + name);
        });
        return new Form(result);
    }

    /// Creates a form with one field and the supplied values.
    ///
    /// An empty `values` array creates a field that writes no pair.
    public static Form of(String name, String... values) { return new Form(Map.of(name, values)); }

    /// Returns a new form with `name` mapped to a copy of `values`.
    ///
    /// The original form remains unchanged. The field name and values must not
    /// be null.
    public Form with(String name, String... values) { var result = copy(fields); result.put(name, values.clone()); return new Form(result); }

    /// Returns a deep copy of the field map and its value arrays.
    ///
    /// Changes to the returned map or arrays do not change this form.
    public Map<String, String[]> fields() { return copy(fields); }

    private static LinkedHashMap<String, String[]> copy(Map<String, String[]> source) {
        var result = new LinkedHashMap<String, String[]>();
        source.forEach((name, values) -> {
            if (name == null || values == null) throw new NullPointerException("form field");
            var copied = values.clone();
            for (var value : copied) if (value == null) throw new NullPointerException("form value");
            result.put(name, copied);
        });
        return result;
    }
}
