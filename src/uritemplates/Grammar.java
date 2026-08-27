package uritemplates;

import java.util.ArrayList;
import java.util.List;
import peg.Parse;
import peg.Parser;
import peg.Rule;
import peg.Tree;

/// The template syntax of RFC 6570 section 2, as a [Rule].
///
/// A template is a small grammar with one recursive shape and a lot of
/// character classes, which is exactly what a PEG is for: the ABNF in the RFC
/// and the rules below are close enough to read side by side, so a
/// disagreement with the spec is visible rather than buried in a hand-written
/// scanner.
///
/// The grammar is built once. Reading a template is then one parse, and
/// [Template] holds the result so that expanding it a thousand times parses
/// nothing.
final class Grammar {

    private static final Rule<Character> TEMPLATE = template();

    private Grammar() {}

    static List<Part> parse(String text) {
        return switch (Parser.parse(TEMPLATE, text)) {
            case Parse.Ok<Character>(var tree, var ignored) -> parts(tree);
            case Parse.Failure<Character> failure ->
                    throw new TemplateException(failure.message() + " in \"" + text + "\"");
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Part> parts(Tree<Character> tree) {
        return (List<Part>) (List<?>) tree.values();
    }

    private static Rule<Character> template() {
        return Rule.sequence(Rule.star(Rule.choice(expression(), literal())), Rule.end());
    }

    /// Text that is already URI: everything the ABNF allows, which is neither
    /// the braces that begin an expression nor the characters a URI may not
    /// carry. A bare `%` is not one of them — in a URI it begins a triple, so a
    /// template that means a literal percent has to say `%25`.
    private static Rule<Character> literal() {
        var character = Rule.choice(
                percent(),
                Rule.<Character>when("a literal character", Grammar::allowed));
        return Rule.map(Rule.plus(character), tree -> new Part.Literal(tree.text()));
    }

    private static Rule<Character> expression() {
        return Rule.map(
                Rule.sequence(
                        Rule.is('{'),
                        Rule.option(Rule.label("operator", Rule.when("an operator", Grammar::operator))),
                        Rule.label("variables", variables()),
                        Rule.is('}')),
                Grammar::expression);
    }

    private static Rule<Character> variables() {
        return Rule.sequence(varspec(), Rule.star(Rule.sequence(Rule.is(','), varspec())));
    }

    private static Rule<Character> varspec() {
        return Rule.map(
                Rule.sequence(
                        Rule.label("name", varname()),
                        Rule.option(Rule.choice(
                                Rule.label("prefix", Rule.sequence(Rule.is(':'), maxLength())),
                                Rule.label("explode", Rule.is('*'))))),
                Grammar::varspec);
    }

    private static Rule<Character> varname() {
        var character = Rule.choice(percent(), Rule.<Character>when("a variable character", Grammar::varchar));
        return Rule.sequence(character, Rule.star(Rule.sequence(Rule.option(Rule.is('.')), character)));
    }

    /// `%x31-39 0*3DIGIT` — a positive integer below 10000, which is the RFC's
    /// way of saying a prefix is a small number and not a length in disguise.
    /// The bound is the ABNF's, so `{var:10000}` is refused rather than
    /// silently obeyed.
    private static Rule<Character> maxLength() {
        return Rule.sequence(
                Rule.when("a digit from 1 to 9", character -> character >= '1' && character <= '9'),
                new Rule.Repeat<>(Rule.<Character>when("a digit", Character::isDigit), 0, 3));
    }

    private static Rule<Character> percent() {
        var hex = Rule.<Character>when("a hexadecimal digit", Encoder::hex);
        return Rule.sequence(Rule.is('%'), hex, hex);
    }

    private static Part expression(Tree<Character> tree) {
        var symbol = tree.node("operator").map(Tree::text).orElse("");
        var operator = symbol.isEmpty()
                ? Operator.SIMPLE
                : Operator.of(symbol.charAt(0)).orElseThrow(() -> new TemplateException(
                        "the operator " + symbol + " is reserved for an extension RFC 6570 never made"));
        var variables = new ArrayList<Varspec>();
        for (var value : tree.node("variables").orElseThrow().values()) variables.add((Varspec) value);
        return new Part.Expression(operator, variables);
    }

    private static Varspec varspec(Tree<Character> tree) {
        var name = tree.node("name").orElseThrow().text();
        var explode = tree.node("explode").isPresent();
        var prefix = tree.node("prefix").map(node -> Integer.parseInt(node.text().substring(1))).orElse(Varspec.WHOLE);
        return new Varspec(name, prefix, explode);
    }

    private static boolean operator(char character) {
        return Operator.of(character).isPresent() || Operator.FUTURE.indexOf(character) >= 0;
    }

    /// `ALPHA / DIGIT / "_"` — a variable name is narrower than the unreserved
    /// set, which is why this cannot just ask the encoder.
    private static boolean varchar(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '_';
    }

    /// The literal set of the ABNF. Everything above ASCII is allowed rather
    /// than spelled out as `ucschar` and `iprivate`: the excluded ranges there
    /// are surrogates and private-use areas that a URI template has no business
    /// carrying anyway, and the difference is not worth two hundred characters
    /// of ranges.
    private static boolean allowed(char character) {
        if (character > 0x7F) return true;
        return switch (character) {
            case 0x21, 0x23, 0x24, 0x26, 0x3D, 0x5D, 0x5F, 0x7E -> true;
            default -> (character >= 0x28 && character <= 0x3B)
                    || (character >= 0x3F && character <= 0x5B)
                    || (character >= 0x61 && character <= 0x7A);
        };
    }
}
