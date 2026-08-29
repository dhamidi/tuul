package tcl;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;

/// Reads lines, groups complete Tcl commands, and evaluates each command.
///
/// The caller owns every reader and writer. This type flushes output after a
/// prompt, result, or failure. It does not close a stream.
public final class Repl {

    private final Evaluator evaluator;
    private final Completer completer;
    private final String primaryPrompt;
    private final String continuationPrompt;
    private final ResultWriter resultWriter;
    private final ErrorWriter errorWriter;

    /// Evaluates one complete command from its own reader.
    @FunctionalInterface
    public interface Evaluator {

        /// Evaluates one complete command and returns its result.
        Object eval(Reader command) throws Exception;
    }

    /// Reports whether a source fragment is a complete command.
    @FunctionalInterface
    public interface Completer {

        /// Returns true when the source can reach the evaluator.
        boolean complete(String source);
    }

    /// Writes one successful evaluator result.
    @FunctionalInterface
    public interface ResultWriter {

        /// Writes one successful evaluator result, which can be null or empty.
        void write(Object result, Writer output) throws IOException;
    }

    /// Writes one evaluator or incomplete-input failure.
    @FunctionalInterface
    public interface ErrorWriter {

        /// Writes one evaluator or incomplete-input failure.
        void write(Exception failure, Writer output) throws IOException;
    }

    /// Counts evaluator calls and failures from one run.
    public record Result(long evaluated, long failures, boolean incomplete) {

        /// Returns true when no command failed and no input remained incomplete.
        public boolean successful() {
            return failures == 0 && !incomplete;
        }
    }

    private Repl(Evaluator evaluator, Completer completer, String primaryPrompt, String continuationPrompt,
            ResultWriter resultWriter, ErrorWriter errorWriter) {
        if (evaluator == null || completer == null || resultWriter == null || errorWriter == null) {
            throw new NullPointerException();
        }
        this.evaluator = evaluator;
        this.completer = completer;
        this.primaryPrompt = primaryPrompt == null ? "" : primaryPrompt;
        this.continuationPrompt = continuationPrompt == null ? "" : continuationPrompt;
        this.resultWriter = resultWriter;
        this.errorWriter = errorWriter;
    }

    /// Creates a REPL with Tcl completeness rules and no prompts.
    public static Repl of(Evaluator evaluator) {
        return of(evaluator, Repl::complete);
    }

    /// Creates a REPL with the specified completeness rule and no prompts.
    public static Repl of(Evaluator evaluator, Completer completer) {
        return new Repl(evaluator, completer, "", "", Repl::writeResult, Repl::writeFailure);
    }

    /// Returns whether source has no open brace, quote, or command substitution.
    public static boolean complete(String source) {
        var braces = 0;
        var quote = false;
        var comment = false;
        var commandStart = true;
        var wordStart = true;
        var brackets = new java.util.ArrayDeque<ScanContext>();
        for (var at = 0; at < source.length(); at++) {
            var character = source.charAt(at);
            if (comment) {
                if (character == '\n') {
                    comment = false;
                    commandStart = true;
                    wordStart = true;
                }
                continue;
            }
            if (character == '\\') {
                var wasCommandStart = commandStart;
                if (++at < source.length() && source.charAt(at) == '\n') {
                    while (at + 1 < source.length() && (source.charAt(at + 1) == ' ' || source.charAt(at + 1) == '\t')) at++;
                    commandStart = wasCommandStart;
                    wordStart = true;
                } else {
                    commandStart = false;
                    wordStart = false;
                }
                continue;
            }
            if (braces > 0) {
                if (character == '{') braces++;
                else if (character == '}') braces--;
                continue;
            }
            if (!quote && commandStart && character == '#') {
                comment = true;
                continue;
            }
            if (quote && character == '"') {
                quote = false;
                commandStart = false;
                wordStart = false;
                continue;
            }
            if (!quote && wordStart && source.startsWith("{*}", at)) {
                at += 2;
                continue;
            }
            if (!quote && wordStart && character == '"') {
                quote = true;
                commandStart = false;
                wordStart = false;
                continue;
            }
            if (!quote && wordStart && character == '{') {
                braces++;
                commandStart = false;
                wordStart = false;
                continue;
            }
            if (character == '[') {
                brackets.addLast(new ScanContext(quote, commandStart, wordStart));
                quote = false;
                commandStart = true;
                wordStart = true;
                continue;
            }
            if (!quote && character == ']' && !brackets.isEmpty()) {
                var context = brackets.removeLast();
                quote = context.quote;
                commandStart = false;
                wordStart = false;
                continue;
            }
            if (!quote && (character == '\n' || character == ';')) {
                commandStart = true;
                wordStart = true;
            } else if (!quote && Character.isWhitespace(character)) {
                wordStart = true;
            } else if (!Character.isWhitespace(character)) {
                commandStart = false;
                wordStart = false;
            }
        }
        return braces == 0 && brackets.isEmpty() && !quote;
    }

