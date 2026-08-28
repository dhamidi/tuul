package markdown;

import java.util.Map;

/// Link reference definitions — `[label]: /url "title"`.
///
/// They are read off the front of a paragraph when it closes, because that is
/// the only place CommonMark allows one, and a paragraph that was nothing else
/// stops being a paragraph. The definition stays in the tree: a document that
/// contains one contains one, and a reader of the DOM should be able to see
/// what was written. [Html] skips it, which is where "renders as nothing"
/// belongs.
///
/// A definition that turns out to be malformed is not one, and the paragraph
/// keeps its text — so `[foo]: ` on its own is a paragraph saying `[foo]:`.
final class Definitions {

    private final String source;
    private final Nodes nodes;
    private final Map<String, Integer> definitions;

    Definitions(String source, Nodes nodes, Map<String, Integer> definitions) {
        this.source = source;
        this.nodes = nodes;
        this.definitions = definitions;
    }

    /// Reads one definition from the front of `content`, answering how many
    /// lines it took. Zero means there was not one, and the paragraph keeps
    /// everything.
    int read(int paragraph, Segments content) {
        var at = skip(content, 0);
        if (at >= content.length() || content.charAt(at) != '[') return 0;
        var label = label(content, at);
        if (label < 0) return 0;
        var name = Labels.normalize(content.subSequence(at + 1, label));
        if (name.isEmpty()) return 0;
        at = label + 1;
        if (at >= content.length() || content.charAt(at) != ':') return 0;
        at = skip(content, at + 1);

        var destination = destination(content, at);
        if (destination < 0) return 0;
        var destinationStart = at;
        at = destination;

        var afterDestination = at;
        var titleStart = skip(content, at);
        var title = titleStart > at || newlineBetween(content, at, titleStart) ? title(content, titleStart) : -1;
        if (title > 0 && !blank(content, title)) {
            title = -1;
        }
        var end = title > 0 ? title : afterDestination;
        if (title < 0 && !blank(content, afterDestination)) return 0;

        var node = nodes.open(Kind.DEFINITION, paragraph, content.source(0));
        nodes.end(node, content.source(end));
        var url = nodes.open(Kind.DESTINATION, node, content.source(destinationStart));
        nodes.end(url, content.source(afterDestination));
        if (title > 0) {
            var text = nodes.open(Kind.TITLE, node, content.source(titleStart + 1));
            nodes.end(text, content.source(title - 1));
        }
        definitions.putIfAbsent(name, node);

        var lines = lines(content, end);
        content.skip(lines);
        return lines;
    }

    /// A label runs to its closing bracket, may not hold an unescaped one, and
    /// stops at 999 characters — the specification's limit, which exists so
    /// that a document cannot be made to scan forever.
    private int label(Segments content, int open) {
        for (var at = open + 1; at < content.length() && at - open <= 1000; at++) {
            var character = content.charAt(at);
            if (character == '\\') {
                at++;
                continue;
            }
            if (character == '[') return -1;
            if (character == ']') return at;
        }
        return -1;
    }

    private int destination(Segments content, int start) {
        if (start >= content.length()) return -1;
        if (content.charAt(start) == '<') {
            for (var at = start + 1; at < content.length(); at++) {
                var character = content.charAt(at);
                if (character == '\\') {
                    at++;
                    continue;
                }
                if (character == '\n' || character == '<') return -1;
                if (character == '>') return at + 1;
            }
            return -1;
        }
        var depth = 0;
        var at = start;
        while (at < content.length()) {
            var character = content.charAt(at);
            if (character == '\\' && at + 1 < content.length()) {
                at += 2;
                continue;
            }
            if (character == '(') depth++;
            if (character == ')') {
                if (depth == 0) break;
                depth--;
            }
            if (Characters.whitespace(character) || character < ' ') break;
            at++;
        }
        return at == start || depth != 0 ? -1 : at;
    }

    private int title(Segments content, int start) {
        if (start >= content.length()) return -1;
        var open = content.charAt(start);
        var close = open == '(' ? ')' : open;
        if (open != '"' && open != '\'' && open != '(') return -1;
        for (var at = start + 1; at < content.length(); at++) {
            var character = content.charAt(at);
            if (character == '\\') {
                at++;
                continue;
            }
            if (character == open && open == '(') return -1;
            if (character == close) return at + 1;
        }
        return -1;
    }

    private boolean blank(Segments content, int from) {
        for (var at = from; at < content.length(); at++) {
            var character = content.charAt(at);
            if (character == '\n') return true;
            if (!Characters.space(character)) return false;
        }
        return true;
    }

    private boolean newlineBetween(Segments content, int from, int to) {
        for (var at = from; at < to; at++) {
            if (content.charAt(at) == '\n') return true;
        }
        return false;
    }

    /// How many lines of the content a position has consumed, counting the one
    /// it is in.
    private int lines(Segments content, int position) {
        var at = 0;
        for (var line = 0; line < content.lines(); line++) {
            at += content.lineEnd(line) - content.lineStart(line);
            if (position <= at) return line + 1;
            at++;
        }
        return content.lines();
    }

    /// Whitespace, including at most one newline: a definition may put its
    /// title on the line after its destination, and no further.
    private int skip(Segments content, int from) {
        var newlines = 0;
        var at = from;
        while (at < content.length()) {
            var character = content.charAt(at);
            if (character == '\n') {
                if (++newlines > 1) return at;
            } else if (!Characters.space(character)) {
                return at;
            }
            at++;
        }
        return at;
    }
}
