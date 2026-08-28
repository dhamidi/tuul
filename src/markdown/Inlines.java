package markdown;

import java.util.ArrayList;
import java.util.List;

/// Inline structure: the second phase, run when a leaf block closes.
///
/// Emphasis is the hard part of CommonMark and this follows the specification's
/// own algorithm: every run of `*` or `_` is emitted as text and remembered on
/// a stack, and when a run that can close meets one that can open, the opener
/// *becomes* the emphasis node and swallows what lies between them. Nothing is
/// allocated twice and nothing is moved, which is what lets the node array stay
/// in the order the document was written — see [Nodes#adopt].
///
/// A reference link is not resolved here. Whether `[foo]` is a link depends on
/// a definition that may not have been read yet, so the parser records the
/// label and leaves the question to [Document#definition], which is asked when
/// somebody wants the answer.
final class Inlines {

    private final String source;
    private final Nodes nodes;
    private final int block;
    private final Segments content;
    private final List<Delimiter> delimiters = new ArrayList<>();
    private final List<Bracket> brackets = new ArrayList<>();

    Inlines(String source, Nodes nodes, int block, Segments content) {
        this.source = source;
        this.nodes = nodes;
        this.block = block;
        this.content = content;
    }

    /// A run of `*` or `_`, and what it is allowed to do.
    private static final class Delimiter {
        int node;
        final char character;
        int count;
        final boolean opens;
        final boolean closes;

        Delimiter(int node, char character, int count, boolean opens, boolean closes) {
            this.node = node;
            this.character = character;
            this.count = count;
            this.opens = opens;
            this.closes = closes;
        }
    }

    /// An unclosed `[` or `![`, and whether anything may still close it.
    private static final class Bracket {
        final int node;
        final int after;
        final boolean image;
        boolean active = true;
        final int delimiters;

        Bracket(int node, int after, boolean image, int delimiters) {
            this.node = node;
            this.after = after;
            this.image = image;
            this.delimiters = delimiters;
        }
    }

    void parse() {
        var at = 0;
        var length = content.length();
        while (at < length) {
            var character = content.charAt(at);
            at = switch (character) {
                case '\\' -> escape(at);
                case '`' -> code(at);
                case '<' -> angle(at);
                case '&' -> entity(at);
                case '*', '_' -> emphasis(at);
                case '[' -> bracket(at, false);
                case '!' -> at + 1 < length && content.charAt(at + 1) == '[' ? bracket(at, true) : text(at, at + 1);
                case ']' -> closeBracket(at);
                case '\n' -> newline(at);
                default -> plain(at);
            };
        }
        pair(0);
    }

    // --- simple inlines -----------------------------------------------------

    private int plain(int at) {
        var to = at;
        var length = content.length();
        while (to < length && !special(content.charAt(to))) to++;
        return text(at, Math.max(to, at + 1));
    }

    private static boolean special(char character) {
        return switch (character) {
            case '\\', '`', '<', '&', '*', '_', '[', ']', '!', '\n' -> true;
            default -> false;
        };
    }

    private int escape(int at) {
        if (at + 1 >= content.length()) return text(at, at + 1);
        var next = content.charAt(at + 1);
        if (next == '\n') {
            node(Kind.HARD_BREAK, at, at + 2);
            return at + 2;
        }
        if (!Characters.punctuation(next)) return text(at, at + 1);
        node(Kind.ESCAPE, at, at + 2);
        return at + 2;
    }

    private int entity(int at) {
        var end = Entities.scan(content, at);
        if (end < 0) return text(at, at + 1);
        node(Kind.ENTITY, at, end);
        return end;
    }

    /// A newline ends a line; two spaces or a backslash before it make the
    /// break hard. The trailing spaces are not text, so the text node stops
    /// before them.
    private int newline(int at) {
        var to = at;
        var spaces = 0;
        while (to > 0 && Characters.space(content.charAt(to - 1))) {
            to--;
            spaces++;
        }
        trim(to);
        node(spaces >= 2 ? Kind.HARD_BREAK : Kind.SOFT_BREAK, to, at + 1);
        var next = at + 1;
        while (next < content.length() && Characters.space(content.charAt(next))) next++;
        return next;
    }

