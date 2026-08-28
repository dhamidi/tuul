package markdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Block structure: the first of the two phases this parser is built in.
///
/// Goldmark's shape, and CommonMark's: a document is a stack of open blocks,
/// and every line is asked, in order, whether it continues each of them. What
/// does not continue is closed, what is new is opened, and whatever is left of
/// the line becomes content of the deepest open leaf. Inline structure is not
/// looked at here at all — see [Inlines], which runs when a leaf closes.
///
/// Content is collected as [Segments] rather than as text, because the prefixes
/// this phase strips — a quote's `>`, a list item's indent — belong to the
/// blocks that own them and not to the paragraph inside.
///
/// What is implemented: ATX and setext headings, thematic breaks, indented and
/// fenced code, HTML blocks of all seven kinds, block quotes with lazy
/// continuation, bullet and ordered lists with tightness, paragraphs, and link
/// reference definitions. Tabs advance to the next four-column stop when they
/// are being counted as indentation, and are otherwise left alone; a tab that
/// would be split in half by a list marker is consumed whole, which is the one
/// place this knowingly parts company with the specification.
final class Blocks {

    private static final Set<String> HTML_BLOCKS = Set.of(
            "address", "article", "aside", "base", "basefont", "blockquote", "body", "caption", "center", "col",
            "colgroup", "dd", "details", "dialog", "dir", "div", "dl", "dt", "fieldset", "figcaption", "figure",
            "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hr",
            "html", "iframe", "legend", "li", "link", "main", "menu", "menuitem", "nav", "noframes", "ol",
            "optgroup", "option", "p", "param", "search", "section", "summary", "table", "tbody", "td", "tfoot",
            "th", "thead", "title", "tr", "track", "ul");

    private final String source;
    private final Nodes nodes = new Nodes();
    private final Map<String, Integer> definitions = new LinkedHashMap<>();
    private final List<Open> stack = new ArrayList<>();
    private boolean consumed;

    Blocks(String source) {
        this.source = source;
    }

    /// One open block, and whatever it needs to know to recognise its own
    /// continuation.
    private static final class Open {
        final int node;
        final Kind kind;
        Segments content;
        boolean ordered;
        char marker;
        int indent;
        boolean tight = true;
        boolean blankBefore;
        char fence;
        int fenceLength;
        int fenceIndent;
        boolean fenced;
        int html;
        boolean lastLineBlank;

        Open(int node, Kind kind) {
            this.node = node;
            this.kind = kind;
        }
    }

    Document parse() {
        stack.add(new Open(nodes.open(Kind.DOCUMENT, Nodes.NONE, 0), Kind.DOCUMENT));
        var at = 0;
        while (at <= source.length()) {
            var end = source.indexOf('\n', at);
            if (end < 0) end = source.length();
            if (at == source.length() && at > 0 && source.charAt(at - 1) == '\n') break;
            line(at, trimCarriage(at, end));
            at = end + 1;
        }
        while (stack.size() > 1) close(stack.size() - 1);
        nodes.end(0, source.length());
        return new Document(source, nodes, definitions);
    }

    private int trimCarriage(int start, int end) {
        return end > start && source.charAt(end - 1) == '\r' ? end - 1 : end;
    }

    private void line(int start, int end) {
        consumed = false;
        var pos = start;
        var matched = 1;
        while (matched < stack.size()) {
            var open = stack.get(matched);
            var next = continues(open, pos, end);
            if (next < 0) break;
            pos = next;
            matched++;
        }
        if (consumed) return;

        var lazy = matched < stack.size() && deepest().kind == Kind.PARAGRAPH
                && !blank(pos, end) && !interrupts(pos, end);
        if (!lazy) {
            while (stack.size() > matched) close(stack.size() - 1);
        }

        if (deepest().kind == Kind.HTML_BLOCK && deepest().html >= 6 && blank(pos, end)) {
            close(stack.size() - 1);
            return;
        }
        if (deepest().kind == Kind.CODE || deepest().kind == Kind.HTML_BLOCK) {
            content(pos, end);
            if (deepest().kind == Kind.HTML_BLOCK && ends(deepest(), pos, end)) close(stack.size() - 1);
            return;
        }

        pos = starts(pos, end);
        if (consumed) return;

        if (blank(pos, end)) {
            deepest().lastLineBlank = true;
            if (deepest().kind == Kind.PARAGRAPH) close(stack.size() - 1);
            else if (deepest().kind == Kind.CODE && deepest().fenced) content(pos, end);
            marker();
            return;
        }
        deepest().lastLineBlank = false;
        content(pos, end);
    }

