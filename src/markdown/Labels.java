package markdown;

import java.util.Locale;

/// How a link label is matched.
///
/// CommonMark folds case, collapses runs of whitespace and trims the ends, so
/// `[Foo   BAR]` and `[foo bar]` are the same reference. Doing it in one place
/// means a definition and a use cannot disagree about what they are called.
final class Labels {

    private Labels() {}

    /// A label with its whitespace collapsed but its case left alone.
    ///
    /// The collapsing is CommonMark's and applies to any reader of a label; the
    /// case folding is CommonMark's rule for *matching* one, and a [Links] that
    /// reads labels in some other naming scheme wants the first without the
    /// second. `Foo` and `foo` are one reference to CommonMark and two Java
    /// types, and a label that broke across a line as `[ActorSystem#effect(String,`
    /// / `Effect.Handler)]` has to come back out as one signature.
    static String collapse(CharSequence label) {
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
        return folded.toString();
    }

    static String normalize(CharSequence label) {
        return collapse(label).toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }
}