    /// Takes trailing spaces off the last text node, which is where a hard
    /// break's two spaces were.
    private void trim(int to) {
        var last = nodes.last(block);
        if (last == Nodes.NONE || nodes.kind(last) != Kind.TEXT) return;
        var end = content.source(to);
        if (nodes.start(last) >= end) nodes.end(last, nodes.start(last));
        else if (nodes.end(last) > end) nodes.end(last, end);
    }

    private int code(int at) {
        var opening = run(at, '`');
        var from = at + opening;
        var to = from;
        while (to < content.length()) {
            if (content.charAt(to) != '`') {
                to++;
                continue;
            }
            var closing = run(to, '`');
            if (closing == opening) {
                var node = node(Kind.CODE_SPAN, from, to);
                nodes.number(node, 1);
                return to + closing;
            }
            to += closing;
        }
        return text(at, from);
    }

    /// `<https://…>`, `<a@b.c>`, or raw HTML. Everything else is a `<`.
    private int angle(int at) {
        var link = Autolinks.scan(content, at);
        if (link > 0) {
            var node = node(Kind.AUTOLINK, at, link);
            var text = nodes.open(Kind.TEXT, node, content.source(at + 1));
            nodes.end(text, content.source(link - 1));
            nodes.number(node, Autolinks.email(content, at, link) ? 1 : 0);
            return link;
        }
        var html = Tags.scan(content, at);
        if (html > 0) {
            node(Kind.HTML_INLINE, at, html);
            return html;
        }
        return text(at, at + 1);
    }

    // --- emphasis -----------------------------------------------------------

    /// Emits the run as text and remembers what it is allowed to do. Whether it
    /// can open or close depends on what is either side of it, which is why the
    /// decision is made here and not when it is matched.
    private int emphasis(int at) {
        var character = content.charAt(at);
        var count = run(at, character);
        var before = at > 0 ? content.charAt(at - 1) : '\n';
        var after = at + count < content.length() ? content.charAt(at + count) : '\n';

        var beforeWhitespace = Characters.whitespace(before);
        var afterWhitespace = Characters.whitespace(after);
        var beforePunctuation = Characters.punctuation(before);
        var afterPunctuation = Characters.punctuation(after);

        var left = !afterWhitespace && (!afterPunctuation || beforeWhitespace || beforePunctuation);
        var right = !beforeWhitespace && (!beforePunctuation || afterWhitespace || afterPunctuation);

        var opens = character == '*' ? left : left && (!right || beforePunctuation);
        var closes = character == '*' ? right : right && (!left || afterPunctuation);

        var node = node(Kind.TEXT, at, at + count);
        delimiters.add(new Delimiter(node, character, count, opens, closes));
        return at + count;
    }

    /// The specification's process-emphasis, over the delimiters recorded above
    /// `bottom`.
    private void pair(int bottom) {
        var closerIndex = bottom;
        while (closerIndex < delimiters.size()) {
            var closer = delimiters.get(closerIndex);
            if (!closer.closes || closer.count == 0) {
                closerIndex++;
                continue;
            }
            var openerIndex = -1;
            for (var index = closerIndex - 1; index >= bottom; index--) {
                var candidate = delimiters.get(index);
                if (candidate.count == 0 || !candidate.opens || candidate.character != closer.character) continue;
                if (odd(candidate, closer)) continue;
                openerIndex = index;
                break;
            }
            if (openerIndex < 0) {
                closerIndex++;
                continue;
            }
            var opener = delimiters.get(openerIndex);
            var used = opener.count >= 2 && closer.count >= 2 ? 2 : 1;
            wrap(opener, closer, used);
            for (var index = closerIndex - 1; index > openerIndex; index--) delimiters.remove(index);
            closerIndex = delimiters.indexOf(closer);
            if (closer.count == 0) {
                delimiters.remove(closerIndex);
            }
        }
        while (delimiters.size() > bottom) delimiters.remove(delimiters.size() - 1);
    }

    /// The rule of three: when a delimiter could go either way, runs whose
    /// lengths sum to a multiple of three do not pair — unless both are
    /// themselves multiples of three. It exists so that `***a***` nests the way
    /// a reader expects.
    private boolean odd(Delimiter opener, Delimiter closer) {
        if (!(opener.closes || closer.opens)) return false;
        if ((opener.count + closer.count) % 3 != 0) return false;
        return opener.count % 3 != 0 || closer.count % 3 != 0;
    }

