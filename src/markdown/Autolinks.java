package markdown;

/// `<https://example.com>` and `<someone@example.com>`.
///
/// An autolink is a URI or an email address in angle brackets, and the whole of
/// what makes it one is its shape — there is no list of schemes to keep up to
/// date, only the rule that a scheme is a letter followed by letters, digits,
/// `+`, `.` or `-`, and then a colon.
final class Autolinks {

    private Autolinks() {}

    /// Where the autolink starting at `pos` ends, or -1 if there is not one.
    static int scan(CharSequence text, int pos) {
        if (pos >= text.length() || text.charAt(pos) != '<') return -1;
        var end = pos + 1;
        while (end < text.length() && text.charAt(end) != '>' && text.charAt(end) != '<') {
            if (Characters.whitespace(text.charAt(end))) return -1;
            end++;
        }
        if (end >= text.length() || text.charAt(end) != '>') return -1;
        var inner = text.subSequence(pos + 1, end);
        return uri(inner) || email(inner) ? end + 1 : -1;
    }

    static boolean email(CharSequence text, int pos, int end) {
        return email(text.subSequence(pos + 1, end - 1));
    }

    private static boolean uri(CharSequence text) {
        if (text.isEmpty() || !Characters.tagStart(text.charAt(0))) return false;
        var at = 1;
        while (at < text.length()) {
            var character = text.charAt(at);
            if (character == ':') return at >= 2 && at <= 32;
            var scheme = Character.isLetterOrDigit(character) || character == '+' || character == '.'
                    || character == '-';
            if (!scheme) return false;
            at++;
        }
        return false;
    }

    private static boolean email(CharSequence text) {
        var at = text.toString().indexOf('@');
        if (at <= 0 || at == text.length() - 1) return false;
        for (var index = 0; index < at; index++) {
            if (!local(text.charAt(index))) return false;
        }
        var label = 0;
        for (var index = at + 1; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character == '.') {
                if (label == 0) return false;
                label = 0;
                continue;
            }
            if (!Character.isLetterOrDigit(character) && character != '-') return false;
            label++;
        }
        return label > 0;
    }

    private static boolean local(char character) {
        if (Character.isLetterOrDigit(character)) return true;
        return ".!#$%&'*+/=?^_`{|}~-".indexOf(character) >= 0;
    }
}
