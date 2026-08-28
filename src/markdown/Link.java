package markdown;

/// The part after a link's text: `(/url "title")`, or the label of a reference.
///
/// It answers with spans rather than strings, so a destination stays a view of
/// the document until somebody writes it out. The three reference shapes —
/// full `[text][label]`, collapsed `[text][]` and shortcut `[text]` — differ
/// only in where the label is, so they are one method.
final class Link {

    private final Segments content;

    Link(Segments content) {
        this.content = content;
    }

    record Inline(int destinationStart, int destinationEnd, int titleStart, int titleEnd, int end) {}

    record Reference(int labelStart, int labelEnd, int end) {}

    /// `(/url "title")`, starting at the paren.
    Inline inline(int open) {
        var at = spaces(open + 1);
        var destinationStart = at;
        var destinationEnd = at;
        if (at < content.length() && content.charAt(at) != ')') {
            var angled = content.charAt(at) == '<';
            var end = destination(at);
            if (end < 0) return null;
            destinationStart = angled ? at + 1 : at;
            destinationEnd = angled ? end - 1 : end;
            at = end;
        }
        var afterDestination = at;
        at = spaces(at);
        var titleStart = -1;
        var titleEnd = -1;
        if (at > afterDestination) {
            var title = title(at);
            if (title > 0) {
                titleStart = at + 1;
                titleEnd = title - 1;
                at = spaces(title);
            }
        }
        if (at >= content.length() || content.charAt(at) != ')') return null;
        return new Inline(destinationStart, destinationEnd, titleStart, titleEnd, at + 1);
    }

    /// A reference, in whichever of its three shapes. `text` is where the link
    /// text began and `close` is its `]`; `after` is what follows it.
    Reference reference(int text, int close, int after) {
        if (after < content.length() && content.charAt(after) == '[') {
            var end = label(after);
            if (end < 0) return null;
            if (end == after + 1) return new Reference(text, close, end + 1);
            return new Reference(after + 1, end, end + 1);
        }
        return new Reference(text, close, after);
    }

    private int destination(int start) {
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
            else if (character == ')') {
                if (depth == 0) break;
                depth--;
            } else if (Characters.whitespace(character) || character < ' ') {
                break;
            }
            at++;
        }
        return depth == 0 ? at : -1;
    }

    private int title(int start) {
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
            if (character == close) return at + 1;
        }
        return -1;
    }

    private int label(int open) {
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

    private int spaces(int from) {
        var at = from;
        while (at < content.length() && Characters.whitespace(content.charAt(at))) at++;
        return at;
    }
}