    /// Turns the opener into the emphasis node and gives it everything up to
    /// the closer.
    private void wrap(Delimiter opener, Delimiter closer, int used) {
        var kind = used == 2 ? Kind.STRONG : Kind.EMPHASIS;
        var innerStart = nodes.end(opener.node);
        var innerEnd = nodes.start(closer.node);
        nodes.end(opener.node, innerStart - used);
        nodes.start(closer.node, innerEnd + used);
        opener.count -= used;
        closer.count -= used;

        var first = nodes.next(opener.node);
        var emphasis = nodes.insertAfter(opener.node, kind, innerStart);
        var last = previous(closer.node);
        if (last != Nodes.NONE && last != emphasis) nodes.adopt(emphasis, first, last);
        nodes.end(emphasis, innerEnd);

        if (opener.count == 0) {
            nodes.unlink(opener.node);
            opener.node = emphasis;
        }
        if (closer.count == 0) nodes.unlink(closer.node);
    }

    private int previous(int node) {
        var previous = Nodes.NONE;
        for (var child = nodes.first(block); child != Nodes.NONE; child = nodes.next(child)) {
            if (child == node) return previous;
            previous = child;
        }
        return previous;
    }

    // --- links and images ---------------------------------------------------

    private int bracket(int at, boolean image) {
        var from = image ? at : at;
        var to = image ? at + 2 : at + 1;
        var node = node(Kind.TEXT, from, to);
        brackets.add(new Bracket(node, to, image, delimiters.size()));
        return to;
    }

    private int closeBracket(int at) {
        if (brackets.isEmpty()) return text(at, at + 1);
        var bracket = brackets.remove(brackets.size() - 1);
        if (!bracket.active) return text(at, at + 1);

        var after = at + 1;
        var destination = -1;
        var destinationEnd = -1;
        var titleStart = -1;
        var titleEnd = -1;
        var labelStart = -1;
        var labelEnd = -1;
        var matched = false;

        if (after < content.length() && content.charAt(after) == '(') {
            var inline = new Link(content).inline(after);
            if (inline != null) {
                matched = true;
                destination = inline.destinationStart();
                destinationEnd = inline.destinationEnd();
                titleStart = inline.titleStart();
                titleEnd = inline.titleEnd();
                after = inline.end();
            }
        }
        if (!matched) {
            var reference = new Link(content).reference(bracket.after, at, after);
            if (reference != null) {
                matched = true;
                labelStart = reference.labelStart();
                labelEnd = reference.labelEnd();
                after = reference.end();
            }
        }
        if (!matched) return text(at, at + 1);

        pair(bracket.delimiters);
        var kind = bracket.image ? Kind.IMAGE : Kind.LINK;
        nodes.kind(bracket.node, kind);
        nodes.end(bracket.node, content.source(after));
        var first = nodes.next(bracket.node);
        var last = nodes.last(block);
        if (first != Nodes.NONE) nodes.adopt(bracket.node, first, last);

        if (destination >= 0) {
            var url = nodes.open(Kind.DESTINATION, bracket.node, content.source(destination));
            nodes.end(url, content.source(destinationEnd));
        }
        if (titleStart >= 0) {
            var title = nodes.open(Kind.TITLE, bracket.node, content.source(titleStart));
            nodes.end(title, content.source(titleEnd));
        }
        if (labelStart >= 0) {
            var label = nodes.open(Kind.LABEL, bracket.node, content.source(labelStart));
            nodes.end(label, content.source(labelEnd));
        }
        if (!bracket.image) {
            for (var open : brackets) {
                if (!open.image) open.active = false;
            }
        }
        return after;
    }

    // --- emitting -----------------------------------------------------------

    private int text(int from, int to) {
        if (to > from) node(Kind.TEXT, from, to);
        return to;
    }

    private int node(Kind kind, int from, int to) {
        var node = nodes.open(kind, block, content.source(from));
        nodes.end(node, content.source(to));
        return node;
    }

    private int run(int at, char character) {
        var to = at;
        while (to < content.length() && content.charAt(to) == character) to++;
        return to - at;
    }
}
