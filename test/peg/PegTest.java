package peg;

import harness.Check;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

public final class PegTest {

    private PegTest() {}

    public static void run() {
        elements();
        composition();
        repetition();
        lookahead();
        trees();
        values();
        recursion();
        failures();
        streaming();
    }

    private static void elements() {
        Check.that("an element matches itself", ok(Rule.is('a'), "a"));
        Check.that("and nothing else", !ok(Rule.is('a'), "b"));
        Check.that("a predicate matches what it accepts", ok(digit(), "7"));
        Check.that("anything matches whatever is there", ok(Rule.<Character>any(), "!"));
        Check.that("but not what is not there", !ok(Rule.<Character>any(), ""));
        Check.that("the end matches only where the input stops", ok(Rule.<Character>end(), ""));
        Check.that("and not before it", !ok(Rule.<Character>end(), "a"));
        Check.that("nothing at all always matches", ok(Rule.<Character>empty(), ""));
    }

    private static void composition() {
        var greeting = Rule.sequence(Rule.is('h'), Rule.is('i'));
        Check.that("a sequence matches all of it", ok(greeting, "hi"));
        Check.that("or none of it", !ok(greeting, "ho"));

        var either = Rule.choice(Rule.is('a'), Rule.is('b'));
        Check.that("a choice takes the first alternative that matches", ok(either, "a"));
        Check.that("and goes on to the next when it does not", ok(either, "b"));

        /// Ordered choice means the first alternative wins even when a later
        /// one would have matched more — the price of having exactly one parse.
        var shortFirst = Rule.sequence(Rule.choice(Rule.is('a'), Rule.sequence(Rule.is('a'), Rule.is('b'))), Rule.end());
        var longFirst = Rule.sequence(Rule.choice(Rule.sequence(Rule.is('a'), Rule.is('b')), Rule.is('a')), Rule.end());
        Check.that("order is meaning: the short alternative first shadows the long one", !ok(shortFirst, "ab"));
        Check.that("and the long one first leaves the short one reachable", ok(longFirst, "ab") && ok(longFirst, "a"));
    }

    private static void repetition() {
        Check.that("zero or more accepts none", ok(Rule.sequence(Rule.star(Rule.is('a')), Rule.end()), ""));
        Check.that("and accepts many", ok(Rule.sequence(Rule.star(Rule.is('a')), Rule.end()), "aaaa"));
        Check.that("one or more insists on one", !ok(Rule.sequence(Rule.plus(Rule.is('a')), Rule.end()), ""));
        Check.that("an optional takes at most one",
                ok(Rule.sequence(Rule.option(Rule.is('a')), Rule.is('a'), Rule.end()), "aa"));

        /// A rule that matches without consuming would repeat until the heat
        /// death of the universe, so a repetition stops when it stops moving.
        var going = Rule.sequence(Rule.star(Rule.<Character>empty()), Rule.end());
        Check.that("a repetition of nothing terminates", ok(going, ""));
    }

    private static void lookahead() {
        var followedByB = Rule.sequence(Rule.is('a'), Rule.and(Rule.is('b')), Rule.any(), Rule.end());
        Check.that("a lookahead matches without consuming", ok(followedByB, "ab"));
        Check.that("and fails where what follows is wrong", !ok(followedByB, "ac"));

        var notC = Rule.sequence(Rule.not(Rule.is('c')), Rule.any(), Rule.end());
        Check.that("a negative lookahead matches what is not there", ok(notC, "a"));
        Check.that("and refuses what is", !ok(notC, "c"));
    }

    private static void trees() {
        var word = Rule.label("word", Rule.plus(letter()));
        var two = Rule.sequence(word, Rule.is(' '), word, Rule.end());
        var parse = Parser.parse(two, "ab cd");
        Check.that("a parse produces a tree", parse instanceof Parse.Ok);

        var tree = ((Parse.Ok<Character>) parse).tree();
        Check.equal("labels gather what they matched",
                List.of("ab", "cd"),
                tree.nodes("word").stream().map(Tree::text).toList());
        Check.equal("and the leaves are the elements that matched", "ab cd", tree.text());
        Check.equal("a label that was not used finds nothing", List.of(), tree.nodes("sentence"));
        Check.that("a label that was used is found", tree.node("word").isPresent());
    }

