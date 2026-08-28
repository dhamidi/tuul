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
///
/// The two character rules live in [web.text.Escape], where the packages below
/// this one can reach them. They stay named here so that everything in `web.ui`
/// has one place to ask. What cannot move is the rest: refusing a name that is
/// not a name, and refusing content that would end a raw text element, both
/// answer with [HtmlException], which is what the components throw for every
/// other way of being wrong too.
final class Escape {

    private static final Pattern ELEMENT = Pattern.compile("[A-Za-z][A-Za-z0-9-]*");
    private static final Pattern ATTRIBUTE = Pattern.compile("[A-Za-z_:][A-Za-z0-9_:.-]*");

    private Escape() {}

    /// Text in an element.
    static void text(String value, Writer out) throws IOException {
        web.text.Escape.text(value, out);
    }

    /// An attribute value, which is written inside double quotes.
    static void attribute(String value, Writer out) throws IOException {
        web.text.Escape.attribute(value, out);
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
