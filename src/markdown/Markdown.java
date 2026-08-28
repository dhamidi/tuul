package markdown;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.CharBuffer;

/// Markdown, parsed into a DOM and rendered as HTML.
///
/// ```
/// var document = Markdown.parse("# Title\n\nSome *emphasis*.\n");
/// Markdown.render(document, writer);
///
/// var cursor = document.cursor();
/// cursor.firstChild();                      // the heading
/// System.out.println(cursor.number());      // 1
/// System.out.println(cursor.text());        // Title
/// ```
///
/// The parse is CommonMark's two phases, in Goldmark's shape: [Blocks] reads
/// the line structure and [Inlines] reads what is inside a leaf when it closes.
/// What comes out is a [Document] — one copy of the source and one array of
/// nodes, walked with a [Cursor] that allocates nothing, or with
/// [Document#walk] as a lazy stream.
///
/// **What streams, and what the source is kept for.** A [Reader] is parsed as
/// it arrives: a line is turned into nodes as soon as its newline has been
/// seen, and nothing waits for the end of the input. A reference to a
/// definition written further down is not a reason to wait either — it is
/// written down as a [Kind#REFERENCE] where it appears and patched into a link
/// when the definition turns up, which is two ints and no rebuilding. See
/// [References].
///
/// The source is still kept, and that is a smaller claim than it sounds: text
/// is never copied, so a node's text is a span, and a span needs something to
/// point into. Holding the source is what makes a document of a megabyte cost
/// a megabyte and 32 bytes a node, rather than a megabyte of nodes each holding
/// a string of its own. Nothing about the parse needs it.
public final class Markdown {

    private Markdown() {}

    public static Document parse(String source) {
        return new Blocks(source).parse();
    }

    /// Parses as `in` produces text, rather than reading it all and then
    /// starting.
    ///
    /// Each buffer that arrives is appended and every whole line in it is
    /// parsed, so the nodes for the top of a document exist while the bottom is
    /// still on its way. What is read is kept, because spans point into it; it
    /// is never copied a second time.
    public static Document parse(Reader in) throws IOException {
        var source = new StringBuilder();
        var blocks = new Blocks(source);
        blocks.start();
        var buffer = CharBuffer.allocate(8192);
        while (in.read(buffer) >= 0) {
            buffer.flip();
            source.append(buffer);
            buffer.clear();
            while (blocks.advance(source.length())) {
                // every line that has arrived whole
            }
        }
        return blocks.finish();
    }

    public static void render(Document document, Writer out) throws IOException {
        Html.render(document, out);
    }

    /// Renders, letting `links` answer for the references the document never
    /// defined. A caller with an index of its own — of Java symbols, of pages,
    /// of anything named — turns the text a reader was going to see into the
    /// link they meant. See [Links].
    public static void render(Document document, Links links, Writer out) throws IOException {
        Html.render(document, links, out);
    }

    /// Parses and renders in one step, for the caller who wants HTML and has no
    /// use for the tree.
    public static void render(String source, Writer out) throws IOException {
        Html.render(parse(source), out);
    }

    public static void render(String source, Links links, Writer out) throws IOException {
        Html.render(parse(source), links, out);
    }

    public static String html(String source) {
        return html(source, Links.NONE);
    }

    public static String html(String source, Links links) {
        var out = new StringWriter();
        try {
            render(source, links, out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toString();
    }
}
