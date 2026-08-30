package markdown;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

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
///
/// A Markdown table renders as a `div` with table, row, and cell roles. The
/// table, rows, and cells use flex styles, so the cells can wrap on narrow
/// screens. The renderer does not emit an HTML `table` element for Markdown
/// tables.
public final class Html {

    private final Document document;
    private final Links links;
    private final Writer out;
    private final Map<Integer, String> headings;
    private char last = '\n';

    private Html(Document document, Links links, Writer out, Map<Integer, String> headings) {
        this.document = document;
        this.links = links;
        this.out = out;
        this.headings = headings;
    }

    public static void render(Document document, Writer out) throws IOException {
        render(document, Links.NONE, out);
    }

    /// Renders, offering `links` every reference the document itself never
    /// defined. See [Links] for why that offer is made here rather than earlier.
    public static void render(Document document, Links links, Writer out) throws IOException {
        new Html(document, links, out, Map.of()).write();
    }

    /// Renders with an identifier on each heading. Use [Outline#of] to make
    /// links that point at these identifiers.
    public static void renderAnchored(Document document, Links links, Writer out) throws IOException {
        new Html(document, links, out, Outline.identifiers(document)).write();
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
            if (step.entering() && whole(step)) {
                if (element(step)) skipping = step.node();
                continue;
            }
            if (step.entering()) open(step);
            else close(step);
        }
    }

    /// The kinds this renders in one go, children and all, because their
    /// children are not content: a code block's lines, a link's destination.
    ///
    /// A reference nobody defined is one of them when it was written as an
    /// image, because an image renders its alternative text rather than its
    /// children — and is not when it was written as a link, whose text is
    /// content and keeps whatever markup it had.
    private boolean whole(Step step) {
        return switch (step.kind()) {
            case CODE, HTML_BLOCK, CODE_SPAN, AUTOLINK, IMAGE, DEFINITION, DESTINATION, TITLE, LABEL, INFO -> true;
            case REFERENCE -> References.image(document.nodes(), step.node());
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
            case REFERENCE -> {
                write("![");
                escape(alt(cursor));
                write("]");
                escape(remainder(cursor));
            }
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
                write("<h" + cursor.number());
                var id = headings.get(step.node());
                if (id != null) write(" id=\"" + attribute(id) + "\"");
                write(">");
            }
            case BREAK -> {
                line();
                write("<hr />\n");
            }
            case QUOTE -> {
                line();
                write("<blockquote>\n");
            }
            case TABLE -> {
                line();
                write("<div class=\"markdown-table\" role=\"table\" style=\"display:flex;flex-direction:column\">\n");
            }
            case TABLE_ROW -> write("<div class=\"markdown-table-row\" role=\"row\" style=\"display:flex;flex-wrap:wrap\">");
            case TABLE_CELL -> {
                var alignment = switch (cursor.number()) {
                    case 1 -> "left";
                    case 2 -> "center";
                    case 3 -> "right";
                    default -> "start";
                };
                var row = cursor.copy();
                row.parent();
                var header = row.number() == 1;
                var role = header ? "columnheader" : "cell";
                var className = header ? "markdown-table-cell markdown-table-header" : "markdown-table-cell";
                write("<div class=\"" + className + "\" role=\"" + role
                        + "\" style=\"flex:1 1 10rem;min-width:0;text-align:" + alignment + "\">");
            }
            case LIST -> {
                line();
                list(cursor);
            }
            case ITEM -> write(cursor.number() == 1 ? "<li>\n" : "<li>");
            case EMPHASIS -> write("<em>");
            case STRONG -> write("<strong>");
            case LINK -> link(cursor);
            case REFERENCE -> reference(cursor);
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
            case TABLE_CELL -> write("</div>");
            case TABLE_ROW -> write("</div>\n");
            case TABLE -> write("</div>\n");
            case LIST -> write(cursor.number() < 0 ? "</ul>\n" : "</ol>\n");
            case ITEM -> write("</li>\n");
            case EMPHASIS -> write("</em>");
            case STRONG -> write("</strong>");
            case LINK -> write("</a>");
            case REFERENCE -> {
                if (destination(cursor) != null) write("</a>");
                else {
                    write("]");
                    escape(remainder(cursor));
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

    private void link(Cursor link) throws IOException {
        var target = target(link);
        write("<a href=\"" + url(links.defined(target.destination())) + "\"");
        if (!target.title().isEmpty()) write(" title=\"" + attribute(target.title()) + "\"");
        write(">");
    }

    private void image(Cursor image) throws IOException {
        var target = target(image);
        write("<img src=\"" + url(target.destination()) + "\" alt=\"" + attribute(alt(image)) + "\"");
        if (!target.title().isEmpty()) write(" title=\"" + attribute(target.title()) + "\"");
        write(" />");
    }

    /// A reference nobody defined: a link if a caller claimed the label, and
    /// otherwise the brackets that were typed.
    ///
    /// The label is offered on the way out as well as on the way in, rather
    /// than remembered between the two. [Links] is asked a question about a
    /// string and a walk can be inside more than one reference at a time, so
    /// asking twice is cheaper than the stack that would avoid it — and it is
    /// the answer that decides both tags, so the two cannot disagree.
    private void reference(Cursor reference) throws IOException {
        var destination = destination(reference);
        if (destination == null) write("[");
        else write("<a href=\"" + url(destination) + "\">");
    }

    /// What a caller says the label points at, or null for nobody.
    ///
    /// An image reference is not offered, and not because it could not be: an
    /// unresolved `![alt][x]` renders its alternative text and nothing else, so
    /// there is no element to hang a destination on, and a caller's naming
    /// scheme is a scheme of names rather than of pictures.
    private String destination(Cursor reference) {
        if (links == Links.NONE || References.image(document.nodes(), reference.index())) return null;
        var label = reference.copy();
        return label.child(Kind.LABEL) ? links.destination(Labels.collapse(label.text())) : null;
    }

    /// What an unmatched reference wrote after its text, which goes back out
    /// as it was typed: nothing for a shortcut, `[]` for a collapsed one,
    /// `[label]` for a full one.
    ///
    /// A reader has to see the label to know which definition is missing. A
    /// page that renders `[the docs][nope]` as `[the docs]` has swallowed the
    /// name of the thing nobody defined, which is the one word that would have
    /// said what to fix.
    ///
    /// Where the text ended is worked out from the label rather than stored: a
    /// shortcut and a collapsed reference label *is* the text, so the text ends
    /// where the label does, and a full one's label sits two characters past
    /// the text's closing bracket.
    ///
    /// This reads the source, so a label split across two lines of a block
    /// quote carries the `>` of the second line into the output. That is what
    /// every span crossing a line does here — a code span has the same seam —
    /// and it belongs to how inline spans are recorded rather than to this.
    private String remainder(Cursor reference) {
        var nodes = document.nodes();
        var label = reference.copy();
        if (!label.child(Kind.LABEL)) return "";
        var opens = nodes.start(reference.index()) + (References.image(nodes, reference.index()) ? 2 : 1);
        var text = nodes.start(label.index()) == opens ? nodes.end(label.index()) : nodes.start(label.index()) - 2;
        return document.source().subSequence(text + 1, nodes.end(reference.index())).toString();
    }

    private record Target(String destination, String title) {}

    /// Where a link points: what it said itself, or what the definition it was
    /// patched with says.
    ///
    /// Nothing is looked up here. A reference that named a definition carries
    /// the node it was resolved to, put there when the definition was read —
    /// so rendering asks the tree rather than a map, and a link that was never
    /// resolved is not a [Kind#LINK] at all but a [Kind#REFERENCE], which this
    /// is never asked about.
    private Target target(Cursor link) {
        var destination = link.copy();
        if (destination.child(Kind.DESTINATION)) {
            var title = link.copy();
            return new Target(unescape(destination.text()), title.child(Kind.TITLE) ? unescape(title.text()) : "");
        }
        var definition = document.at(link.number());
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