    private static void values() {
        var number = Rule.map(Rule.plus(digit()), tree -> Integer.valueOf(tree.text()));
        var sum = Rule.sequence(number, Rule.star(Rule.sequence(Rule.is('+'), number)), Rule.end());
        var parse = Parser.parse(sum, "12+3+7");
        Check.that("a grammar can map matches into values", parse instanceof Parse.Ok);
        Check.equal("which stand in the tree where the match was",
                List.of(12, 3, 7),
                ((Parse.Ok<Character>) parse).tree().values());
        Check.equal("the elements a mapped rule matched are gone, and only those",
                "++",
                ((Parse.Ok<Character>) parse).tree().text());
    }

    /// A rule that refers to itself is a rule that does not exist yet, which is
    /// what a reference is for.
    private static void recursion() {
        var nested = new AtomicReference<Rule<Character>>();
        Rule<Character> inner = Rule.reference("nested", nested::get);
        nested.set(Rule.choice(Rule.sequence(Rule.is('('), inner, Rule.is(')')), Rule.is('x')));
        var whole = Rule.sequence(inner, Rule.<Character>end());
        Check.that("a grammar can refer to itself", ok(whole, "(((x)))"));
        Check.that("and still says no", !ok(whole, "(((x))"));
    }

    private static void failures() {
        var sum = Rule.sequence(Rule.plus(digit()), Rule.is('+'), Rule.plus(digit()), Rule.end());
        var parse = Parser.parse(sum, "12+");
        Check.that("an incomplete parse fails", parse instanceof Parse.Failure);

        var failure = (Parse.Failure<Character>) parse;
        Check.equal("at the furthest position anything reached", 3, failure.position());
        Check.equal("saying what would have let it go on", List.of("a digit"), failure.expected());
        Check.equal("which reads as a sentence", "expected a digit at 3", failure.message());

        /// The alternative that got furthest is the one worth reporting, even
        /// though a shorter one is what the choice settled on.
        var either = Rule.sequence(
                Rule.choice(Rule.sequence(Rule.is('a'), Rule.is('b'), Rule.is('c')), Rule.is('a')),
                Rule.is('!'));
        var report = (Parse.Failure<Character>) Parser.parse(either, "abx");
        Check.equal("a failure remembers the alternative that got furthest", 2, report.position());
        Check.equal("and what that one wanted", List.of("c"), report.expected());
    }

    /// The stream is pulled as the parse asks for it, which is the difference
    /// between parsing a stream and reading one into a list first.
    private static void streaming() {
        var pulled = new ArrayList<Character>();
        var input = Input.of(counting("aaabbbbbbbbbb", pulled));
        var parse = Parser.parse(Rule.plus(Rule.is('a')), input);
        Check.that("a parse over a stream succeeds", parse instanceof Parse.Ok);
        Check.equal("having read only what it matched and the one that stopped it", 4, pulled.size());
        Check.equal("which is all the input admits to holding", 4, input.read());
        Check.equal("and it knows where it stopped", 3, ((Parse.Ok<Character>) parse).position());

        var reader = Input.characters(new StringReader("hi"));
        Check.that("characters can come from a reader", ok(Rule.sequence(Rule.is('h'), Rule.is('i'), Rule.end()), reader));
    }

    private static Rule<Character> digit() {
        return Rule.when("a digit", character -> character >= '0' && character <= '9');
    }

    private static Rule<Character> letter() {
        return Rule.when("a letter", Character::isLetter);
    }

    private static boolean ok(Rule<Character> rule, String text) {
        return Parser.parse(rule, text) instanceof Parse.Ok;
    }

    private static boolean ok(Rule<Character> rule, Input<Character> input) {
        return Parser.parse(rule, input) instanceof Parse.Ok;
    }

    private static Iterator<Character> counting(String text, List<Character> pulled) {
        return new Iterator<>() {

            private int position;

            @Override
            public boolean hasNext() {
                return position < text.length();
            }

            @Override
            public Character next() {
                if (!hasNext()) throw new NoSuchElementException("end of input");
                var character = text.charAt(position++);
                pulled.add(character);
                return character;
            }
        };
    }
}
