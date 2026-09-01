package fetch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// An immutable collection of HTTP header fields.
///
/// Header names compare without regard to case. A name can have several
/// values, and each value keeps its insertion order. The constructor copies
/// the map and lists, so later caller changes do not change this object.
///
/// The constructor preserves map keys that differ only by case. Lookup methods
/// use the first matching key. Mutation methods replace matching keys with one
/// key.
public record Headers(
        /// The immutable header map, with each name mapped to its ordered values.
        Map<String, List<String>> values) {
    /// An empty immutable header set.
    public static final Headers NONE = new Headers(Map.of());

    /// Creates an immutable header set by copying `values` and every value list.
    ///
    /// Names must be nonblank. Names and values must not contain carriage
    /// return, line feed, or NUL characters.
    public Headers {
        var copy = new LinkedHashMap<String, List<String>>();
        values.forEach((name, entries) -> {
            entries.forEach(Headers::checkValue);
            copy.put(checkName(name), List.copyOf(entries));
        });
        values = Collections.unmodifiableMap(copy);
    }

    /// Returns the immutable header map.
    @Override
    public Map<String, List<String>> values() { return values; }

    /// Returns the empty immutable header set.
    public static Headers of() { return NONE; }

    /// Returns a header set containing one value for `name`.
    public static Headers of(String name, String value) { return NONE.with(name, value); }

    /// Returns the first value for `name`, or an empty result when the name is absent.
    ///
    /// If several stored names differ only by case, this method uses the first stored entry.
    public Optional<String> first(String name) {
        var found = entry(name);
        return found == null || found.getValue().isEmpty() ? Optional.empty() : Optional.of(found.getValue().getFirst());
    }

    /// Returns the first value for `name`, or `fallback` when the name is absent.
    public String first(String name, String fallback) { return first(name).orElse(fallback); }

    /// Returns all values for `name` in insertion order, or an empty list when the name is absent.
    public List<String> all(String name) { var found = entry(name); return found == null ? List.of() : found.getValue(); }

    /// Returns whether a stored header name equals `name` without regard to case.
    ///
    /// A stored name with an empty value list still returns `true`.
    public boolean has(String name) { return entry(name) != null; }

    /// Returns the header names in their stored order.
    ///
    /// The returned set is unmodifiable. Names retain the spelling used when they were stored.
    public Set<String> names() { return values.keySet(); }

    /// Returns a new header set with all values for `name` replaced by `value`.
    ///
    /// The original header set remains unchanged. The new entry is last in
    /// stored order.
    public Headers with(String name, String value) {
        checkValue(value);
        var copy = mutableWithout(name);
        copy.put(checkName(name), List.of(value));
        return new Headers(copy);
    }

    /// Returns a new header set with `value` appended to the values for `name`.
    ///
    /// The original header set remains unchanged. When several keys differ
    /// only by case, this method keeps the first key and its values. The new
    /// entry is last in stored order.
    public Headers add(String name, String value) {
        checkValue(value);
        var copy = mutableWithout(name);
        var entries = new ArrayList<>(all(name));
        entries.add(value);
        copy.put(existingName(name), entries);
        return new Headers(copy);
    }

    /// Returns a new header set without any values whose name equals `name` case-insensitively.
    public Headers without(String name) { return new Headers(mutableWithout(name)); }

    private Map.Entry<String, List<String>> entry(String name) {
        return values.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private String existingName(String name) { var found = entry(name); return found == null ? checkName(name) : found.getKey(); }
    private LinkedHashMap<String, List<String>> mutableWithout(String name) {
        var copy = new LinkedHashMap<String, List<String>>();
        values.forEach((key, value) -> { if (!key.equalsIgnoreCase(name)) copy.put(key, value); });
        return copy;
    }

    private static String checkName(String value) { checkLine(value); if (value.isBlank()) throw new IllegalArgumentException("empty header name"); return value; }
    private static void checkValue(String value) { checkLine(value); }
    private static void checkLine(String value) {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf(0) >= 0) throw new IllegalArgumentException("invalid header");
    }
}
