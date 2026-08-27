package web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The headers of a request or a response.
///
/// A header name has no case — `Content-Type` and `content-type` are the same
/// header — and one name may carry several values, which is why `Set-Cookie`
/// cannot be an entry in a plain map. Both of those are the usual way a
/// hand-rolled framework grows a bug nobody can reproduce, so names are matched
/// without regard to case and values keep their order.
///
/// The spelling a caller used is kept for the wire, because a header written as
/// `Content-Type` should arrive as `Content-Type` even though nothing is
/// entitled to care.
public record Headers(Map<String, List<String>> values) {

    public static final Headers NONE = new Headers(Map.of());

    public Headers {
        var copy = new LinkedHashMap<String, List<String>>();
        values.forEach((name, list) -> copy.put(name, List.copyOf(list)));
        values = Collections.unmodifiableMap(copy);
    }

    public static Headers of() {
        return NONE;
    }

    public static Headers of(String name, String value) {
        return NONE.with(name, value);
    }

    /// The first value under this name, which is what all but a handful of
    /// headers ever have.
    public Optional<String> first(String name) {
        return all(name).stream().findFirst();
    }

    public String first(String name, String fallback) {
        return first(name).orElse(fallback);
    }

    public List<String> all(String name) {
        return entry(name).map(values::get).orElseGet(List::of);
    }

    public boolean has(String name) {
        return entry(name).isPresent();
    }

    /// Replaces whatever was under this name, however it was spelled.
    public Headers with(String name, String value) {
        return replace(name, List.of(value));
    }

    /// Adds a value beside whatever is already there — `Set-Cookie` and the
    /// other headers that genuinely repeat.
    public Headers add(String name, String value) {
        var existing = new ArrayList<>(all(name));
        existing.add(value);
        return replace(name, existing);
    }

    public Headers without(String name) {
        return replace(name, List.of());
    }

    /// Every name, as it was spelled.
    public Set<String> names() {
        return values.keySet();
    }

    private Headers replace(String name, List<String> replacement) {
        var next = new LinkedHashMap<String, List<String>>();
        var spelling = entry(name).orElse(name);
        values.forEach((existing, list) -> {
            if (!existing.equals(spelling)) next.put(existing, list);
            else if (!replacement.isEmpty()) next.put(spelling, replacement);
        });
        if (!next.containsKey(spelling) && !replacement.isEmpty()) next.put(spelling, replacement);
        return new Headers(next);
    }

    private Optional<String> entry(String name) {
        var wanted = fold(name);
        return values.keySet().stream().filter(existing -> fold(existing).equals(wanted)).findFirst();
    }

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
