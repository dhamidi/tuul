package web.hyperspec;

import java.util.ArrayList;
import java.util.List;
import peg.Parse;
import peg.Parser;
import peg.Rule;
import peg.Tree;
import web.hyperspec.Script.Command;
import web.hyperspec.Script.Part;
import web.hyperspec.Script.Word;

/// Tcl's syntax, as a grammar.
///
/// Not all of Tcl — a spec needs the *shape* of the language, which is what
/// makes `expect link "Sign in"` and `set id [attribute note id]` read the way
/// they do, and none of the language's own primitives. What is here is the
/// dodekalogue's first eleven rules: words separated by blanks, `;` and newline
/// ending a command, `#` starting a comment where a command could start,
/// `{braces}` deferring everything inside them, `"quotes"` allowing
/// substitution, `$name` and `${name}` naming a variable, `[script]` standing
/// for its own result, and a backslash escaping any of it.
///
/// Two simplifications, both deliberate and both visible from outside:
///
/// - `]` always ends a word, not only inside `[...]`. A literal `]` is written
///   `\]` or inside braces. Making it context-sensitive would buy one character
///   of convenience for a rule nobody could keep in their head.
/// - A comment runs to the end of the line and starts only where a command
///   could, exactly as in Tcl. `visit / # not a comment` passes `#` as a word;
///   write `visit / ;# a comment` instead.
public final class Syntax {

    private static final String BLANK = " \t\r";
    private static final String ENDS_WORD = BLANK + "\n;][";

    private static final Rule<Character> SCRIPT = Rule.reference("script", Syntax::script);

    private Syntax() {}

    /// Parses a spec. `first` is the line the source starts on, so that a body
    /// parsed out of a `{...}` reports the lines of the file it came from
    /// rather than of itself.
    public static Script parse(String source, int first) {
        var parse = Parser.parse(Rule.sequence(SCRIPT, Rule.end()), source);
        return switch (parse) {
            case Parse.Failure<Character> failure -> throw unparsable(source, first, failure);
            case Parse.Ok<Character> ok -> new Convert(first).script(Trees.only(ok.tree(), "script"));
        };
    }

    public static Script parse(String source) {
        return parse(source, 1);
    }

    /// What went wrong, where a person can find it.
    ///
    /// A furthest-failure position at the end of the input means something was
    /// opened and never closed, and saying *that* is worth more than a column
    /// number pointing past the last character — the mistake is the `{` or the
    /// `\"` somewhere above, and the parser cannot know which one.
    private static SpecException unparsable(String source, int first, Parse.Failure<Character> failure) {
        var at = Math.min(failure.position(), source.length());
        var line = first + newlines(source.substring(0, at));
        var wanted = String.join(" or ", failure.expected());
        if (at == source.length()) {
            return new SpecException(line, "the spec ends with something still open — expected " + wanted);
        }
        var column = at - source.lastIndexOf('\n', at - 1);
        return new SpecException(line, "column " + column + ": unexpected "
                + describe(source.charAt(at)) + " — expected " + wanted);
    }

    private static String describe(char c) {
        return switch (c) {
            case '\n' -> "the end of the line";
            case '\t' -> "a tab";
            default -> "\"" + c + "\"";
        };
    }

    // The grammar. Only labelled rules make nodes, so the shape of the tree is
    // exactly the shape of these labels and nothing else has to be filtered.

    private static Rule<Character> script() {
        return Rule.label("script", Rule.star(Rule.choice(blanks(), terminator(), command())));
    }

    private static Rule<Character> command() {
        return Rule.label("command", Rule.sequence(word(), Rule.star(Rule.sequence(blanks(), word()))));
    }

    private static Rule<Character> word() {
        return Rule.label("word", Rule.choice(braced(), quoted(), bare()));
    }

    private static Rule<Character> braced() {
        return Rule.label("braced", Rule.sequence(is('{'), Rule.label("body", Rule.star(balanced())), is('}')));
    }

    /// Braces nest, and a backslash hides one. Everything else inside a braced
    /// word is itself, which is the point of braces.
    private static Rule<Character> balanced() {
        return Rule.reference("balanced", () -> Rule.choice(
                Rule.sequence(is('{'), Rule.star(balanced()), is('}')),
                escape(),
                noneOf("{}", "text")));
    }

    private static Rule<Character> quoted() {
        return Rule.label("quoted", Rule.sequence(
                is('"'),
                Rule.star(Rule.choice(escape(), variable(), substitution(), text("\"$[\\"))),
                is('"')));
    }

    /// A word that begins with a brace or a quote is a braced or a quoted
    /// word, or it is a mistake — never a bare word that happens to start with
    /// one. Without this an unclosed `{` parses as text and the spec runs,
    /// which is the worst of the three possible outcomes.
    private static Rule<Character> bare() {
        return Rule.label("bare", Rule.sequence(
                Rule.not(anyOf("{\"", "a brace or a quote")),
                Rule.plus(Rule.choice(escape(), variable(), substitution(), text(ENDS_WORD + "$\\")))));
    }

