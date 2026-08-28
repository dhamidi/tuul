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
/// **Where streaming stops.** A [Reader] is read once, and nothing is copied
/// after that: every node's text is a span of the source. But the source is
/// held whole, and it has to be — a link written as `[foo]` may be defined at
/// the bottom of the document, so nothing can be finished until the last line
/// has been read. A parser that claimed to stream markdown would be lying about
/// that, so this reads a document and then answers questions about it.
public final class Markdown {

    private Markdown() {}

    public static Document parse(String source) {
        return new Blocks(source).parse();
    }

    /// Reads the whole of `in`. See the note above about why this cannot be a
    /// stream: link reference definitions can appear after their use.
    public static Document parse(Reader in) throws IOException {
        return parse(read(in));
    }

    public static void render(Document document, Writer out) throws IOException {
        Html.render(document, out);
    }

    /// Parses and renders in one step, for the caller who wants HTML and has no
    /// use for the tree.
    public static void render(String source, Writer out) throws IOException {
        Html.render(parse(source), out);
    }

    public static String html(String source) {
        var out = new StringWriter();
        try {
            render(source, out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toString();
    }

    private static String read(Reader in) throws IOException {
        var text = new StringBuilder();
        var buffer = CharBuffer.allocate(8192);
        while (in.read(buffer) >= 0) {
            buffer.flip();
            text.append(buffer);
            buffer.clear();
        }
        return text.toString();
    }
}