    private record ScanContext(boolean quote, boolean commandStart, boolean wordStart) {}

    /// Returns a copy that uses the specified primary and continuation prompts.
    public Repl prompts(String primary, String continuation) {
        return new Repl(evaluator, completer, primary, continuation, resultWriter, errorWriter);
    }

    /// Returns a copy with `% ` and `> ` prompts.
    public Repl interactive() {
        return prompts("% ", "> ");
    }

    /// Returns a copy with no prompts.
    public Repl noPrompts() {
        return prompts("", "");
    }

    /// Returns a copy that writes successful results with `writer`.
    public Repl results(ResultWriter writer) {
        return new Repl(evaluator, completer, primaryPrompt, continuationPrompt, writer, errorWriter);
    }

    /// Returns a copy that writes failures with `writer`.
    public Repl failures(ErrorWriter writer) {
        return new Repl(evaluator, completer, primaryPrompt, continuationPrompt, resultWriter, writer);
    }

    /// Reads to EOF and writes results and failures to one writer.
    public Result run(Reader input, Writer output) throws IOException {
        return run(input, output, output);
    }

    /// Reads to EOF and writes results and failures to separate writers.
    public Result run(Reader input, Writer output, Writer errors) throws IOException {
        var command = new StringBuilder();
        long evaluated = 0;
        long failures = 0;
        while (true) {
            prompt(command.isEmpty() ? primaryPrompt : continuationPrompt, output);
            var line = readLine(input);
            if (line == null) break;
            command.append(line);
            if (!completer.complete(command.toString())) continue;
            if (!command.toString().isBlank()) {
                evaluated++;
                try {
                    var result = evaluator.eval(new StringReader(command.toString()));
                    resultWriter.write(result, output);
                    output.flush();
                } catch (Exception failure) {
                    failures++;
                    errorWriter.write(failure, errors);
                    errors.flush();
                }
            }
            command.setLength(0);
        }
        var incomplete = !command.isEmpty() && !completer.complete(command.toString());
        if (incomplete) {
            failures++;
            errorWriter.write(new IllegalArgumentException("incomplete command"), errors);
            errors.flush();
        }
        return new Result(evaluated, failures, incomplete);
    }

    private static String readLine(Reader input) throws IOException {
        var line = new StringBuilder();
        while (true) {
            var character = input.read();
            if (character < 0) return line.isEmpty() ? null : line.toString();
            line.append((char) character);
            if (character == '\n') return line.toString();
        }
    }

    private static void prompt(String prompt, Writer output) throws IOException {
        if (prompt.isEmpty()) return;
        output.write(prompt);
        output.flush();
    }

    private static void writeResult(Object result, Writer output) throws IOException {
        if (result == null || Values.string(result).isEmpty()) return;
        output.write(Values.string(result));
        output.write('\n');
    }

    private static void writeFailure(Exception failure, Writer output) throws IOException {
        output.write("error: ");
        output.write(failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage());
        output.write('\n');
    }
}
