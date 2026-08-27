package argparse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import json.Json;
import peg.Parse;
import peg.Rule;

/// A command definition, read as a grammar over a stream of words.
///
/// This is the whole of the parsing: what a command line *is* — options in any
/// order, arguments in theirs, and everything after `--` taken as written — is
/// said once, as a [Rule], and [peg.Parser] does the rest. What is left over
/// here is only diagnosis: a rule that fails says where and what it wanted, and
/// [#explain] turns that into a sentence naming the word that was wrong.
public final class Grammar {

    /// An option or argument that was given, and what it was given as. A
    /// nameless one is a bare word whose argument is not known until the
    /// positions are counted up.
    record Found(String name, Json value) {}

    /// The subcommand a word selected.
    record Chosen(Command command) {}

    static final String POSITIONAL = "";

    private Grammar() {}

    /// The grammar of one command, and nothing after it.
    public static Rule<String> of(Command command) {
        return Rule.sequence(inner(command), Rule.end());
    }

    private static Rule<String> inner(Command command) {
        if (command.commands().isEmpty()) return body(command);
        var branches = new ArrayList<Rule<String>>();
        for (var child : command.commands()) {
            branches.add(Rule.sequence(
                    Rule.is(child.name()),
                    Rule.map(Rule.empty(), tree -> new Chosen(child)),
                    inner(child)));
        }
        branches.add(body(command));
        return Rule.sequence(Rule.star(Rule.choice(options(command))), Rule.choice(branches));
    }

    /// A command's own words: its options and arguments in any order, then
    /// whatever `--` hands over.
    ///
    /// A command that declares no arguments matches no bare words, so a word
    /// that was meant to be a subcommand fails here rather than being quietly
    /// accepted as something nobody asked for.
    private static Rule<String> body(Command command) {
        var either = new ArrayList<>(options(command));
        if (command.arguments().stream().anyMatch(argument -> argument.kind() != Argument.Kind.PASSTHROUGH)) {
            either.add(positional());
        }
        return Rule.sequence(Rule.star(Rule.choice(either)), passthrough(command));
    }

    private static List<Rule<String>> options(Command command) {
        return command.options().stream().map(Grammar::option).toList();
    }

    /// `--name`, `-n`, `--name value`, `--name=value`, `-n value`. Not `-nvalue`:
    /// a short form that swallows the rest of its word is how `-rf` becomes
    /// ambiguous, and nobody needs it.
    private static Rule<String> option(Option option) {
        var forms = new ArrayList<Rule<String>>();
        if (!option.takesValue()) {
            forms.add(Rule.map(Rule.is(option.written()), tree -> new Found(option.name(), Json.TRUE)));
            option.abbreviated().ifPresent(form ->
                    forms.add(Rule.map(Rule.is(form), tree -> new Found(option.name(), Json.TRUE))));
            return Rule.choice(forms);
        }
        var joined = option.written() + "=";
        forms.add(Rule.map(
                Rule.when(joined + "<value>", word -> word.startsWith(joined)),
                tree -> new Found(option.name(), Json.of(tree.text().substring(joined.length())))));
        forms.add(Rule.sequence(Rule.is(option.written()), value(option)));
        option.abbreviated().ifPresent(form -> forms.add(Rule.sequence(Rule.is(form), value(option))));
        return Rule.choice(forms);
    }

    /// The word after an option. It may not look like another option, so that a
    /// forgotten value is reported where it happened rather than swallowing the
    /// next flag.
    private static Rule<String> value(Option option) {
        return Rule.map(
                Rule.when("a value for " + option.written(), word -> !dashed(word)),
                tree -> new Found(option.name(), Json.of(tree.text())));
    }

    private static Rule<String> positional() {
        return Rule.map(
                Rule.when("an argument", word -> !dashed(word)),
                tree -> new Found(POSITIONAL, Json.of(tree.text())));
    }

    private static Rule<String> passthrough(Command command) {
        var name = command.arguments().stream()
                .filter(argument -> argument.kind() == Argument.Kind.PASSTHROUGH)
                .map(Argument::name)
                .findFirst()
                .orElse(POSITIONAL);
        var word = Rule.map(Rule.<String>any(), tree -> new Found(name, Json.of(tree.text())));
        return Rule.option(Rule.sequence(Rule.is("--"), Rule.star(word)));
    }

    /// A word that means an option rather than a value. A single dash is a word
    /// — it usually means standard input — and `--` is not, because ending the
    /// options is the one thing it does.
    static boolean dashed(String word) {
        return word.startsWith("-") && !word.equals("-");
    }

    /// Turns a parse failure into the sentence a person needs: which word was
    /// wrong, what kind of thing it was, and the nearest thing it might have
    /// been meant to be.
    static String explain(Command command, List<String> words, Parse.Failure<String> failure) {
        if (failure.position() >= words.size()) return incomplete(failure);
        var word = words.get(failure.position());
        if (dashed(word)) return "unknown option: " + word + suggestion(word, forms(command));
        if (!command.commands().isEmpty()) {
            return "unknown command: " + word + suggestion(word, command.commands().stream().map(Command::name).toList());
        }
        return "unexpected argument: " + word;
    }

    /// The input ran out in the middle of something. When that something was a
    /// value, say so plainly; a list of everything else that could have come
    /// next helps nobody.
    private static String incomplete(Parse.Failure<String> failure) {
        return failure.expected().stream()
                .filter(wanted -> wanted.startsWith("a value for "))
                .findFirst()
                .map(wanted -> wanted.substring("a value for ".length()) + " needs a value")
                .orElseGet(failure::message);
    }

    private static List<String> forms(Command command) {
        var forms = new ArrayList<String>();
        for (var option : command.options()) {
            forms.add(option.written());
            option.abbreviated().ifPresent(forms::add);
        }
        return forms;
    }

    /// A guess, when there is one close enough to be worth making. A wrong
    /// guess is worse than none, so the threshold is tight.
    private static String suggestion(String word, List<String> known) {
        return nearest(word, known).map(near -> " — did you mean " + near + "?").orElse("");
    }

    static Optional<String> nearest(String word, List<String> known) {
        var allowed = Math.max(2, word.length() / 4);
        return known.stream()
                .filter(candidate -> distance(word, candidate) <= allowed)
                .min(Comparator.comparingInt(candidate -> distance(word, candidate)));
    }

    /// Edit distance, counting a swapped pair of letters as one mistake rather
    /// than two — `dcos` is one slip away from `docs`, and treating it as two
    /// is how a suggestion fails to appear when it would have helped most.
    private static int distance(String from, String to) {
        var rows = new int[from.length() + 1][to.length() + 1];
        for (var row = 0; row <= from.length(); row++) rows[row][0] = row;
        for (var column = 0; column <= to.length(); column++) rows[0][column] = column;
        for (var row = 1; row <= from.length(); row++) {
            for (var column = 1; column <= to.length(); column++) {
                var same = from.charAt(row - 1) == to.charAt(column - 1);
                rows[row][column] = Math.min(
                        rows[row - 1][column - 1] + (same ? 0 : 1),
                        Math.min(rows[row - 1][column] + 1, rows[row][column - 1] + 1));
                if (row > 1 && column > 1
                        && from.charAt(row - 1) == to.charAt(column - 2)
                        && from.charAt(row - 2) == to.charAt(column - 1)) {
                    rows[row][column] = Math.min(rows[row][column], rows[row - 2][column - 2] + 1);
                }
            }
        }
        return rows[from.length()][to.length()];
    }
}
