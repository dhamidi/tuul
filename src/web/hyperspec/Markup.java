package web.hyperspec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import peg.Parse;
import peg.Parser;
import peg.Rule;
import peg.Tree;

/// Reading HTML far enough to find what a page offers.
///
/// The grammar is the tokens — an open tag, a close tag, text — and the tree is
/// built from them with a stack, which is how a reader stays small and still
/// gets nesting right. What it deliberately does not do is listed on
/// [Document]; the short version is that it reads HTML a program wrote rather
/// than HTML a person typed, and says so instead of guessing.
final class Markup {

    private static final String SPACE = " \t\r\n";
    private static final String ENDS_NAME = SPACE + "=>/\"'";

    private Markup() {}

    static Document.Element read(String html) {
        var parse = Parser.parse(Rule.sequence(markup(), Rule.end()), html);
        return switch (parse) {
            case Parse.Failure<Character> failure -> throw new SpecException(0, "cannot read the page: " + failure.message());
            case Parse.Ok<Character> ok -> build(Trees.only(ok.tree(), "markup"));
        };
    }

    private static Rule<Character> markup() {
        return Rule.label("markup", Rule.star(Rule.choice(
                comment(), doctype(), raw("script"), raw("style"), close(), open(), text(), stray())));
    }

    private static Rule<Character> comment() {
        return until(literal("<!--"), literal("-->"));
    }

    private static Rule<Character> doctype() {
        return until(literal("<!"), literal(">"));
    }

    /// `<script>` and `<style>` hold text that is not markup, so they are read
    /// whole and dropped. Reading their content as markup is how a reader finds
    /// a link inside a string in a script and reports an affordance that is not
    /// there.
    private static Rule<Character> raw(String tag) {
        return Rule.sequence(
                literal("<" + tag),
                Rule.not(name()),
                Rule.star(Rule.sequence(Rule.not(Rule.is('>')), Rule.any())),
                Rule.is('>'),
                Rule.star(Rule.sequence(Rule.not(literal("</" + tag)), Rule.any())),
                until(literal("</" + tag), literal(">")));
    }

    private static Rule<Character> close() {
        return Rule.label("close", Rule.sequence(
                literal("</"), Rule.label("tag", Rule.plus(name())), spaces(), Rule.is('>')));
    }

    private static Rule<Character> open() {
        return Rule.label("open", Rule.sequence(
                Rule.is('<'),
                Rule.label("tag", Rule.plus(name())),
                Rule.star(attribute()),
                spaces(),
                Rule.option(Rule.label("empty", Rule.is('/'))),
                Rule.is('>')));
    }

    private static Rule<Character> attribute() {
        return Rule.label("attribute", Rule.sequence(
                Rule.plus(anyOf(SPACE, "a space")),
                Rule.label("key", Rule.plus(noneOf(ENDS_NAME, "an attribute name"))),
                Rule.option(Rule.sequence(spaces(), Rule.is('='), spaces(), value()))));
    }

    private static Rule<Character> value() {
        return Rule.choice(
                Rule.sequence(Rule.is('"'), Rule.label("value", Rule.star(noneOf("\"", "a value"))), Rule.is('"')),
                Rule.sequence(Rule.is('\''), Rule.label("value", Rule.star(noneOf("'", "a value"))), Rule.is('\'')),
                Rule.label("value", Rule.plus(noneOf(SPACE + ">\"'`=", "a value"))));
    }

    private static Rule<Character> text() {
        return Rule.label("text", Rule.plus(noneOf("<", "text")));
    }

    /// A `<` that begins no tag is text. Browsers do this and so must anything
    /// that reads what browsers accept.
    private static Rule<Character> stray() {
        return Rule.label("text", Rule.is('<'));
    }

    private static Rule<Character> name() {
        return Rule.when("a tag name", c -> Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':');
    }

    private static Rule<Character> spaces() {
        return Rule.star(anyOf(SPACE, "a space"));
    }

    private static Rule<Character> until(Rule<Character> open, Rule<Character> close) {
        return Rule.sequence(open, Rule.star(Rule.sequence(Rule.not(close), Rule.any())), close);
    }

