package web;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import json.Json;

/// Named values arriving from a client: a query string, or a form.
///
/// The two are the same format and the same problems — a name may repeat, order
/// matters for the ones that do, and everything is percent-encoded with `+`
/// standing in for a space — so they are the same type here. `web.forms` reads
/// a form as one of these, and a message carries one as JSON.
public record Parameters(Map<String, List<String>> values) {

    public static final Parameters NONE = new Parameters(Map.of());

    public Parameters {
        var copy = new LinkedHashMap<String, List<String>>();
        values.forEach((name, list) -> copy.put(name, List.copyOf(list)));
        values = Collections.unmodifiableMap(copy);
    }

    public static Parameters of() {
        return NONE;
    }

    /// Reads `a=1&b=2&b=3`. A name with no `=` is present with an empty value,
    /// because `?debug` means something and losing it would be a lie.
    public static Parameters parse(String query) {
        if (query == null || query.isBlank()) return NONE;
        var values = new LinkedHashMap<String, List<String>>();
        for (var pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            var split = pair.indexOf('=');
            var name = decode(split < 0 ? pair : pair.substring(0, split));
            var value = split < 0 ? "" : decode(pair.substring(split + 1));
            values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return new Parameters(values);
    }

    public Optional<String> first(String name) {
        return all(name).stream().findFirst();
    }

    public String first(String name, String fallback) {
        return first(name).orElse(fallback);
    }

    public List<String> all(String name) {
        return values.getOrDefault(name, List.of());
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public Set<String> names() {
        return values.keySet();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Parameters with(String name, String value) {
        var next = new LinkedHashMap<>(values);
        next.put(name, List.of(value));
        return new Parameters(next);
    }

    /// Everything here, and then everything there — the later one wins, which is
    /// how a path variable beats a query parameter of the same name.
    public Parameters and(Parameters other) {
        var next = new LinkedHashMap<>(values);
        next.putAll(other.values());
        return new Parameters(next);
    }

    /// As a JSON object, which is how a request reaches an application: one
    /// value is a string, several are an array. A parameter that repeats is
    /// rare enough that flattening it would be surprising, and common enough
    /// that dropping the rest would be wrong.
    public Json.Object json() {
        var object = Json.Object.of();
        for (var entry : values.entrySet()) {
            object = entry.getValue().size() == 1
                    ? object.with(entry.getKey(), entry.getValue().getFirst())
                    : object.with(entry.getKey(), Json.Array.strings(entry.getValue()));
        }
        return object;
    }

    /// Back to a query string, for a redirect that has to keep them.
    public String encoded() {
        var query = new StringBuilder();
        values.forEach((name, list) -> list.forEach(value -> {
            if (!query.isEmpty()) query.append('&');
            query.append(encode(name)).append('=').append(encode(value));
        }));
        return query.toString();
    }

    private static String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
