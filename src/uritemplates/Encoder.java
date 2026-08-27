package uritemplates;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/// Percent-encoding, in the two flavours RFC 6570 needs.
///
/// Which characters survive is the whole difference between `{var}` and
/// `{+var}`, and it is where implementations quietly disagree. Two rules matter
/// beyond the character sets: encoding is of UTF-8 *bytes*, not of characters,
/// and an expansion that allows reserved characters must let an existing
/// percent-triple through untouched rather than encoding its `%` and turning
/// `%20` into `%2520`.
final class Encoder {

    private static final String RESERVED = ":/?#[]@!$&'()*+,;=";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Encoder() {}

    static void write(String value, boolean reserved, Writer out) throws IOException {
        var index = 0;
        while (index < value.length()) {
            if (reserved && triple(value, index)) {
                out.write(value, index, 3);
                index += 3;
                continue;
            }
            var codePoint = value.codePointAt(index);
            var width = Character.charCount(codePoint);
            if (unreserved(codePoint) || (reserved && reserved(codePoint))) out.write(value, index, width);
            else percent(value.substring(index, index + width), out);
            index += width;
        }
    }

    /// The first `count` characters of a value, counted in code points so that
    /// a prefix never cuts a character in half. Truncation happens before
    /// encoding, so it can never split a percent-triple either.
    static String prefix(String value, int count) {
        var end = value.offsetByCodePoints(0, Math.min(count, value.codePointCount(0, value.length())));
        return value.substring(0, end);
    }

    /// Turns a percent-triple back into the character it stands for. Anything
    /// that is not a triple is itself.
    static String decode(String value) {
        var out = new StringBuilder();
        var bytes = new ByteArrayOutputStream();
        var index = 0;
        while (index < value.length()) {
            if (!triple(value, index)) {
                flush(bytes, out);
                out.append(value.charAt(index));
                index++;
                continue;
            }
            bytes.write(Integer.parseInt(value.substring(index + 1, index + 3), 16));
            index += 3;
        }
        flush(bytes, out);
        return out.toString();
    }

    private static void flush(ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() == 0) return;
        out.append(bytes.toString(StandardCharsets.UTF_8));
        bytes.reset();
    }

    private static void percent(String character, Writer out) throws IOException {
        for (var b : character.getBytes(StandardCharsets.UTF_8)) {
            out.write('%');
            out.write(HEX[(b >> 4) & 0xF]);
            out.write(HEX[b & 0xF]);
        }
    }

    static boolean unreserved(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '-' || codePoint == '.' || codePoint == '_' || codePoint == '~';
    }

    static boolean reserved(int codePoint) {
        return codePoint < 128 && RESERVED.indexOf(codePoint) >= 0;
    }

    static boolean triple(String value, int index) {
        return index + 2 < value.length()
                && value.charAt(index) == '%'
                && hex(value.charAt(index + 1))
                && hex(value.charAt(index + 2));
    }

    static boolean hex(char character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
    }
}
