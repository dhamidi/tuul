package settings;

import java.util.ArrayList;
import java.util.List;

final class Pointer {

    private Pointer() {}

    static List<String> absolute(String pointer) {
        if (pointer == null || pointer.isEmpty() || pointer.charAt(0) != '/') {
            throw new IllegalArgumentException("a settings pointer must be absolute and non-empty: " + pointer);
        }
        return parse(pointer);
    }

    static List<String> relative(String pointer) {
        if (pointer == null || (!pointer.isEmpty() && pointer.charAt(0) != '/')) {
            throw new IllegalArgumentException("a relative settings pointer must be empty or start with '/': " + pointer);
        }
        return pointer.isEmpty() ? List.of() : parse(pointer);
    }

    static String join(String namespace, String relative) {
        return "/" + escape(namespace) + relative;
    }

    static String render(List<String> tokens) {
        var result = new StringBuilder();
        for (var token : tokens) result.append('/').append(escape(token));
        return result.toString();
    }

    static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    static boolean overlaps(List<String> left, List<String> right) {
        return prefix(left, right) || prefix(right, left);
    }

    static boolean prefix(List<String> prefix, List<String> path) {
        return prefix.size() <= path.size() && java.util.stream.IntStream.range(0, prefix.size())
                .allMatch(index -> prefix.get(index).equals(path.get(index)));
    }

    private static List<String> parse(String pointer) {
        var tokens = new ArrayList<String>();
        for (var part : pointer.substring(1).split("/", -1)) {
            var token = new StringBuilder();
            for (var index = 0; index < part.length(); index++) {
                var character = part.charAt(index);
                if (character != '~') {
                    token.append(character);
                    continue;
                }
                if (++index >= part.length()) throw new IllegalArgumentException("invalid JSON Pointer escape: " + pointer);
                var escaped = part.charAt(index);
                if (escaped == '0') token.append('~');
                else if (escaped == '1') token.append('/');
                else throw new IllegalArgumentException("invalid JSON Pointer escape: " + pointer);
            }
            tokens.add(token.toString());
        }
        return List.copyOf(tokens);
    }
}
