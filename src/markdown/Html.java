package markdown;

import java.io.IOException;
import java.io.Writer;

/// HTML, written into a [Writer] as the document is walked.
///
/// It is a fold over [Document#walk] rather than a recursion, which is why that
/// walk yields an event on the way in and another on the way out: an element
/// opens when its node is entered and closes when it is left, and a node with
/// no children needs no special case. Nothing is buffered — the writer sees the
/// document in order, so a large one can be rendered straight onto a socket.
///
/// A reference link asks the document for its definition at the moment it is
/// written, which is the point of leaving that question open: by the time
/// anything renders, every definition has been read.
public final class Html {

    private final Document document;
    private final Writer out;
    private int unresolved = -1;
    private char last = '\n';

    private Html(Document document, Writer out) {
        this.document = document;
        this.out = out;
    }

    public static void render(Document document, Writer out) throws IOException {
        new Html(document, out).write();
    }

    /// Writes, remembering the last character, so that [#line] can tell whether
    /// the output is already at the start of one.
    private void write(String text) throws IOException {
        if (text.isEmpty()) return;
        out.write(text);
        last = text.charAt(text.length() - 1);
    }

    private void write(char character) throws IOException {
        out.write(character);
        last = character;
    }

    /// A newline, unless there already is one. Block elements begin on their
    /// own line and inline content does not, and this is the whole of the
    /// difference between `<li>one</li>` and an item holding a quote.
    private void line() throws IOException {
        if (last != '\n') write('\n');
    }

    private void write() throws IOException {
        var steps = document.walk().iterator();
        var skipping = -1;
        while (steps.hasNext()) {
            var step = steps.next();
            if (skipping >= 0) {
                if (step.node() == skipping && step.leaving()) skipping = -1;
                continue;
            }
            if (step.entering() && whole(step.kind())) {
                if (element(step)) skipping = step.node();
                continue;
            }
            if (step.entering()) open(step);
            else close(step);
        }
    }

    /// The kinds this renders in one go, children and all, because their
    /// children are not content: a code block's lines, a link's destination.
    private static boolean whole(Kind kind) {
        return switch (kind) {
            case CODE, HTML_BLOCK, CODE_SPAN, AUTOLINK, IMAGE, DEFINITION, DESTINATION, TITLE, LABEL, INFO -> true;
            default -> false;
        };
    }

    private boolean element(Step step) throws IOException {
        var cursor = document.at(step.node());
        switch (step.kind()) {
            case CODE -> code(cursor);
            case HTML_BLOCK -> {
                line();
                lines(cursor);
            }
            case CODE_SPAN -> codeSpan(cursor);
            case AUTOLINK -> autolink(cursor);
            case IMAGE -> image(cursor);
            default -> { }
        }
        return true;
    }

    private void open(Step step) throws IOException {
        var cursor = document.at(step.node());
        switch (step.kind()) {
            case DOCUMENT -> { }
            case PARAGRAPH -> {
                if (!tight(cursor)) {
                    line();
                    write("<p>");
                }
            }
            case HEADING -> {
                line();
                write("<h" + cursor.number() + ">");
            }
            case BREAK -> {
                line();
                write("<hr />\n");
            }
            case QUOTE -> {
                line();
                write("<blockquote>\n");
            }
            case LIST -> {
                line();
                list(cursor);
            }
            case ITEM -> write(cursor.number() == 1 ? "<li>\n" : "<li>");
            case EMPHASIS -> write("<em>");
            case STRONG -> write("<strong>");
            case LINK -> link(cursor);
            case TEXT -> escape(cursor.text());
            case ESCAPE -> escape(cursor.text().subSequence(1, 2));
            case ENTITY -> escape(Entities.decode(cursor.text()));
            case SOFT_BREAK -> write("\n");
            case HARD_BREAK -> write("<br />\n");
            case HTML_INLINE -> write(cursor.text().toString());
            default -> { }
        }
    }

