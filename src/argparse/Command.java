package argparse;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import json.Json;
import peg.Parse;
import peg.Parser;
import peg.Tree;

/// A command line, defined once.
///
/// The definition is the parser and the definition is the help — there is no
/// second copy of it in a string somewhere to drift out of date. A command is
/// built up by naming what it accepts:
///
/// ```
/// var docs = Command.named("docs", "describe a type")
///         .flag("json", "print the description as JSON")
///         .repeated("source-path", "where to look for sources")
///         .optional("symbol", "the type to describe");
/// ```
///
/// and then asked what a particular command line meant. What comes back is
/// JSON, because that is what tuul's applications take: a parsed command line
/// is already a message.
public record Command(
        String name,
        String summary,
        List<Option> options,
        List<Argument> arguments,
        List<Command> commands) {

    /// Every command answers `--help`, without anybody having to say so.
    public static final String HELP = "help";

    public Command {
        options = List.copyOf(options);
        arguments = List.copyOf(arguments);
        commands = List.copyOf(commands);
    }

    public static Command named(String name, String summary) {
        var help = new Option(HELP, 'h', "show this and stop", Option.Kind.FLAG, Optional.empty());
        return new Command(name, summary, List.of(help), List.of(), List.of());
    }

    public Command flag(String name, char letter, String description) {
        return with(new Option(name, letter, description, Option.Kind.FLAG, Optional.empty()));
    }

    public Command flag(String name, String description) {
        return flag(name, Option.NONE, description);
    }

    public Command value(String name, char letter, String description) {
        return with(new Option(name, letter, description, Option.Kind.VALUE, Optional.empty()));
    }

    public Command value(String name, String description) {
        return value(name, Option.NONE, description);
    }

    /// A value option that stands for something when it is not given.
    public Command value(String name, String description, String fallback) {
        return with(new Option(name, Option.NONE, description, Option.Kind.VALUE, Optional.of(Json.of(fallback))));
    }

    public Command repeated(String name, char letter, String description) {
        return with(new Option(name, letter, description, Option.Kind.REPEATED, Optional.empty()));
    }

    public Command repeated(String name, String description) {
        return repeated(name, Option.NONE, description);
    }

    public Command argument(String name, String description) {
        return with(new Argument(name, description, Argument.Kind.REQUIRED));
    }

    public Command optional(String name, String description) {
        return with(new Argument(name, description, Argument.Kind.OPTIONAL));
    }

    public Command rest(String name, String description) {
        return with(new Argument(name, description, Argument.Kind.VARIADIC));
    }

    /// Where everything after `--` goes.
    public Command passthrough(String name, String description) {
        return with(new Argument(name, description, Argument.Kind.PASSTHROUGH));
    }

    public Command command(Command child) {
        var children = new ArrayList<>(commands);
        children.add(child);
        return new Command(name, summary, options, arguments, children);
    }

    public Optional<Command> child(String word) {
        return commands.stream().filter(command -> command.name().equals(word)).findFirst();
    }

    public Parsed parse(String... words) {
        return parse(List.of(words));
    }

    /// Reads a command line. A failure names the word that was wrong rather
    /// than the rule that could not match it, because one of those is the
    /// user's business.
    public Parsed parse(List<String> words) {
        var deepest = deepest(words);
        return switch (Parser.parse(Grammar.of(this), words)) {
            case Parse.Failure<String> failure -> new Parsed.Failure(
                    deepest.path(), deepest.command(), Grammar.explain(deepest.command(), words, failure));
            case Parse.Ok<String> ok -> collect(ok.tree());
        };
    }

    public void help(Writer out) throws IOException {
        Usage.help(name, this, out);
    }

    private record Deeper(String path, Command command) {}

    /// The command the words were heading towards, for the help to be about and
    /// the suggestions to come from — even when the words never got there.
    private Deeper deepest(List<String> words) {
        var path = new StringBuilder(name);
        var command = this;
        for (var word : words) {
            var child = command.child(word);
            if (child.isEmpty()) break;
            command = child.get();
            path.append(' ').append(word);
        }
        return new Deeper(path.toString(), command);
    }

    /// Turns what the grammar found into what was meant: the chosen command,
    /// its options with their defaults, and its arguments in order.
    private Parsed collect(Tree<String> tree) {
        var path = new StringBuilder(name);
        var chain = new ArrayList<Command>(List.of(this));
        var found = new ArrayList<Grammar.Found>();
        var help = false;
        for (var value : tree.values()) {
            switch (value) {
                case Grammar.Chosen(var chosen) -> {
                    chain.add(chosen);
                    path.append(' ').append(chosen.name());
                }
                case Grammar.Found given when given.name().equals(HELP) -> help = true;
                case Grammar.Found given -> found.add(given);
                default -> {}
            }
        }
        var command = chain.getLast();
        if (help) return new Parsed.Help(path.toString(), command);
        return values(path.toString(), chain, found);
    }

    private Parsed values(String path, List<Command> chain, List<Grammar.Found> found) {
        var command = chain.getLast();
        var values = Json.Object.of();
        for (var option : declared(chain)) {
            var given = valuesOf(found, option.name());
            if (given.isEmpty()) {
                var absent = option.absent();
                if (absent.isPresent()) values = values.with(option.name(), absent.get());
                continue;
            }
            values = switch (option.kind()) {
                case FLAG -> values.with(option.name(), Json.TRUE);
                case VALUE -> values.with(option.name(), given.getLast());
                case REPEATED -> values.with(option.name(), Json.Array.of(given));
            };
        }

        var bare = valuesOf(found, Grammar.POSITIONAL);
        var taken = 0;
        for (var argument : command.arguments()) {
            if (argument.kind() == Argument.Kind.PASSTHROUGH) {
                values = values.with(argument.name(), Json.Array.of(valuesOf(found, argument.name())));
                continue;
            }
            if (argument.kind() == Argument.Kind.VARIADIC) {
                values = values.with(argument.name(), Json.Array.of(bare.subList(taken, bare.size())));
                taken = bare.size();
                continue;
            }
            if (taken < bare.size()) {
                values = values.with(argument.name(), bare.get(taken++));
                continue;
            }
            if (argument.kind() == Argument.Kind.REQUIRED) {
                return new Parsed.Failure(path, command, "missing <" + argument.name() + ">");
            }
        }
        if (taken < bare.size()) {
            return new Parsed.Failure(path, command, "unexpected argument: " + text(bare.get(taken)));
        }
        return new Parsed.Values(path, command, values);
    }

    /// The options of the whole chain, so a flag declared on a parent still
    /// belongs to the subcommand that was reached through it. A child that
    /// names an option again wins.
    private static List<Option> declared(List<Command> chain) {
        var options = new LinkedHashMap<String, Option>();
        for (var command : chain) {
            for (var option : command.options()) {
                if (!option.name().equals(HELP)) options.put(option.name(), option);
            }
        }
        return List.copyOf(options.values());
    }

    private static List<Json> valuesOf(List<Grammar.Found> found, String name) {
        return found.stream().filter(given -> given.name().equals(name)).map(Grammar.Found::value).toList();
    }

    private static String text(Json value) {
        return value instanceof Json.Str(var written) ? written : value.text();
    }

    private Command with(Option option) {
        var options = new ArrayList<>(this.options);
        options.add(option);
        return new Command(name, summary, options, arguments, commands);
    }

    private Command with(Argument argument) {
        var arguments = new ArrayList<>(this.arguments);
        arguments.add(argument);
        return new Command(name, summary, options, arguments, commands);
    }
}
