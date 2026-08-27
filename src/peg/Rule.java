package peg;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/// A parsing expression, as data.
///
/// The operators are the ones PEG has and no more: match an element, anything,
/// a sequence, an ordered choice, a repetition, a lookahead either way, and the
/// two that turn a match into something — a label, and a mapping. The set is
/// closed, so it is a sealed interface, and [Parser] is the one thing that
/// knows what the operators mean.
///
/// Ordered choice is what makes this a PEG rather than a grammar: the first
/// alternative that matches wins and the rest are never tried, so a rule has
/// exactly one parse and there is no ambiguity to resolve later.
///
/// Left recursion does not work here and cannot be made to: a rule that begins
/// by asking for itself never gets past the asking. Write the repetition out
/// with [#star] or [#plus] instead, which is what left recursion was for.
public sealed interface Rule<T> {

    /// Matches nothing at all, successfully. The identity of a sequence.
    record Empty<T>() implements Rule<T> {}

    /// Matches only where the input has run out.
    record End<T>() implements Rule<T> {}

    /// Matches one element, whatever it is.
    record Any<T>() implements Rule<T> {}

    /// Matches one element that satisfies the test. `expected` is what the
    /// failure will say, so it should read as a noun phrase.
    record Match<T>(String expected, Predicate<T> test) implements Rule<T> {}

    /// All of them, in order, or none of them.
    record Sequence<T>(List<Rule<T>> rules) implements Rule<T> {

        public Sequence {
            rules = List.copyOf(rules);
        }
    }

    /// The first one that matches. Order is meaning: put the longer alternative
    /// first, or it will never be reached.
    record Choice<T>(List<Rule<T>> rules) implements Rule<T> {

        public Choice {
            rules = List.copyOf(rules);
        }
    }

    /// Between `least` and `most` of them, as many as there are.
    record Repeat<T>(Rule<T> rule, int least, int most) implements Rule<T> {}

    /// Matches where the rule would match, and consumes nothing.
    record And<T>(Rule<T> rule) implements Rule<T> {}

    /// Matches where the rule would not, and consumes nothing.
    record Not<T>(Rule<T> rule) implements Rule<T> {}

    /// Gathers what the rule matched under a name.
    record Label<T>(String label, Rule<T> rule) implements Rule<T> {}

    /// Turns what the rule matched into a value, which stands in the tree where
    /// the match was.
    record Map<T>(Rule<T> rule, Function<Tree<T>, Object> action) implements Rule<T> {}

    /// A rule named now and supplied later, which is how a grammar refers to
    /// itself without having to exist first.
    record Reference<T>(String name, Supplier<Rule<T>> rule) implements Rule<T> {}

    static <T> Rule<T> empty() {
        return new Empty<>();
    }

    static <T> Rule<T> end() {
        return new End<>();
    }

    static <T> Rule<T> any() {
        return new Any<>();
    }

    /// Matches an element equal to this one.
    static <T> Rule<T> is(T value) {
        return new Match<>(String.valueOf(value), value::equals);
    }

    static <T> Rule<T> when(String expected, Predicate<T> test) {
        return new Match<>(expected, test);
    }

    @SafeVarargs
    static <T> Rule<T> sequence(Rule<T>... rules) {
        return new Sequence<>(List.of(rules));
    }

    static <T> Rule<T> sequence(List<Rule<T>> rules) {
        return new Sequence<>(rules);
    }

    @SafeVarargs
    static <T> Rule<T> choice(Rule<T>... rules) {
        return new Choice<>(List.of(rules));
    }

    static <T> Rule<T> choice(List<Rule<T>> rules) {
        return new Choice<>(rules);
    }

    static <T> Rule<T> star(Rule<T> rule) {
        return new Repeat<>(rule, 0, Integer.MAX_VALUE);
    }

    static <T> Rule<T> plus(Rule<T> rule) {
        return new Repeat<>(rule, 1, Integer.MAX_VALUE);
    }

    static <T> Rule<T> option(Rule<T> rule) {
        return new Repeat<>(rule, 0, 1);
    }

    static <T> Rule<T> and(Rule<T> rule) {
        return new And<>(rule);
    }

    static <T> Rule<T> not(Rule<T> rule) {
        return new Not<>(rule);
    }

    static <T> Rule<T> label(String label, Rule<T> rule) {
        return new Label<>(label, rule);
    }

    static <T> Rule<T> map(Rule<T> rule, Function<Tree<T>, Object> action) {
        return new Map<>(rule, action);
    }

    static <T> Rule<T> reference(String name, Supplier<Rule<T>> rule) {
        return new Reference<>(name, rule);
    }
}