    /// A blank line followed by more content in the same item is what makes a
    /// list loose — the items grow paragraph tags, which is the whole visible
    /// difference.
    private void loosen() {
        var inItem = false;
        for (var index = stack.size() - 1; index > 0; index--) {
            var open = stack.get(index);
            if (open.kind == Kind.ITEM) inItem = true;
            if (open.kind == Kind.LIST && inItem && open.blankBefore) {
                open.tight = false;
                open.blankBefore = false;
            }
        }
    }

    private void marker() {
        for (var open : stack) {
            if (open.kind == Kind.LIST) open.blankBefore = true;
        }
    }

    private Open deepest() {
        return stack.get(stack.size() - 1);
    }

    private void content(int start, int end) {
        loosen();
        var open = deepest();
        if (open.content == null) open.content = new Segments(source);
        open.content.add(start, end);
        nodes.end(open.node, end);
    }

    // --- continuation -------------------------------------------------------

    /// Where the line continues after this block's own prefix, or -1 when it
    /// does not continue at all.
    private int continues(Open open, int pos, int end) {
        return switch (open.kind) {
            case QUOTE -> quoted(pos, end);
            case ITEM -> item(open, pos, end);
            case LIST, DOCUMENT -> pos;
            case CODE -> code(open, pos, end);
            case HTML_BLOCK -> pos;
            case PARAGRAPH -> blank(pos, end) ? -1 : pos;
            default -> -1;
        };
    }

    private int quoted(int pos, int end) {
        var after = skipSpaces(pos, end, 3);
        if (after >= end || source.charAt(after) != '>') return -1;
        after++;
        if (after < end && source.charAt(after) == ' ') after++;
        else if (after < end && source.charAt(after) == '\t') after++;
        return after;
    }

    private int item(Open open, int pos, int end) {
        if (blank(pos, end)) return Math.min(end, pos);
        var indent = indent(pos, end);
        if (indent < open.indent) return -1;
        return advance(pos, end, open.indent);
    }

    private int code(Open open, int pos, int end) {
        if (!open.fenced) {
            if (blank(pos, end)) return Math.min(end, pos);
            return indent(pos, end) >= 4 ? advance(pos, end, 4) : -1;
        }
        var after = skipSpaces(pos, end, 3);
        if (after < end && source.charAt(after) == open.fence) {
            var run = run(after, end, open.fence);
            if (run >= open.fenceLength && blank(after + run, end)) {
                close(stack.indexOf(open));
                consumed = true;
                return -1;
            }
        }
        return advance(pos, end, open.fenceIndent);
    }

    private boolean ends(Open open, int start, int end) {
        var line = source.substring(start, end);
        return switch (open.html) {
            case 1 -> containsAny(line, "</script>", "</pre>", "</style>", "</textarea>");
            case 2 -> line.contains("-->");
            case 3 -> line.contains("?>");
            case 4 -> line.contains(">");
            case 5 -> line.contains("]]>");
            default -> false;
        };
    }

    private static boolean containsAny(String line, String... needles) {
        var lower = line.toLowerCase(java.util.Locale.ROOT);
        for (var needle : needles) {
            if (lower.contains(needle)) return true;
        }
        return false;
    }

    // --- new blocks ---------------------------------------------------------

    /// Opens whatever this line starts, as deep as it goes, and answers where
    /// the content of the innermost one begins.
    private int starts(int pos, int end) {
        var at = pos;
        while (true) {
            if (deepest().kind == Kind.CODE || deepest().kind == Kind.HTML_BLOCK) return at;
            var indent = indent(at, end);
            if (indent >= 4) {
                if (deepest().kind == Kind.PARAGRAPH || blank(at, end)) return at;
                open(Kind.CODE, at);
                return advance(at, end, 4);
            }
            var text = skipSpaces(at, end, 3);
            if (text >= end) return at;

            var next = quote(text, end);
            if (next < 0) next = atx(text, end);
            if (next < 0) next = fence(text, end, indent);
            if (next < 0) next = html(text, end);
            if (next < 0) next = setext(text, end);
            if (next < 0) next = rule(text, end);
            if (next < 0) next = listItem(text, end, indent);
            if (next < 0) {
                if (deepest().kind != Kind.PARAGRAPH && !blank(text, end)) open(Kind.PARAGRAPH, text);
                return text;
            }
            at = next;
            if (deepest().kind == Kind.HEADING || deepest().kind == Kind.BREAK) return at;
        }
    }

