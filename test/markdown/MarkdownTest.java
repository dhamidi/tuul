package markdown;

import harness.Check;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class MarkdownTest {

    private MarkdownTest() {}

    public static void run() throws IOException {
        headings();
        breaks();
        code();
        quotes();
        lists();
        after();
        paragraphs();
        emphasis();
        links();
        references();
        patching();
        unresolved();
        offered();
        incremental();
        images();
        html();
        entities();
        shape();
        cursor();
        streams();
        memory();
        readers();
        awkward();
    }

    // --- blocks -------------------------------------------------------------

    private static void headings() {
        equal("an ATX heading is its level in hashes", "<h1>foo</h1>\n", "# foo\n");
        equal("up to six of them", "<h6>foo</h6>\n", "###### foo\n");
        equal("and seven is a paragraph", "<p>####### foo</p>\n", "####### foo\n");
        equal("a hash needs a space after it", "<p>#foo</p>\n", "#foo\n");
        equal("a closing run is not content", "<h2>foo</h2>\n", "## foo ##\n");
        equal("an underline makes a heading of the paragraph above it", "<h1>foo</h1>\n", "foo\n===\n");
        equal("and a dashed one makes it level two", "<h2>foo</h2>\n", "foo\n---\n");
        equal("a heading may be empty", "<h1></h1>\n", "#\n");
    }

    private static void breaks() {
        equal("three dashes are a thematic break", "<hr />\n", "---\n");
        equal("so are three stars", "<hr />\n", "***\n");
        equal("spaces between them do not matter", "<hr />\n", " - - - \n");
        equal("two are not enough", "<p>--</p>\n", "--\n");
    }

    private static void code() {
        equal("four spaces make a code block", "<pre><code>foo\n</code></pre>\n", "    foo\n");
        equal("a fence makes one without them", "<pre><code>foo\n</code></pre>\n", "```\nfoo\n```\n");
        equal("an info string becomes a language class",
                "<pre><code class=\"language-java\">var x = 1;\n</code></pre>\n",
                "```java\nvar x = 1;\n```\n");
        equal("tildes fence too", "<pre><code>foo\n</code></pre>\n", "~~~\nfoo\n~~~\n");
        equal("markup inside is text", "<pre><code>&lt;b&gt;*x*&lt;/b&gt;\n</code></pre>\n", "```\n<b>*x*</b>\n```\n");
        equal("an unclosed fence runs to the end", "<pre><code>foo\n</code></pre>\n", "```\nfoo\n");
    }

    private static void quotes() {
        equal("a quote holds a paragraph", "<blockquote>\n<p>foo</p>\n</blockquote>\n", "> foo\n");
        equal("its lines join", "<blockquote>\n<p>foo\nbar</p>\n</blockquote>\n", "> foo\n> bar\n");
        equal("a lazy line continues it", "<blockquote>\n<p>foo\nbar</p>\n</blockquote>\n", "> foo\nbar\n");
        equal("but a heading does not", "<blockquote>\n<p>foo</p>\n</blockquote>\n<h1>bar</h1>\n", "> foo\n# bar\n");
        equal("quotes nest", "<blockquote>\n<blockquote>\n<p>foo</p>\n</blockquote>\n</blockquote>\n", "> > foo\n");
        equal("and hold other blocks", "<blockquote>\n<h1>foo</h1>\n</blockquote>\n", "> # foo\n");
    }

    private static void lists() {
        equal("a tight list has no paragraphs", "<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n", "- one\n- two\n");
        equal("a blank line between items makes it loose",
                "<ul>\n<li>\n<p>one</p>\n</li>\n<li>\n<p>two</p>\n</li>\n</ul>\n", "- one\n\n- two\n");
        equal("lists nest by indent", "<ul>\n<li>a\n<ul>\n<li>b</li>\n</ul>\n</li>\n</ul>\n", "- a\n  - b\n");
        equal("numbers make an ordered list", "<ol>\n<li>x</li>\n<li>y</li>\n</ol>\n", "1. x\n2. y\n");
        equal("which may start anywhere", "<ol start=\"3\">\n<li>x</li>\n</ol>\n", "3. x\n");
        equal("a different marker starts a different list",
                "<ul>\n<li>a</li>\n</ul>\n<ul>\n<li>b</li>\n</ul>\n", "- a\n+ b\n");
        equal("an item holds more than a paragraph",
                "<ul>\n<li>\n<p>a</p>\n<pre><code>code\n</code></pre>\n</li>\n</ul>\n", "- a\n\n      code\n");
        equal("a list interrupts a paragraph when it starts at one",
                "<p>foo</p>\n<ul>\n<li>bar</li>\n</ul>\n", "foo\n- bar\n");
    }

    /// What follows a list, which is where a list stops.
    ///
    /// A list holds items and nothing else, so the first thing that is not an
    /// item ends it. Every one of these produced a document whose `</ul>` came
    /// at the very end, with the paragraphs, headings and code blocks after the
    /// list nested inside it — and a heading after a list came out one level
    /// too high, because the marker for a loose list writes to the same field a
    /// heading keeps its level in.
    private static void after() {
        equal("a paragraph ends a list",
                "<ul>\n<li>one</li>\n</ul>\n<p>after</p>\n", "- one\n\nafter\n");
        equal("a heading ends a list, and keeps its level",
                "<ul>\n<li>one</li>\n</ul>\n<h2>after</h2>\n", "- one\n\n## after\n");
        equal("a code block ends a list",
                "<ul>\n<li>one</li>\n</ul>\n<pre><code>x\n</code></pre>\n", "- one\n\n```\nx\n```\n");
        equal("a quote ends a list",
                "<ul>\n<li>one</li>\n</ul>\n<blockquote>\n<p>x</p>\n</blockquote>\n", "- one\n\n> x\n");
        equal("a thematic break ends a list",
                "<ul>\n<li>one</li>\n</ul>\n<hr />\n", "- one\n\n***\n");
        equal("a list of another marker ends the first",
                "<ul>\n<li>one</li>\n</ul>\n<ul>\n<li>two</li>\n</ul>\n", "- one\n\n+ two\n");
        equal("a nested list ends with its parent",
                "<ul>\n<li>a\n<ul>\n<li>b</li>\n</ul>\n</li>\n</ul>\n<p>after</p>\n", "- a\n  - b\n\nafter\n");
        equal("a heading after a nested list keeps its level",
                "<ul>\n<li>a\n<ul>\n<li>b</li>\n</ul>\n</li>\n</ul>\n<h2>after</h2>\n", "- a\n  - b\n\n## after\n");
        equal("a list that ends does not make the one before it loose",
                "<ul>\n<li>one</li>\n</ul>\n<p>mid</p>\n<ul>\n<li>two</li>\n</ul>\n<h2>end</h2>\n",
                "- one\n\nmid\n\n- two\n\n## end\n");

        var document = markdown.Markdown.parse("- one\n\n## after\n");
        var heading = document.walk()
                .filter(step -> step.entering() && step.kind() == markdown.Kind.HEADING)
                .findFirst()
                .orElseThrow();
        Check.equal("and the level is right in the tree, not only in the markup",
                2, document.at(heading.node()).number());
        var parent = document.at(heading.node());
        parent.parent();
        Check.equal("the heading is a child of the document, not of the list",
                markdown.Kind.DOCUMENT, parent.kind());
    }

    private static void paragraphs() {
        equal("a blank line ends a paragraph", "<p>a</p>\n<p>b</p>\n", "a\n\nb\n");
        equal("lines of one join with a newline", "<p>a\nb</p>\n", "a\nb\n");
        equal("leading space is not content", "<p>a</p>\n", "   a\n");
        equal("two trailing spaces are a hard break", "<p>a<br />\nb</p>\n", "a  \nb\n");
        equal("and so is a trailing backslash", "<p>a<br />\nb</p>\n", "a\\\nb\n");
    }

    // --- inlines ------------------------------------------------------------

    private static void emphasis() {
        equal("a star pair is emphasis", "<p><em>foo</em></p>\n", "*foo*\n");
        equal("an underscore pair is too", "<p><em>foo</em></p>\n", "_foo_\n");
        equal("two are strong", "<p><strong>foo</strong></p>\n", "**foo**\n");
        equal("three are both", "<p><em><strong>foo</strong></em></p>\n", "***foo***\n");
        equal("they nest", "<p><strong>a <em>b</em></strong></p>\n", "**a *b***\n");
        equal("a star with space either side is not a delimiter", "<p>a * foo * b</p>\n", "a * foo * b\n");
        equal("intraword underscores are not emphasis", "<p>foo_bar_baz</p>\n", "foo_bar_baz\n");
        equal("intraword stars are", "<p>foo<em>bar</em>baz</p>\n", "foo*bar*baz\n");
        equal("an unmatched delimiter is text", "<p>*foo</p>\n", "*foo\n");
    }

    private static void links() {
        equal("an inline link", "<p><a href=\"/url\">foo</a></p>\n", "[foo](/url)\n");
        equal("with a title", "<p><a href=\"/url\" title=\"t\">foo</a></p>\n", "[foo](/url \"t\")\n");
        equal("with no destination at all", "<p><a href=\"\">foo</a></p>\n", "[foo]()\n");
        equal("angle brackets around a destination with spaces",
                "<p><a href=\"/my%20url\">foo</a></p>\n", "[foo](</my url>)\n");
        equal("emphasis inside link text", "<p><a href=\"/u\"><em>foo</em></a></p>\n", "[*foo*](/u)\n");
        equal("code inside link text", "<p><a href=\"/u\"><code>foo</code></a></p>\n", "[`foo`](/u)\n");
        equal("a bracket that closes nothing is text", "<p>foo]</p>\n", "foo]\n");
        equal("an ampersand in a destination is escaped", "<p><a href=\"/a&amp;b\">x</a></p>\n", "[x](/a&b)\n");
        equal("an autolink is its own text",
                "<p><a href=\"https://x.com\">https://x.com</a></p>\n", "<https://x.com>\n");
        equal("an email autolink gets a scheme",
                "<p><a href=\"mailto:a@b.com\">a@b.com</a></p>\n", "<a@b.com>\n");
    }

    private static void references() {
        equal("a full reference", "<p><a href=\"/url\" title=\"t\">foo</a></p>\n", "[foo][bar]\n\n[bar]: /url \"t\"\n");
        equal("a collapsed one", "<p><a href=\"/url\">foo</a></p>\n", "[foo][]\n\n[foo]: /url\n");
        equal("a shortcut one", "<p><a href=\"/url\">foo</a></p>\n", "[foo]\n\n[foo]: /url\n");
        equal("defined after it is used", "<p><a href=\"/url\">foo</a></p>\n", "[foo]\n\n[foo]: /url\n");
        equal("labels fold case and whitespace",
                "<p><a href=\"/url\">Foo</a></p>\n", "[Foo]\n\n[  FOO  ]: /url\n");
        equal("an undefined label is not a link", "<p>[foo]</p>\n", "[foo]\n");
        equal("a definition renders as nothing", "", "[foo]: /url\n");
        equal("a definition before a paragraph keeps the paragraph",
                "<p>text</p>\n", "[foo]: /url\ntext\n");

        var document = Markdown.parse("[foo]: /url \"title\"\n");
        Check.that("a definition is in the tree", document.definition("foo").isPresent());
        Check.that("looked up however it was capitalised", document.definition("FOO").isPresent());
        Check.that("and not one that was never written", document.definition("bar").isEmpty());
    }

    /// A reference is written down where it appears and patched when its
    /// definition turns up, so where the definition was written cannot change
    /// what the document says.
    private static void patching() {
        equal("one label used twice, defined afterwards",
                "<p><a href=\"/u\" title=\"T\">a</a> and <a href=\"/u\" title=\"T\">b</a></p>\n",
                "[a][r] and [b][r]\n\n[r]: /u \"T\"\n");
        equal("used before and after its definition",
                "<p><a href=\"/u\">one</a></p>\n<p><a href=\"/u\">two</a></p>\n",
                "[one][r]\n\n[r]: /u\n\n[two][r]\n");
        equal("an image reference defined afterwards",
                "<p><img src=\"/u\" alt=\"alt\" /></p>\n", "![alt][r]\n\n[r]: /u\n");
        equal("a second definition of a label changes nothing",
                "<p><a href=\"/first\">a</a></p>\n", "[a][r]\n\n[r]: /first\n\n[r]: /second\n");

        // The two documents are not identical, and should not be: a definition
        // sits where it was written, so the node order differs. What must agree
        // is the link — that it resolved, and to the same place.
        var before = Markdown.parse("[r]: /u\n\n[a][r]\n");
        var after = Markdown.parse("[a][r]\n\n[r]: /u\n");
        Check.equal("a definition after a use resolves the same as one before",
                target(before), target(after));
        Check.equal("and the definition stays where it was written",
                List.of(Kind.DEFINITION, Kind.PARAGRAPH), List.of(kinds(before).get(1), kinds(after).get(1)));

        var resolved = Markdown.parse("[a][r]\n\n[r]: /u\n");
        var link = resolved.cursor();
        link.firstChild();
        link.firstChild();
        Check.equal("a patched reference is a link", Kind.LINK, link.kind());
        Check.equal("carrying the definition it was patched with",
                Kind.DEFINITION, resolved.at(link.number()).kind());
    }

    /// A reference nobody ever defines stays what it was written as, which is
    /// text — all of it, including the label.
    ///
    /// The label is the point: a reader seeing `[the docs][nope]` knows which
    /// definition is missing, and one seeing `[the docs]` has had the name of
    /// it taken away.
    private static void unresolved() {
        equal("an undefined full reference keeps its label",
                "<p>See [the docs][nope].</p>\n", "See [the docs][nope].\n");
        equal("an undefined collapsed one keeps its brackets",
                "<p>See [the docs][].</p>\n", "See [the docs][].\n");
        equal("an undefined shortcut one is just its text",
                "<p>See [the docs].</p>\n", "See [the docs].\n");

        equal("an undefined full image reference", "<p>![alt][nope]</p>\n", "![alt][nope]\n");
        equal("an undefined collapsed image reference", "<p>![alt][]</p>\n", "![alt][]\n");
        equal("an undefined shortcut image reference", "<p>![alt]</p>\n", "![alt]\n");

        equal("markup inside the text survives, and the label stays literal",
                "<p>[<em>em</em>][nope]</p>\n", "[*em*][nope]\n");
        equal("a label is escaped rather than written into the markup",
                "<p>[a][&lt;b&gt;]</p>\n", "[a][<b>]\n");
        equal("two on a line keep their own labels", "<p>[a][b] [c][d]</p>\n", "[a][b] [c][d]\n");
        equal("one that resolves beside one that does not",
                "<p>[a][nope] and <a href=\"/u\">b</a></p>\n", "[a][nope] and [b][r]\n\n[r]: /u\n");

        var document = Markdown.parse("[text][nope]\n");
        var reference = document.cursor();
        reference.firstChild();
        reference.firstChild();
        Check.equal("and the node says it is unresolved", Kind.REFERENCE, reference.kind());
    }

    /// The parse does not wait for the end of the input.
    ///
    /// This drives [Blocks] a line at a time, which is what [Markdown#parse]
    /// does with what a [Reader] has produced so far. A test is the only caller
    /// with a reason to look in the middle.
    private static void incremental() {
        var source = "# Title\n\n[a][r] here.\n\n[r]: /u\n";
        var blocks = new Blocks(source);
        blocks.start();

        var lines = 0;
        var nodesAtReference = 0;
        while (blocks.advance(source.length())) {
            lines++;
            if (lines == 3) nodesAtReference = blocks.size();
        }
        Check.that("nodes exist before the last line is read", nodesAtReference > 0);
        Check.that("including the reference, still waiting for its definition",
                blocks.references().unresolved() > 0);

        var document = blocks.finish();
        Check.equal("and nothing is waiting once the definition arrives",
                0, blocks.references().unresolved());
        Check.equal("which is the same document as parsing it whole",
                kinds(Markdown.parse(source)), kinds(document));
    }

    /// Where the first link in a document points, by way of the definition it
    /// was patched with.
    private static String target(Document document) {
        var found = document.walk()
                .filter(step -> step.entering() && step.kind() == Kind.LINK)
                .findFirst()
                .orElseThrow();
        var link = document.at(found.node());
        var destination = document.at(link.number());
        return link.kind() + " → " + (destination.child(Kind.DESTINATION) ? destination.text().toString() : "?");
    }

    /// Every node's kind, in document order — enough to say two parses agree
    /// about the shape of a document without saying it twice.
    private static List<Kind> kinds(Document document) {
        var kinds = new ArrayList<Kind>();
        document.walk().forEach(step -> {
            if (step.entering()) kinds.add(step.kind());
        });
        return kinds;
    }

    private static void images() {
        equal("an image", "<p><img src=\"/u\" alt=\"foo\" /></p>\n", "![foo](/u)\n");
        equal("with a title", "<p><img src=\"/u\" alt=\"foo\" title=\"t\" /></p>\n", "![foo](/u \"t\")\n");
        equal("its alternative text has the markup taken off",
                "<p><img src=\"/u\" alt=\"a b\" /></p>\n", "![a *b*](/u)\n");
    }

    private static void html() {
        equal("a block of HTML is passed through", "<div>\n<em>x</em>\n</div>\n", "<div>\n<em>x</em>\n</div>\n");
        equal("a comment too", "<!-- c -->\n", "<!-- c -->\n");
        equal("a script keeps its contents", "<script>\nvar x = 1 < 2;\n</script>\n",
                "<script>\nvar x = 1 < 2;\n</script>\n");
        equal("an inline tag is passed through", "<p>a <b>b</b> c</p>\n", "a <b>b</b> c\n");
        equal("with its attributes", "<p><a href=\"x\" title=\"y\">z</a></p>\n", "<a href=\"x\" title=\"y\">z</a>\n");
        equal("an inline comment too", "<p>a <!-- c --> b</p>\n", "a <!-- c --> b\n");
        equal("markdown inside an HTML block is not parsed", "<div>\n*not emphasis*\n</div>\n",
                "<div>\n*not emphasis*\n</div>\n");
        equal("a lone less-than is text", "<p>a &lt; b</p>\n", "a < b\n");
        equal("an HTML block ends at a blank line", "<div>\n<p>after</p>\n", "<div>\n\nafter\n");
    }

    private static void entities() {
        equal("a named reference is decoded", "<p>&amp;</p>\n", "&amp;\n");
        equal("a numeric one is too", "<p>*</p>\n", "&#42;\n");
        equal("and a hexadecimal one", "<p>*</p>\n", "&#x2A;\n");
        equal("an unknown name is left alone", "<p>&amp;nope;</p>\n", "&nope;\n");
        equal("a backslash escapes punctuation", "<p>*not emphasis*</p>\n", "\\*not emphasis\\*\n");
        equal("but not a letter", "<p>\\a</p>\n", "\\a\n");
        equal("markup characters in text are escaped", "<p>a &lt; b &amp; c</p>\n", "a \\< b \\& c\n");
        equal("a code span keeps its backticks apart", "<p><code>a</code></p>\n", "`a`\n");
        equal("a longer run fences a shorter one", "<p><code>a ` b</code></p>\n", "``a ` b``\n");
        equal("one space each side is stripped", "<p><code>`</code></p>\n", "`` ` ``\n");
    }

    // --- the DOM ------------------------------------------------------------

    private static void shape() {
        var document = Markdown.parse("# Title\n\nA *word*.\n");
        Check.equal("the document is node zero", Kind.DOCUMENT, document.cursor().kind());
        Check.that("and holds every other node", document.size() > 5);

        var cursor = document.cursor();
        Check.that("its first child is the heading", cursor.firstChild() && cursor.is(Kind.HEADING));
        Check.equal("which knows its level", 1, cursor.number());
        Check.equal("and points at the source it came from", "Title", cursor.string());
        Check.that("the paragraph is next", cursor.nextSibling() && cursor.is(Kind.PARAGRAPH));
        Check.that("emphasis is inside it", cursor.firstChild() && cursor.nextSibling() && cursor.is(Kind.EMPHASIS));
        Check.equal("and covers the source it was written in", "word", cursor.string());
        Check.that("a cursor climbs back out", cursor.parent() && cursor.is(Kind.PARAGRAPH));

        var source = "# Title\n\nA *word*.\n";
        var heading = document.at(1);
        Check.equal("a span is an offset into the source, not a copy",
                "Title", source.substring(heading.start(), heading.end()));
    }

    private static void cursor() {
        var document = Markdown.parse("- a\n- *b*\n\n> c\n");
        var seen = new HashSet<Integer>();
        var duplicates = new ArrayList<Integer>();
        var cursor = document.cursor();
        do {
            if (!seen.add(cursor.index())) duplicates.add(cursor.index());
        } while (cursor.firstChild() || cursor.nextSibling() || cursor.climb());
        Check.equal("a walk reaches no node twice", List.of(), duplicates);
        Check.that("and reaches more than the root", seen.size() > 5);

        for (var node : seen) {
            var at = document.at(node);
            var depth = 0;
            while (at.parent() && depth < 100) depth++;
            Check.that("every node found is under the document", at.index() == 0);
        }

        var forked = document.cursor();
        forked.firstChild();
        var copy = forked.copy();
        copy.firstChild();
        Check.that("a copy moves without moving the original", forked.index() != copy.index());
        Check.equal("and resetting puts a cursor anywhere", 0, copy.reset(0).index());
    }

    private static void streams() {
        var document = Markdown.parse("# a\n");
        var steps = document.walk().toList();
        Check.that("a walk enters and leaves every node", steps.size() % 2 == 0);
        Check.equal("it starts at the document", 0, steps.getFirst().node());
        Check.that("entering it", steps.getFirst().entering());
        Check.equal("and ends by leaving it", 0, steps.getLast().node());
        Check.that("leaving it", steps.getLast().leaving());

        var entered = new ArrayList<Integer>();
        var left = new ArrayList<Integer>();
        document.walk().forEach(step -> (step.entering() ? entered : left).add(step.node()));
        Check.equal("everything entered is left", new HashSet<>(entered), new HashSet<>(left));

        Check.equal("a walk can be rooted anywhere", 1, document.walk(1).findFirst().orElseThrow().node());

        var lazy = Markdown.parse("a\n\nb\n\nc\n");
        Check.equal("and is lazy — two steps read two steps", 2, lazy.walk().limit(2).toList().size());
    }

    private static void memory() {
        var one = Markdown.parse(paragraphs(50));
        var two = Markdown.parse(paragraphs(100));
        Check.that("twice the document is about twice the nodes",
                two.size() > one.size() * 1.8 && two.size() < one.size() * 2.2);
        Check.that("a node costs 32 bytes, so the array is about that times the count",
                one.bytes() >= one.size() * Nodes.WIDTH * 4 && one.bytes() < one.size() * Nodes.WIDTH * 8 + 4096);
        var long_ = Markdown.parse("x".repeat(100_000) + "\n");
        Check.that("a hundred thousand characters are a handful of nodes", long_.size() < 8);
        Check.that("and cost no more than a handful of nodes to hold",
                long_.bytes() < 4096 && long_.source().length() == 100_001);
    }

    private static String paragraphs(int count) {
        var text = new StringBuilder();
        for (var index = 0; index < count; index++) text.append("Paragraph ").append(index).append(" here.\n\n");
        return text.toString();
    }

    private static void readers() throws IOException {
        var document = Markdown.parse(new StringReader("# from a reader\n"));
        Check.equal("a document can be read from a Reader", Kind.HEADING, document.at(1).kind());

        var out = new StringWriter();
        Markdown.render(document, out);
        Check.equal("and rendered into a Writer", "<h1>from a reader</h1>\n", out.toString());

        // A line arriving in pieces is where a parser that advances per line
        // goes wrong, so this hands it one character at a time.
        var source = "# Title\r\n\n[a][r] and [b][r]\n\n> quoted\n\n[r]: /u \"T\"\n";
        var dribbled = Markdown.parse(new OneCharacterAtATime(source));
        Check.equal("a document read a character at a time parses the same",
                kinds(Markdown.parse(source)), kinds(dribbled));

        var whole = new StringWriter();
        Markdown.render(dribbled, whole);
        Check.equal("and renders the same", Markdown.html(source), whole.toString());
    }

    /// A [Reader] that hands over one character per read, so that every line
    /// arrives in pieces and no buffer boundary lines up with a newline.
    private static final class OneCharacterAtATime extends Reader {

        private final String source;
        private int at;

        private OneCharacterAtATime(String source) {
            this.source = source;
        }

        @Override
        public int read(char[] buffer, int offset, int length) {
            if (at >= source.length()) return -1;
            buffer[offset] = source.charAt(at++);
            return 1;
        }

        @Override
        public void close() {}
    }

    /// The cases where implementations quietly differ from one another, and
    /// the one this library exists to serve: a `///` doc comment.
    private static void awkward() {
        equal("a hard break inside emphasis", "<p><em>a<br />\nb</em></p>\n", "*a  \nb*\n");
        equal("a code span may hold a newline", "<p><code>a b</code></p>\n", "`a\nb`\n");
        equal("emphasis may not span a blank line", "<p>*a</p>\n<p>b*</p>\n", "*a\n\nb*\n");
        equal("a link may not hold a link", "<p>[a <a href=\"/u\">b</a>](/v)</p>\n", "[a [b](/u)](/v)\n");
        equal("an image may", "<p><a href=\"/v\"><img src=\"/u\" alt=\"b\" /></a></p>\n", "[![b](/u)](/v)\n");
        equal("an undefined image reference is text", "<p>![b]</p>\n", "![b]\n");
        equal("a quote inside a list item",
                "<ul>\n<li>\n<blockquote>\n<p>a</p>\n</blockquote>\n</li>\n</ul>\n", "- > a\n");
        equal("a fence inside a list item",
                "<ul>\n<li>\n<pre><code>a\n</code></pre>\n</li>\n</ul>\n", "- ```\n  a\n  ```\n");
        equal("indented code needs a blank line after a paragraph", "<p>a\nb</p>\n", "a\n    b\n");
        equal("a setext underline beats a thematic break for a paragraph", "<h2>a</h2>\n", "a\n---\n");
        equal("a thematic break wins when there is no paragraph", "<hr />\n", "---\n");

        var comment = """
                Markdown in a doc comment, which is why this exists.

                - a list
                - of things

                And `code`, plus a [link](https://example.com).
                """;
        var rendered = Markdown.html(comment);
        Check.that("a doc comment renders as the markdown it is",
                rendered.contains("<ul>") && rendered.contains("<code>code</code>")
                        && rendered.contains("<a href=\"https://example.com\">link</a>"));
        Check.that("and keeps its paragraphs apart", rendered.indexOf("<p>") != rendered.lastIndexOf("<p>"));
    }

    /// What a caller can say about a reference the document never defined.
    ///
    /// The point of the hook is a doc comment: `[ActorSystem#effect(String,
    /// Effect.Handler)]` is javadoc's way of naming a symbol and CommonMark's
    /// way of naming a link definition nobody wrote, so a whole page of
    /// cross-references was rendering as square brackets. What markdown is
    /// allowed to know about that is only what is checked here — a label was
    /// offered, and something came back or did not.
    private static void offered() {
        Links known = label -> label.equals("Foo#bar(String, Baz)") || label.equals("Foo") ? "/s/" + label : null;

        Check.equal("a label somebody claims becomes a link",
                "<p><a href=\"/s/Foo\">Foo</a></p>\n", Markdown.html("[Foo]\n", known));
        Check.equal("and one nobody claims is still the text that was typed",
                "<p>[the docs]</p>\n", Markdown.html("[the docs]\n", known));
        Check.equal("both in one paragraph",
                "<p><a href=\"/s/Foo\">Foo</a> and [Bar]</p>\n", Markdown.html("[Foo] and [Bar]\n", known));

        Check.equal("a label broken across a line is offered as one",
                "<p><a href=\"/s/Foo#bar(String,%20Baz)\">Foo#bar(String,\nBaz)</a></p>\n",
                Markdown.html("[Foo#bar(String,\nBaz)]\n", known));
        Check.equal("case is kept, because it is a name and not a CommonMark label",
                "<p>[foo]</p>\n", Markdown.html("[foo]\n", known));

        Check.equal("a full reference links its text and drops the label",
                "<p><a href=\"/s/Foo\">the system</a></p>\n", Markdown.html("[the system][Foo]\n", known));
        Check.equal("a collapsed one links its text",
                "<p><a href=\"/s/Foo\">Foo</a></p>\n", Markdown.html("[Foo][]\n", known));
        Check.equal("markup inside the text survives inside the link",
                "<p><a href=\"/s/Foo\"><em>Foo</em></a></p>\n", Markdown.html("[*Foo*][Foo]\n", known));

        Check.equal("the document wins: a definition beats anything a caller knows",
                "<p><a href=\"/mine\">Foo</a></p>\n", Markdown.html("[Foo]\n\n[Foo]: /mine\n", known));
        Check.equal("an image reference is not offered, having nowhere to put a link",
                "<p>![Foo]</p>\n", Markdown.html("![Foo]\n", known));

        Check.equal("a destination is encoded, and the text escaped, the way any other link's is",
                "<p><a href=\"/s/%22q%22\">&quot;q&quot;</a></p>\n",
                Markdown.html("[\"q\"]\n", label -> "/s/" + label));
        Check.equal("nobody knowing anything renders what it always did",
                Markdown.html("[Foo] and [the docs]\n"), Markdown.html("[Foo] and [the docs]\n", Links.NONE));
    }

    // --- helpers ------------------------------------------------------------

    private static void equal(String what, String expected, String source) {
        Check.equal(what, expected, Markdown.html(source));
    }
}