    private void close(Step step) throws IOException {
        var cursor = document.at(step.node());
        switch (step.kind()) {
            case PARAGRAPH -> {
                if (!tight(cursor)) write("</p>\n");
                else if (document.nodes().next(step.node()) != Nodes.NONE) write("\n");
            }
            case HEADING -> write("</h" + cursor.number() + ">\n");
            case QUOTE -> write("</blockquote>\n");
            case LIST -> write(cursor.number() < 0 ? "</ul>\n" : "</ol>\n");
            case ITEM -> write("</li>\n");
            case EMPHASIS -> write("</em>");
            case STRONG -> write("</strong>");
            case LINK -> {
                if (unresolved == step.node()) {
                    write("]");
                    unresolved = -1;
                } else {
                    write("</a>");
                }
            }
            default -> { }
        }
    }

    /// A paragraph inside a tight list item has no tags of its own — the item
    /// carries its text directly, which is the whole visible difference between
    /// a tight list and a loose one.
    private boolean tight(Cursor paragraph) {
        var parent = paragraph.copy();
        return parent.parent() && parent.is(Kind.ITEM) && parent.number() != 1;
    }

    private void list(Cursor list) throws IOException {
        var start = list.number();
        if (start < 0) write("<ul>\n");
        else if (start == 1) write("<ol>\n");
        else write("<ol start=\"" + start + "\">\n");
    }

    private void code(Cursor code) throws IOException {
        var info = code.copy();
        line();
        write("<pre><code");
        if (info.child(Kind.INFO)) {
            var language = info.string();
            var space = language.indexOf(' ');
            write(" class=\"language-" + attribute(space < 0 ? language : language.substring(0, space)) + "\"");
        }
        write(">");
        lines(code);
        write("</code></pre>\n");
    }

    /// The lines of a verbatim block, each one a text node, each written with
    /// the newline the source had after it.
    private void lines(Cursor block) throws IOException {
        var line = block.copy();
        if (!line.firstChild()) return;
        do {
            if (!line.is(Kind.TEXT)) continue;
            if (block.is(Kind.HTML_BLOCK)) write(line.text().toString());
            else escape(line.text());
            write("\n");
        } while (line.nextSibling());
    }

    /// A code span's newlines become spaces, and one space is stripped from
    /// each end when there is one at both — so `` ` `` can be written inside
    /// one.
    private void codeSpan(Cursor code) throws IOException {
        var text = code.text().toString().replace('\n', ' ');
        if (text.length() > 2 && text.startsWith(" ") && text.endsWith(" ") && !text.isBlank()) {
            text = text.substring(1, text.length() - 1);
        }
        write("<code>");
        escape(text);
        write("</code>");
    }

    private void autolink(Cursor link) throws IOException {
        var text = link.copy();
        text.firstChild();
        var target = text.string();
        var href = link.number() == 1 ? "mailto:" + target : target;
        write("<a href=\"" + url(href) + "\">");
        escape(target);
        write("</a>");
    }

    /// A reference whose label nobody defined is not a link at all: the
    /// brackets were text, and they go back the way they were written. The
    /// node's span still covers them, which is why this can be answered here
    /// rather than guessed at.
    private void link(Cursor link) throws IOException {
        var target = target(link);
        if (target == null) {
            unresolved = link.index();
            write("[");
            return;
        }
        write("<a href=\"" + url(target.destination()) + "\"");
        if (!target.title().isEmpty()) write(" title=\"" + attribute(target.title()) + "\"");
        write(">");
    }

    private void image(Cursor image) throws IOException {
        var target = target(image);
        if (target == null) {
            write("![");
            escape(alt(image));
            write("]");
            return;
        }
        write("<img src=\"" + url(target.destination()) + "\" alt=\"" + attribute(alt(image)) + "\"");
        if (!target.title().isEmpty()) write(" title=\"" + attribute(target.title()) + "\"");
        write(" />");
    }

    private record Target(String destination, String title) {}

