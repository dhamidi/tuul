package markdown;

/// Raw HTML, recognised but not interpreted.
///
/// CommonMark passes HTML through — a tag, a comment, a processing
/// instruction, a declaration, CDATA — and a markdown implementation that drops
/// it is not one. This is the scanner both phases share: block level asks
/// whether a line begins one, inline level asks how far one runs.
///
/// Nothing here validates HTML. A tag is a shape, and whether the shape means
/// anything is the browser's business.
final class Tags {

    private Tags() {}

    /// How far the HTML starting at `pos` runs, or -1 if nothing does. Covers
    /// all six shapes the specification lists.
    static int scan(CharSequence text, int pos) {
        if (pos >= text.length() || text.charAt(pos) != '<') return -1;
        var after = comment(text, pos);
        if (after < 0) after = processing(text, pos);
        if (after < 0) after = cdata(text, pos);
        if (after < 0) after = declaration(text, pos);
        if (after < 0) after = closing(text, pos);
        if (after < 0) after = open(text, pos);
        return after;
    }

    /// Whether a line is one tag and nothing else, which is the seventh kind of
    /// HTML block.
    static boolean complete(String line) {
        var end = scan(line, 0);
        if (end < 0) return false;
        for (var at = end; at < line.length(); at++) {
            if (!Characters.space(line.charAt(at))) return false;
        }
        return true;
    }

    static int comment(CharSequence text, int pos) {
        if (!starts(text, pos, "<!--")) return -1;
        var end = indexOf(text, "-->", pos + 4);
        return end < 0 ? -1 : end + 3;
    }

    static int processing(CharSequence text, int pos) {
        if (!starts(text, pos, "<?")) return -1;
        var end = indexOf(text, "?>", pos + 2);
        return end < 0 ? -1 : end + 2;
    }

    static int cdata(CharSequence text, int pos) {
        if (!starts(text, pos, "<![CDATA[")) return -1;
        var end = indexOf(text, "]]>", pos + 9);
        return end < 0 ? -1 : end + 3;
    }

    static int declaration(CharSequence text, int pos) {
        if (!starts(text, pos, "<!")) return -1;
        var at = pos + 2;
        if (at >= text.length() || !Character.isLetter(text.charAt(at))) return -1;
        while (at < text.length() && text.charAt(at) != '>') at++;
        return at < text.length() ? at + 1 : -1;
    }

    static int closing(CharSequence text, int pos) {
        if (!starts(text, pos, "</")) return -1;
        var at = name(text, pos + 2);
        if (at < 0) return -1;
        at = spaces(text, at);
        return at < text.length() && text.charAt(at) == '>' ? at + 1 : -1;
    }

    /// An open tag: a name, then attributes, then an optional slash and a `>`.
    static int open(CharSequence text, int pos) {
        var at = name(text, pos + 1);
        if (at < 0) return -1;
        while (true) {
            var after = attribute(text, at);
            if (after < 0) break;
            at = after;
        }
        at = spaces(text, at);
        if (at < text.length() && text.charAt(at) == '/') at++;
        return at < text.length() && text.charAt(at) == '>' ? at + 1 : -1;
    }

    private static int attribute(CharSequence text, int pos) {
        var at = spaces(text, pos);
        if (at == pos) return -1;
        if (at >= text.length()) return -1;
        var character = text.charAt(at);
        if (!Character.isLetter(character) && character != '_' && character != ':') return -1;
        at++;
        while (at < text.length() && attributeName(text.charAt(at))) at++;
        var afterName = at;
        at = spaces(text, at);
        if (at >= text.length() || text.charAt(at) != '=') return afterName;
        at = spaces(text, at + 1);
        if (at >= text.length()) return -1;
        var quote = text.charAt(at);
        if (quote == '"' || quote == '\'') {
            var end = at + 1;
            while (end < text.length() && text.charAt(end) != quote) end++;
            return end < text.length() ? end + 1 : -1;
        }
        var end = at;
        while (end < text.length() && !unquotedEnd(text.charAt(end))) end++;
        return end == at ? -1 : end;
    }

    private static boolean attributeName(char character) {
        return Character.isLetterOrDigit(character)
                || character == '_' || character == '.' || character == ':' || character == '-';
    }

    private static boolean unquotedEnd(char character) {
        return Characters.whitespace(character) || character == '"' || character == '\'' || character == '='
                || character == '<' || character == '>' || character == '`';
    }

    private static int name(CharSequence text, int pos) {
        if (pos >= text.length() || !Characters.tagStart(text.charAt(pos))) return -1;
        var at = pos;
        while (at < text.length() && Characters.tagPart(text.charAt(at))) at++;
        return at;
    }

    private static int spaces(CharSequence text, int pos) {
        var at = pos;
        while (at < text.length() && Characters.whitespace(text.charAt(at))) at++;
        return at;
    }

    private static boolean starts(CharSequence text, int pos, String prefix) {
        if (pos + prefix.length() > text.length()) return false;
        for (var at = 0; at < prefix.length(); at++) {
            if (text.charAt(pos + at) != prefix.charAt(at)) return false;
        }
        return true;
    }

    private static int indexOf(CharSequence text, String needle, int from) {
        for (var at = from; at + needle.length() <= text.length(); at++) {
            if (starts(text, at, needle)) return at;
        }
        return -1;
    }
}