    private int quote(int pos, int end) {
        if (source.charAt(pos) != '>') return -1;
        open(Kind.QUOTE, pos);
        var after = pos + 1;
        if (after < end && (source.charAt(after) == ' ' || source.charAt(after) == '\t')) after++;
        return after;
    }

    private int atx(int pos, int end) {
        var hashes = run(pos, end, '#');
        if (hashes == 0 || hashes > 6) return -1;
        var after = pos + hashes;
        if (after < end && !Characters.space(source.charAt(after))) return -1;
        closeParagraph();
        var node = open(Kind.HEADING, pos);
        nodes.number(node.node, hashes);
        var from = skipSpaces(after, end, Integer.MAX_VALUE);
        var to = end;
        while (to > from && Characters.space(source.charAt(to - 1))) to--;
        var closing = to;
        while (closing > from && source.charAt(closing - 1) == '#') closing--;
        if (closing < to && (closing == from || Characters.space(source.charAt(closing - 1)))) {
            to = closing;
            while (to > from && Characters.space(source.charAt(to - 1))) to--;
        }
        node.content = new Segments(source);
        if (to > from) node.content.add(from, to);
        nodes.start(node.node, from);
        nodes.end(node.node, to);
        close(stack.size() - 1);
        consumed = true;
        return end;
    }

    private int fence(int pos, int end, int indent) {
        var character = source.charAt(pos);
        if (character != '`' && character != '~') return -1;
        var length = run(pos, end, character);
        if (length < 3) return -1;
        var info = skipSpaces(pos + length, end, Integer.MAX_VALUE);
        if (character == '`' && source.substring(info, end).indexOf('`') >= 0) return -1;
        closeParagraph();
        var node = open(Kind.CODE, pos);
        node.fenced = true;
        node.fence = character;
        node.fenceLength = length;
        node.fenceIndent = indent;
        var to = end;
        while (to > info && Characters.space(source.charAt(to - 1))) to--;
        if (to > info) {
            var text = nodes.open(Kind.INFO, node.node, info);
            nodes.end(text, to);
        }
        consumed = true;
        return end;
    }

    private int html(int pos, int end) {
        var type = htmlType(pos, end);
        if (type == 0) return -1;
        if (type == 7 && deepest().kind == Kind.PARAGRAPH) return -1;
        closeParagraph();
        var node = open(Kind.HTML_BLOCK, pos);
        node.html = type;
        return pos;
    }

