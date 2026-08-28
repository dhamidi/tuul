package web.text;

import java.io.IOException;
import java.io.Writer;

/// Text, escaped for the place it is about to land in.
///
/// There is no single "HTML escaping": text in an element and text in an
/// attribute end at different characters. Each is its own method here, because
/// the way a library gets this wrong is by having one.
///
/// This is a leaf so that everything which writes markup can reach it. Three
/// packages write a `link` or a `script` element — [web.ui] for components,
/// `web.assets` for the import map, and `web` for what a feature puts in the
/// page — and `web.ui` sits above the other two, so its copy was not reachable
/// from them. They each grew a private four-line one instead, and both of those
/// escaped `&`, `"` and `<` while missing `>` and `'`. Two near-copies of a
/// security rule, each wrong in the same way, is the argument for this package.
///
/// Everything writes to a [Writer] rather than answering with a String: the
/// caller is already writing a document, and a rule applied to a value that was
/// finished first is a copy of the whole document per rule.
public final class Escape {

    private Escape() {}

    /// Text in an element. `&` is replaced first by being replaced at all — a
    /// pass per character rather than a pass per rule, so an escape can never
    /// be escaped again.
    public static void text(String value, Writer out) throws IOException {
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
    public static void attribute(String value, Writer out) throws IOException {
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
}
