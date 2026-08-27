package peg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/// Runs a [Rule] over an [Input]. The one thing that knows what the operators
/// mean.
///
/// While it runs it remembers the furthest position anything reached and what
/// was expected there. Ordered choice throws most of its work away — an
/// alternative that got eight elements in before failing leaves no trace in the
/// alternative that matched two — and that discarded work is exactly what a
/// person needs to be told about when nothing matches at all.
///
/// A parse stops where the rule stops. It is the grammar's business to say that
/// the input has to be used up, with [Rule#end()], so that a parser can also be
/// used to read one thing off the front of a stream.
public final class Parser<T> {

    private final Input<T> input;
    private final Map<Rule.Reference<T>, Rule<T>> resolved = new HashMap<>();
    private final Set<String> expected = new LinkedHashSet<>();
    private int furthest;

    private Parser(Input<T> input) {
        this.input = input;
    }

    public static <T> Parse<T> parse(Rule<T> rule, Input<T> input) {
        var parser = new Parser<>(input);
        var progress = parser.apply(rule, 0);
        return progress == null
                ? new Parse.Failure<>(parser.furthest, List.copyOf(parser.expected))
                : new Parse.Ok<>(new Tree.Node<>("", progress.trees()), progress.at());
    }

    public static <T> Parse<T> parse(Rule<T> rule, List<T> input) {
        return parse(rule, Input.of(input));
    }

    public static Parse<Character> parse(Rule<Character> rule, String text) {
        return parse(rule, Input.characters(text));
    }

    /// What one rule made where it was applied, or null where it did not apply.
    /// Null rather than an empty Optional because every operator asks this
    /// question and most of them ask it in a loop.
    private record Progress<T>(List<Tree<T>> trees, int at) {}

    private Progress<T> apply(Rule<T> rule, int at) {
        return switch (rule) {
            case Rule.Empty<T> ignored -> new Progress<>(List.of(), at);
            case Rule.End<T> ignored -> input.has(at) ? failed(at, "end of input") : new Progress<>(List.of(), at);
            case Rule.Any<T> ignored -> input.has(at)
                    ? new Progress<>(List.of(new Tree.Leaf<>(input.at(at))), at + 1)
                    : failed(at, "anything");
            case Rule.Match<T>(var wanted, var test) -> input.has(at) && test.test(input.at(at))
                    ? new Progress<>(List.of(new Tree.Leaf<>(input.at(at))), at + 1)
                    : failed(at, wanted);
            case Rule.Sequence<T>(var rules) -> sequence(rules, at);
            case Rule.Choice<T>(var rules) -> choice(rules, at);
            case Rule.Repeat<T>(var repeated, var least, var most) -> repeat(repeated, least, most, at);
            case Rule.And<T>(var inner) -> and(inner, at);
            case Rule.Not<T>(var inner) -> not(inner, at);
            case Rule.Label<T>(var label, var inner) -> label(label, inner, at);
            case Rule.Map<T>(var inner, var action) -> map(inner, action, at);
            case Rule.Reference<T> reference -> apply(resolved.computeIfAbsent(reference, named -> named.rule().get()), at);
        };
    }

    private Progress<T> sequence(List<Rule<T>> rules, int at) {
        var trees = new ArrayList<Tree<T>>();
        var position = at;
        for (var rule : rules) {
            var progress = apply(rule, position);
            if (progress == null) return null;
            trees.addAll(progress.trees());
            position = progress.at();
        }
        return new Progress<>(trees, position);
    }

    private Progress<T> choice(List<Rule<T>> rules, int at) {
        for (var rule : rules) {
            var progress = apply(rule, at);
            if (progress != null) return progress;
        }
        return null;
    }

    private Progress<T> repeat(Rule<T> rule, int least, int most, int at) {
        var trees = new ArrayList<Tree<T>>();
        var position = at;
        var count = 0;
        while (count < most) {
            var progress = apply(rule, position);
            if (progress == null) break;
            trees.addAll(progress.trees());
            count++;
            if (progress.at() == position) break;
            position = progress.at();
        }
        return count < least ? null : new Progress<>(trees, position);
    }

    /// A lookahead that succeeded proves nothing about what comes next, so what
    /// it expected on the way is forgotten. One that failed is the reason the
    /// parse stopped, so what it expected is kept.
    private Progress<T> and(Rule<T> rule, int at) {
        var mark = mark();
        var progress = apply(rule, at);
        if (progress == null) return null;
        restore(mark);
        return new Progress<>(List.of(), at);
    }

    private Progress<T> not(Rule<T> rule, int at) {
        var mark = mark();
        var progress = apply(rule, at);
        restore(mark);
        return progress == null ? new Progress<>(List.of(), at) : failed(at, "not " + expectation(rule));
    }

    private Progress<T> label(String label, Rule<T> rule, int at) {
        var progress = apply(rule, at);
        return progress == null
                ? null
                : new Progress<>(List.of(new Tree.Node<>(label, progress.trees())), progress.at());
    }

    private Progress<T> map(Rule<T> rule, Function<Tree<T>, Object> action, int at) {
        var progress = apply(rule, at);
        return progress == null
                ? null
                : new Progress<>(List.of(new Tree.Value<>(action.apply(new Tree.Node<>("", progress.trees())))),
                        progress.at());
    }

    /// Records where the parse could not go on. Only the furthest such place is
    /// kept: everything before it was recovered from.
    private Progress<T> failed(int at, String wanted) {
        if (at > furthest) {
            furthest = at;
            expected.clear();
        }
        if (at == furthest) expected.add(wanted);
        return null;
    }

    private record Mark(int furthest, List<String> expected) {}

    private Mark mark() {
        return new Mark(furthest, List.copyOf(expected));
    }

    private void restore(Mark mark) {
        furthest = mark.furthest();
        expected.clear();
        expected.addAll(mark.expected());
    }

    /// What a rule would say it wanted, for the failures that are about a rule
    /// rather than an element.
    private static <T> String expectation(Rule<T> rule) {
        return switch (rule) {
            case Rule.Match<T>(var wanted, var ignored) -> wanted;
            case Rule.Label<T>(var label, var ignored) -> label;
            case Rule.Reference<T>(var name, var ignored) -> name;
            case Rule.Any<T> ignored -> "anything";
            case Rule.End<T> ignored -> "end of input";
            default -> "that";
        };
    }
}