    private int htmlType(int pos, int end) {
        if (source.charAt(pos) != '<') return 0;
        var rest = source.substring(pos, end);
        var lower = rest.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("<script") || lower.startsWith("<pre") || lower.startsWith("<style")
                || lower.startsWith("<textarea")) {
            return 1;
        }
        if (rest.startsWith("<!--")) return 2;
        if (rest.startsWith("<?")) return 3;
        if (rest.length() > 2 && rest.startsWith("<!") && Character.isLetter(rest.charAt(2))) return 4;
        if (rest.startsWith("<![CDATA[")) return 5;
        var name = tagName(rest);
        if (name != null && HTML_BLOCKS.contains(name)) return 6;
        return Tags.complete(rest) ? 7 : 0;
    }

    private static String tagName(String rest) {
        var at = rest.startsWith("</") ? 2 : 1;
        if (at >= rest.length() || !Characters.tagStart(rest.charAt(at))) return null;
        var to = at;
        while (to < rest.length() && Characters.tagPart(rest.charAt(to))) to++;
        var after = to < rest.length() ? rest.charAt(to) : ' ';
        if (after != ' ' && after != '\t' && after != '>' && !(after == '/' && to + 1 < rest.length())) return null;
        return rest.substring(at, to).toLowerCase(java.util.Locale.ROOT);
    }

    private int setext(int pos, int end) {
        if (deepest().kind != Kind.PARAGRAPH) return -1;
        var character = source.charAt(pos);
        if (character != '=' && character != '-') return -1;
        var length = run(pos, end, character);
        if (!blank(pos + length, end)) return -1;
        var paragraph = deepest();
        nodes.kind(paragraph.node, Kind.HEADING);
        nodes.number(paragraph.node, character == '=' ? 1 : 2);
        nodes.end(paragraph.node, end);
        close(stack.size() - 1);
        consumed = true;
        return end;
    }

    private int rule(int pos, int end) {
        var character = source.charAt(pos);
        if (character != '-' && character != '_' && character != '*') return -1;
        var count = 0;
        for (var at = pos; at < end; at++) {
            var current = source.charAt(at);
            if (current == character) count++;
            else if (!Characters.space(current)) return -1;
        }
        if (count < 3) return -1;
        closeParagraph();
        var node = open(Kind.BREAK, pos);
        nodes.end(node.node, end);
        close(stack.size() - 1);
        consumed = true;
        return end;
    }

    private int listItem(int pos, int end, int indent) {
        var character = source.charAt(pos);
        var ordered = false;
        var start = 1;
        var after = pos;
        if (character == '-' || character == '+' || character == '*') {
            after = pos + 1;
        } else if (Character.isDigit(character)) {
            var digits = 0;
            while (after < end && Character.isDigit(source.charAt(after)) && digits < 9) {
                after++;
                digits++;
            }
            if (after >= end || (source.charAt(after) != '.' && source.charAt(after) != ')')) return -1;
            start = Integer.parseInt(source, pos, after, 10);
            character = source.charAt(after);
            after++;
            ordered = true;
        } else {
            return -1;
        }
        if (after < end && !Characters.space(source.charAt(after))) return -1;
        if (deepest().kind == Kind.PARAGRAPH && blank(after, end)) return -1;
        if (deepest().kind == Kind.PARAGRAPH && ordered && start != 1 && !inList()) return -1;

        var spaces = 0;
        var content = after;
        while (content < end && Characters.space(source.charAt(content)) && spaces < 5) {
            spaces += source.charAt(content) == '\t' ? 4 - (spaces % 4) : 1;
            content++;
        }
        if (spaces > 4 || blank(after, end)) {
            spaces = 1;
            content = Math.min(after + 1, end);
            if (blank(after, end)) content = end;
        }
        var width = indent + (after - pos) + spaces;

        closeParagraph();
        var current = deepest();
        if (current.kind == Kind.LIST && (current.marker != character || current.ordered != ordered)) {
            close(stack.size() - 1);
        }
        var list = deepest();
        if (list.kind != Kind.LIST || list.marker != character || list.ordered != ordered) {
            var opened = open(Kind.LIST, pos);
            opened.ordered = ordered;
            opened.marker = character;
            nodes.number(opened.node, ordered ? start : -1);
            list = opened;
        } else if (list.blankBefore) {
            list.tight = false;
            list.blankBefore = false;
        }
        var item = open(Kind.ITEM, pos);
        item.indent = width;
        return content;
    }

    /// Whether this line starts a block, and so cannot be the lazy
    /// continuation of a paragraph. Asking without opening anything is the only
    /// way to know, because a paragraph swallows what does not interrupt it.
    private boolean interrupts(int pos, int end) {
        var indent = indent(pos, end);
        if (indent >= 4) return false;
        var at = skipSpaces(pos, end, 3);
        if (at >= end) return false;
        var character = source.charAt(at);
        if (character == '>') return true;
        var hashes = run(at, end, '#');
        if (hashes >= 1 && hashes <= 6 && at + hashes < end && Characters.space(source.charAt(at + hashes))) {
            return true;
        }
        if ((character == '`' || character == '~') && run(at, end, character) >= 3) return true;
        var html = htmlType(at, end);
        if (html >= 1 && html <= 6) return true;
        if (isRule(at, end)) return true;
        return isItem(at, end);
    }

    private boolean inList() {
        for (var open : stack) {
            if (open.kind == Kind.LIST) return true;
        }
        return false;
    }

    private boolean isRule(int pos, int end) {
        var character = source.charAt(pos);
        if (character != '-' && character != '_' && character != '*') return false;
        var count = 0;
        for (var at = pos; at < end; at++) {
            var current = source.charAt(at);
            if (current == character) count++;
            else if (!Characters.space(current)) return false;
        }
        return count >= 3;
    }

    /// A list item may interrupt a paragraph only when it has content and, if
    /// it is numbered, starts at one — otherwise a line beginning `2024.` would
    /// become a list.
    private boolean isItem(int pos, int end) {
        var character = source.charAt(pos);
        var after = pos;
        if (character == '-' || character == '+' || character == '*') {
            after = pos + 1;
        } else if (Character.isDigit(character)) {
            var digits = 0;
            while (after < end && Character.isDigit(source.charAt(after)) && digits < 9) {
                after++;
                digits++;
            }
            if (after >= end || (source.charAt(after) != '.' && source.charAt(after) != ')')) return false;
            if (Integer.parseInt(source, pos, after, 10) != 1 && !inList()) return false;
            after++;
        } else {
            return false;
        }
        if (after < end && !Characters.space(source.charAt(after))) return false;
        return !blank(after, end);
    }

    private void closeParagraph() {
        if (deepest().kind == Kind.PARAGRAPH) close(stack.size() - 1);
    }

    private Open open(Kind kind, int start) {
        var parent = deepest();
        var node = nodes.open(kind, parent.node, start);
        var opened = new Open(node, kind);
        stack.add(opened);
        return opened;
    }

    /// Closes the block at `index` and everything under it. A leaf's content
    /// becomes inline nodes here, which is the seam between the two phases.
    private void close(int index) {
        while (stack.size() > index + 1) close(stack.size() - 1);
        var open = stack.remove(index);
        if (open.kind == Kind.PARAGRAPH) definitions(open);
        if (open.kind == Kind.LIST) tightness(open);
        if (open.content == null) return;
        switch (nodes.kind(open.node)) {
            case PARAGRAPH, HEADING -> new Inlines(source, nodes, open.node, open.content).parse();
            case CODE, HTML_BLOCK -> verbatim(open);
            default -> { }
        }
    }

    /// A code block's text is its content, joined — the one place a span is not
    /// enough, because the lines are not contiguous once their indent is gone.
    /// The node's span covers the source it came from and [Html] writes the
    /// lines out, so nothing is copied here either.
    private void verbatim(Open open) {
        var content = open.content;
        var last = content.lines() - 1;
        if (nodes.kind(open.node) == Kind.CODE && open.fenced) {
            while (last >= 0 && content.lineEnd(last) == content.lineStart(last)) last--;
        }
        for (var line = 0; line <= last; line++) {
            var text = nodes.open(Kind.TEXT, open.node, content.lineStart(line));
            nodes.end(text, content.lineEnd(line));
        }
    }

    /// A list is loose when a blank line separated any two of its items, and
    /// then every item's paragraph keeps its tags. Recorded on the list because
    /// that is where a renderer asks.
    private void tightness(Open list) {
        nodes.number(list.node, list.ordered ? nodes.number(list.node) : -1);
        if (!list.tight) markLoose(list.node);
    }

    private void markLoose(int list) {
        for (var item = nodes.first(list); item != Nodes.NONE; item = nodes.next(item)) {
            nodes.number(item, 1);
        }
    }

    // --- line arithmetic ----------------------------------------------------

    private boolean blank(int pos, int end) {
        for (var at = pos; at < end; at++) {
            if (!Characters.space(source.charAt(at))) return false;
        }
        return true;
    }

    private int indent(int pos, int end) {
        var columns = 0;
        for (var at = pos; at < end; at++) {
            var character = source.charAt(at);
            if (character == ' ') columns++;
            else if (character == '\t') columns += 4 - (columns % 4);
            else break;
        }
        return columns;
    }

    private int skipSpaces(int pos, int end, int most) {
        var columns = 0;
        var at = pos;
        while (at < end && columns < most) {
            var character = source.charAt(at);
            if (character == ' ') columns++;
            else if (character == '\t') columns += 4 - (columns % 4);
            else break;
            at++;
        }
        return at;
    }

    /// Moves past `columns` of indentation, or as much of it as there is.
    private int advance(int pos, int end, int columns) {
        var seen = 0;
        var at = pos;
        while (at < end && seen < columns) {
            var character = source.charAt(at);
            if (character == ' ') seen++;
            else if (character == '\t') seen += 4 - (seen % 4);
            else break;
            at++;
        }
        return at;
    }

    private int run(int pos, int end, char character) {
        var at = pos;
        while (at < end && source.charAt(at) == character) at++;
        return at - pos;
    }

    // --- link reference definitions -----------------------------------------

    /// Takes the definitions off the front of a paragraph. A paragraph that was
    /// nothing else stops being a paragraph, so that it renders as nothing
    /// without anybody having to remember to skip it.
    private void definitions(Open paragraph) {
        var content = paragraph.content;
        if (content == null) return;
        while (!content.empty()) {
            var taken = new Definitions(source, nodes, definitions).read(paragraph.node, content);
            if (taken == 0) break;
        }
        if (content.empty()) {
            nodes.kind(paragraph.node, Kind.DEFINITION);
            paragraph.content = null;
        }
    }
}
