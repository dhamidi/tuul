package markdown;

import java.util.Locale;

/// How a link label is matched.
///
/// CommonMark folds case, collapses runs of whitespace and trims the ends, so
/// `[Foo   BAR]` and `[foo bar]` are the same reference. Doing it in one place
/// means a definition and a use cannot disagree about what they are called.
final class Labels {

    private Labels() {}

    static String normalize(CharSequence label) {
        var folded = new StringBuilder(label.length());
        var space = false;
        for (var i = 0; i < label.length(); i++) {
            var character = label.charAt(i);
            if (Characters.whitespace(character)) {
                space = folded.length() > 0;
                continue;
            }
            if (space) folded.append(' ');
            space = false;
            folded.append(character);
        }
        return folded.toString().toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }
}