    /// Where a link points, whether it said so itself or named a definition
    /// that did. A label nobody defined has no destination, and the link is
    /// rendered as an empty one rather than dropped — the text is what the
    /// document said, and the reader can see it.
    private Target target(Cursor link) {
        var destination = link.copy();
        if (destination.child(Kind.DESTINATION)) {
            var title = link.copy();
            return new Target(unescape(destination.text()), title.child(Kind.TITLE) ? unescape(title.text()) : "");
        }
        var label = link.copy();
        if (!label.child(Kind.LABEL)) return null;
        var defined = document.definition(label.string());
        if (defined.isEmpty()) return null;
        var definition = defined.get();
        var url = definition.copy();
        var title = definition.copy();
        return new Target(
                url.child(Kind.DESTINATION) ? unescape(url.text()) : "",
                title.child(Kind.TITLE) ? unescape(title.text()) : "");
    }

    /// An image's alternative text is its content with the markup taken off,
    /// because an attribute cannot hold elements.
    private String alt(Cursor image) {
        var text = new StringBuilder();
        document.walk(image.index()).forEach(step -> {
            if (!step.entering()) return;
            var cursor = document.at(step.node());
            switch (step.kind()) {
                case TEXT, CODE_SPAN -> text.append(cursor.text());
                case ESCAPE -> text.append(cursor.text().charAt(1));
                case ENTITY -> text.append(Entities.decode(cursor.text()));
                case SOFT_BREAK, HARD_BREAK -> text.append('\n');
                default -> { }
            }
        });
        return text.toString();
    }

    private void escape(CharSequence text) throws IOException {
        for (var at = 0; at < text.length(); at++) {
            var character = text.charAt(at);
            switch (character) {
                case '<' -> write("&lt;");
                case '>' -> write("&gt;");
                case '&' -> write("&amp;");
                case '"' -> write("&quot;");
                default -> write(character);
            }
        }
    }

    private static String attribute(CharSequence text) {
        var escaped = new StringBuilder(text.length());
        for (var at = 0; at < text.length(); at++) {
            var character = text.charAt(at);
            switch (character) {
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '&' -> escaped.append("&amp;");
                case '"' -> escaped.append("&quot;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    /// A destination as it goes in an attribute: what a browser needs escaped,
    /// escaped, and what it needs percent-encoded, encoded — while leaving an
    /// existing percent-escape alone, since a URL that was already written that
    /// way meant it.
    private static String url(String destination) {
        var encoded = new StringBuilder(destination.length());
        for (var at = 0; at < destination.length(); at++) {
            var character = destination.charAt(at);
            if (character == '%' && at + 2 < destination.length() && hex(destination.charAt(at + 1))
                    && hex(destination.charAt(at + 2))) {
                encoded.append(destination, at, at + 3);
                at += 2;
                continue;
            }
            switch (character) {
                case '&' -> encoded.append("&amp;");
                case '"' -> encoded.append("%22");
                case '<' -> encoded.append("%3C");
                case '>' -> encoded.append("%3E");
                case ' ' -> encoded.append("%20");
                case '\\' -> encoded.append("%5C");
                case '`' -> encoded.append("%60");
                case '[' -> encoded.append("%5B");
                case ']' -> encoded.append("%5D");
                default -> {
                    if (character < 128) encoded.append(character);
                    else encoded.append(percent(character));
                }
            }
        }
        return encoded.toString();
    }

    private static String percent(char character) {
        var bytes = String.valueOf(character).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var encoded = new StringBuilder(bytes.length * 3);
        for (var value : bytes) encoded.append('%').append(String.format("%02X", value));
        return encoded.toString();
    }

    private static boolean hex(char character) {
        var lower = Character.toLowerCase(character);
        return (character >= '0' && character <= '9') || (lower >= 'a' && lower <= 'f');
    }

    /// Backslash escapes and character references, resolved — a destination is
    /// stored as it was written, and this is where it stops being.
    private static String unescape(CharSequence text) {
        var plain = new StringBuilder(text.length());
        for (var at = 0; at < text.length(); at++) {
            var character = text.charAt(at);
            if (character == '\\' && at + 1 < text.length() && Characters.punctuation(text.charAt(at + 1))) {
                plain.append(text.charAt(++at));
                continue;
            }
            if (character == '&') {
                var end = Entities.scan(text, at);
                if (end > 0) {
                    plain.append(Entities.decode(text.subSequence(at, end)));
                    at = end - 1;
                    continue;
                }
            }
            plain.append(character);
        }
        return plain.toString();
    }
}
