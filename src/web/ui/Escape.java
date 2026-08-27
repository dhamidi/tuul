package web.ui;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.regex.Pattern;

/// The rules that stop generated markup from meaning something nobody wrote.
///
/// There is no single "HTML escaping": text in an element and text in an
/// attribute end at different characters, and raw text elements end at a
/// closing tag that cannot be escaped at all. Each of those is its own method
/// here, because the way a library gets this wrong is by having one.
final class Escape {

    private static final Pattern ELEMENT = Pattern.compile("[A-Za-z][A-Za-z0-9-]*");
    private static final Pattern ATTRIBUTE = Pattern.compile("[A-Za-z_:][A-Za-z0-9_:.-]*");

    private Escape() {}

    /// Text in an element. `&` is replaced first by being replaced at all — a
    /// pass per character rather than a pass per rule, so an escape can never
    /// be escaped again.
    static void text(String value, Writer out) throws IOException {
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '&' -> out.write("&amp;");
                case '<' -> out.write("&lt;");
                case '>' -> out.write("&gt;");
                default -> out.write(character);
            }
        }
    }

    /// An attribute value, which is written inside double quotes. A quote that
    /// got through here would end the value and let whatever follows become
    /// attributes of its own — that is the whole attack, and it is why an
    /// attribute cannot borrow the rule for element text.
    static void attribute(String value, Writer out) throws IOException {
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '&' -> out.write("&amp;");
                case '<' -> out.write("&lt;");
                case '>' -> out.write("&gt;");
                case '"' -> out.write("&quot;");
                case '\'' -> out.write("&#39;");
                default -> out.write(character);
            }
        }
    }

    /// `<script>` and `<style>` hold text, not markup. Nothing in them is
    /// escaped and nothing can be: escaping a `<` in a script changes the
    /// program. So the only honest answer to content that could end the element
    /// early, or send the parser into another state, is to refuse it.
    static void raw(String element, String content, Writer out) throws IOException {
        var lowered = content.toLowerCase(Locale.ROOT);
        refuse(element, lowered, "</" + element);
        if (element.equals("script")) {
            refuse(element, lowered, "<script");
            refuse(element, lowered, "<!--");
        }
        out.write(content);
    }

    /// A name that is not a name could carry an attribute, or a whole tag,
    /// inside it.
    static String element(String name) {
        if (ELEMENT.matcher(name).matches()) return name;
        throw new HtmlException("not an element name: " + name);
    }

    static String name(String name) {
        if (ATTRIBUTE.matcher(name).matches()) return name;
        throw new HtmlException("not an attribute name: " + name);
    }

    private static void refuse(String element, String lowered, String forbidden) {
        if (!lowered.contains(forbidden)) return;
        throw new HtmlException("<" + element + "> cannot contain " + forbidden
                + " — it would end the element, and there is no way to escape it here");
    }
}