    private static Rule<Character> text(String ends) {
        return Rule.label("text", Rule.plus(noneOf(ends, "text")));
    }

    private static Rule<Character> escape() {
        return Rule.label("escape", Rule.sequence(is('\\'), Rule.any()));
    }

    private static Rule<Character> variable() {
        return Rule.label("variable", Rule.sequence(is('$'), Rule.choice(
                Rule.sequence(is('{'), Rule.label("name", Rule.star(noneOf("}", "a name"))), is('}')),
                Rule.label("name", Rule.plus(Rule.when("a name", Syntax::names))))));
    }

    private static Rule<Character> substitution() {
        return Rule.label("substitution", Rule.sequence(is('['), SCRIPT, is(']')));
    }

    private static Rule<Character> terminator() {
        return Rule.choice(is(';'), is('\n'), comment());
    }

    private static Rule<Character> comment() {
        return Rule.sequence(is('#'), Rule.star(Rule.sequence(Rule.not(is('\n')), Rule.any())));
    }

    private static Rule<Character> blanks() {
        return Rule.plus(anyOf(BLANK, "a blank"));
    }

    private static boolean names(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static Rule<Character> is(char c) {
        return Rule.is(c);
    }

    private static Rule<Character> anyOf(String chars, String expected) {
        return Rule.when(expected, c -> chars.indexOf(c) >= 0);
    }

    private static Rule<Character> noneOf(String chars, String expected) {
        return Rule.when(expected, c -> chars.indexOf(c) < 0);
    }

    private static int newlines(String text) {
        return (int) text.chars().filter(c -> c == '\n').count();
    }

    /// Turns the parse tree into the AST, counting lines as it goes.
    ///
    /// The counter is threaded through the whole conversion rather than
    /// recomputed, so a command inside a `[substitution]` inside a braced body
    /// still reports the line of the file it was written in.
    private static final class Convert {

        private int line;

        private Convert(int first) {
            line = first;
        }

        private Script script(Tree<Character> tree) {
            var commands = new ArrayList<Command>();
            for (var child : ((Tree.Node<Character>) tree).children()) {
                if (child instanceof Tree.Node<Character> node && node.label().equals("command")) {
                    commands.add(command(node));
                    continue;
                }
                line += newlines(child.text());
            }
            return new Script(commands);
        }

        private Command command(Tree<Character> tree) {
            var at = line;
            var words = new ArrayList<Word>();
            for (var word : Trees.children(tree, "word")) words.add(word(word));
            return new Command(at, words);
        }

        private Word word(Tree<Character> tree) {
            var at = line;
            var braced = Trees.children(tree, "braced");
            if (!braced.isEmpty()) {
                var body = Trees.only(braced.getFirst(), "body").text();
                line += newlines(tree.text());
                return new Word(List.of(new Part.Text(body)), body, true, at);
            }
            var inner = Trees.children(tree, "quoted");
            var parts = parts(inner.isEmpty() ? Trees.children(tree, "bare").getFirst() : inner.getFirst());
            line += newlines(tree.text());
            return new Word(parts, "", false, at);
        }

        /// A quoted or bare word's pieces, in order. The line counter is not
        /// advanced here: the word advances it once, over its whole text.
        private List<Part> parts(Tree<Character> tree) {
            var parts = new ArrayList<Part>();
            for (var child : ((Tree.Node<Character>) tree).children()) {
                if (!(child instanceof Tree.Node<Character> node)) continue;
                switch (node.label()) {
                    case "text" -> parts.add(new Part.Text(node.text()));
                    case "escape" -> parts.add(new Part.Text(escaped(node.text())));
                    case "variable" -> parts.add(new Part.Variable(Trees.only(node, "name").text()));
                    case "substitution" -> parts.add(new Part.Substitution(nested(node)));
                    default -> { }
                }
            }
            return parts;
        }

        /// A `[script]`, converted with the counter where it stands so that its
        /// commands carry the lines they were written on.
        private Script nested(Tree<Character> node) {
            var before = line;
            var script = script(Trees.only(node, "script"));
            line = before;
            return script;
        }
    }

    /// What a backslash escape stands for. The letters Tcl gives a meaning to,
    /// and otherwise the character itself — which is how `\$`, `\[` and `\\`
    /// stop being syntax.
    private static String escaped(String source) {
        var c = source.charAt(1);
        return switch (c) {
            case 'n' -> "\n";
            case 't' -> "\t";
            case 'r' -> "\r";
            case '0' -> "\0";
            case '\n' -> " ";
            default -> String.valueOf(c);
        };
    }
}
