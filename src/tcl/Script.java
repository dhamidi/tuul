package tcl;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/// A Tcl script represented as commands, words, and substitution parts.
///
/// Each command and word keeps its source line. A script also keeps the origin
/// that the host supplied for error reports and `info script`.
public record Script(List<Script.Command> commands, String origin) {

    public Script {
        commands = List.copyOf(commands);
        origin = origin == null ? "" : origin;
    }

    /// Creates a script with no origin.
    public Script(List<Command> commands) {
        this(commands, "");
    }

    /// Parses a complete script whose first source line is 1.
    public static Script parse(String source) {
        return parse(source, 1, "");
    }

    /// Parses a complete script with the specified first source line.
    public static Script parse(String source, int firstLine) {
        return parse(source, firstLine, "");
    }

    /// Parses a complete script and stores its source origin.
    public static Script parse(String source, int firstLine, String origin) {
        try {
            return Parser.parse(new StringReader(source), firstLine, origin);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Creates a script from words that already contain JVM values.
    public static Script lists(List<? extends List<?>> commands) {
        var result = new ArrayList<Command>();
        var line = 1;
        for (var command : commands) {
            var commandLine = line;
            var words = command.stream()
                    .map(value -> new Word(List.of(new Part.Value(value)), "", false, commandLine, Values.string(value)))
                    .toList();
            result.add(new Command(line++, words, Values.string(command)));
        }
        return new Script(result, "");
    }

    /// One command before substitution.
    public record Command(int line, List<Word> words, String source) {

        public Command {
            words = List.copyOf(words);
        }

        /// Creates a command when the original source is not available.
        public Command(int line, List<Word> words) {
            this(line, words, "");
        }
    }

    /// One word before substitution.
    public record Word(List<Part> parts, String body, boolean braced, int line, String source) {

        public Word {
            parts = List.copyOf(parts);
        }

        /// Creates a word when the original source is not available.
        public Word(List<Part> parts, String body, boolean braced, int line) {
            this(parts, body, braced, line, "");
        }
    }

    /// One literal or substituted part of a word.
    public sealed interface Part {

        /// Literal text after escape processing.
        record Text(String text) implements Part {}

        /// A variable name and its optional array index word.
        record Variable(String name, Word index) implements Part {}

        /// A nested script whose result replaces the bracketed source.
        record Substitution(Script script) implements Part {}

        /// A word whose list elements become separate command words.
        record Expanded(Word word) implements Part {}

        /// A JVM object that the host already substituted.
        record Value(Object object) implements Part {}
    }
}
