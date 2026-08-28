package markdown;

/// The character classes CommonMark is written in terms of.
///
/// They are here rather than inline because the emphasis rules ask about them
/// four times a delimiter, and because "punctuation" in that specification
/// means something wider than a keyboard suggests: symbols count, so `+foo+`
/// flanks the way `*foo*` does.
final class Characters {

    private Characters() {}

    static boolean whitespace(char character) {
        return character == ' '
                || character == '\t'
                || character == '\n'
                || character == '\u000B'
                || character == '\f'
                || character == '\r'
                || Character.getType(character) == Character.SPACE_SEPARATOR;
    }

    static boolean space(char character) {
        return character == ' ' || character == '\t';
    }

    static boolean punctuation(char character) {
        if (character < 128) {
            return !Character.isLetterOrDigit(character) && !whitespace(character) && character != 0;
        }
        return switch (Character.getType(character)) {
            case Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION,
                    Character.MATH_SYMBOL,
                    Character.CURRENCY_SYMBOL,
                    Character.MODIFIER_SYMBOL,
                    Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    /// A tag name, as HTML spells one: a letter, then letters, digits or
    /// hyphens.
    static boolean tagStart(char character) {
        return character < 128 && Character.isLetter(character);
    }

    static boolean tagPart(char character) {
        return character == '-' || (character < 128 && Character.isLetterOrDigit(character));
    }
}