    private static Rule<Character> literal(String text) {
        return Rule.sequence(text.chars().mapToObj(c -> Rule.is((char) c)).toList());
    }

    private static Rule<Character> anyOf(String chars, String expected) {
        return Rule.when(expected, c -> chars.indexOf(c) >= 0);
    }

    private static Rule<Character> noneOf(String chars, String expected) {
        return Rule.when(expected, c -> chars.indexOf(c) < 0);
    }

    /// Tokens into a tree. An end tag that closes nothing on the stack is
    /// ignored rather than fatal — a page with one stray `</div>` is still a
    /// page a client can use, and refusing to read it would help nobody.
    private static Document.Element build(Tree<Character> markup) {
        var stack = new ArrayDeque<Frame>();
        stack.push(new Frame("#document", Map.of()));
        for (var child : ((Tree.Node<Character>) markup).children()) {
            if (!(child instanceof Tree.Node<Character> node)) continue;
            switch (node.label()) {
                case "open" -> open(stack, node);
                case "close" -> close(stack, Trees.text(node, "tag"));
                case "text" -> stack.peek().content.add(new Document.Text(entities(node.text())));
                default -> { }
            }
        }
        while (stack.size() > 1) closeTop(stack);
        return stack.pop().element();
    }

    private static void open(Deque<Frame> stack, Tree<Character> node) {
        var tag = Trees.text(node, "tag").toLowerCase(java.util.Locale.ROOT);
        var attributes = new LinkedHashMap<String, String>();
        for (var attribute : Trees.children(node, "attribute")) {
            attributes.putIfAbsent(
                    Trees.text(attribute, "key").toLowerCase(java.util.Locale.ROOT),
                    entities(Trees.text(attribute, "value")));
        }
        var frame = new Frame(tag, attributes);
        if (Document.VOID.contains(tag) || !Trees.children(node, "empty").isEmpty()) {
            stack.peek().content.add(frame.element());
            return;
        }
        stack.push(frame);
    }

    private static void close(Deque<Frame> stack, String tag) {
        var name = tag.toLowerCase(java.util.Locale.ROOT);
        if (stack.stream().noneMatch(frame -> frame.name.equals(name))) return;
        while (stack.size() > 1 && !stack.peek().name.equals(name)) closeTop(stack);
        if (stack.size() > 1) closeTop(stack);
    }

    private static void closeTop(Deque<Frame> stack) {
        var done = stack.pop();
        stack.peek().content.add(done.element());
    }

    /// The named entities a page actually uses, and the numeric ones. A page
    /// rendered by `web.ui` only ever emits the first five; the rest are here
    /// because a page written by hand does not know that.
    private static String entities(String text) {
        if (text.indexOf('&') < 0) return text;
        var out = new StringBuilder();
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            var end = c == '&' ? text.indexOf(';', i) : -1;
            if (end < 0 || end - i > 10) {
                out.append(c);
                continue;
            }
            var entity = text.substring(i + 1, end);
            var decoded = entity(entity);
            if (decoded == null) {
                out.append(c);
                continue;
            }
            out.append(decoded);
            i = end;
        }
        return out.toString();
    }

    private static String entity(String name) {
        return switch (name) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos", "#39" -> "'";
            case "nbsp" -> " ";
            default -> number(name);
        };
    }

    private static String number(String name) {
        try {
            if (name.startsWith("#x") || name.startsWith("#X")) {
                return Character.toString(Integer.parseInt(name.substring(2), 16));
            }
            if (name.startsWith("#")) return Character.toString(Integer.parseInt(name.substring(1)));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    /// An element being built. Mutable because a stack is, and package-private
    /// to nothing — it never leaves this file.
    private static final class Frame {

        private final String name;
        private final Map<String, String> attributes;
        private final List<Document> content = new ArrayList<>();

        private Frame(String name, Map<String, String> attributes) {
            this.name = name;
            this.attributes = attributes;
        }

        private Document.Element element() {
            return new Document.Element(name, attributes, content);
        }
    }
}
