package settings;

import java.util.List;

final class Pointer {

    private Pointer() {}

    static List<String> absolute(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            throw new IllegalArgumentException("a settings pointer must be absolute and non-empty: " + pointer);
        }
        try {
            return json.Pointer.parse(pointer).tokens();
        } catch (json.JsonException invalid) {
            throw new IllegalArgumentException(invalid.getMessage(), invalid);
        }
    }

    static List<String> relative(String pointer) {
        try {
            return json.Pointer.parse(pointer).tokens();
        } catch (NullPointerException | json.JsonException invalid) {
            throw new IllegalArgumentException("a relative settings pointer must be empty or start with '/': " + pointer,
                    invalid);
        }
    }

    static String join(String namespace, String relative) {
        var joined = json.Pointer.root().append(namespace);
        for (var token : relative(relative)) joined = joined.append(token);
        return joined.toString();
    }

    static String render(List<String> tokens) {
        var result = json.Pointer.root();
        for (var token : tokens) result = result.append(token);
        return result.toString();
    }

    static boolean overlaps(List<String> left, List<String> right) {
        return prefix(left, right) || prefix(right, left);
    }

    static boolean prefix(List<String> prefix, List<String> path) {
        return prefix.size() <= path.size() && java.util.stream.IntStream.range(0, prefix.size())
                .allMatch(index -> prefix.get(index).equals(path.get(index)));
    }
}
