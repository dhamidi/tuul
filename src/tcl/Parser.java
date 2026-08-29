package tcl;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import tcl.Script.Command;
import tcl.Script.Part;
import tcl.Script.Word;

/// Reads Tcl source one command at a time.
final class Parser {

    private Parser() {}

    static Script parse(Reader reader, int firstLine, String origin) throws IOException {
        var source = new Source(reader, firstLine);
        var commands = new ArrayList<Command>();
        for (var command = next(source, origin, false); command != null; command = next(source, origin, false)) {
            commands.add(command);
        }
        return new Script(commands, origin);
    }

    static Command next(Source source, String origin, boolean bracketed) throws IOException {
        skipBeforeCommand(source, bracketed);
        if (source.peek() < 0 || bracketed && source.peek() == ']') return null;
        if (!bracketed) source.clearHistory();
        var commandStart = source.mark();
        var line = source.line();
        var words = new ArrayList<Word>();
        var raw = new StringBuilder();
        while (true) {
            skipBlanks(source, raw);
            var next = source.peek();
            if (next < 0 || next == '\n' || next == ';' || bracketed && next == ']') break;
            words.add(word(source, origin, bracketed));
            if (!words.getLast().source().isEmpty()) raw.append(words.getLast().source());
            next = source.peek();
            if (next >= 0 && next != '\n' && next != ';' && !(bracketed && next == ']')
                    && !blank(next)) error("extra characters after close-quote or close-brace", source.line());
        }
        var commandSource = source.since(commandStart).strip();
        if (source.peek() == '\n' || source.peek() == ';') source.read();
        return new Command(line, words, commandSource);
    }

    private static void skipBeforeCommand(Source source, boolean bracketed) throws IOException {
        while (true) {
            while (blank(source.peek()) || source.peek() == '\n' || source.peek() == ';') source.read();
            if (source.peek() != '#') return;
            while (source.peek() >= 0 && source.read() != '\n') { }
            if (bracketed && source.peek() == ']') return;
        }
    }

    private static void skipBlanks(Source source, StringBuilder raw) throws IOException {
        while (blank(source.peek())) raw.append((char) source.read());
    }

    private static Word word(Source source, String origin, boolean bracketed) throws IOException {
        var start = source.mark();
        var line = source.line();
        final Word parsed;
        if (source.peek() == '{') {
            if (startsExpansion(source)) {
                var marker = source.take(3);
                var expanded = word(source, origin, bracketed);
                parsed = new Word(List.of(new Part.Expanded(expanded)), "", false, line, marker + expanded.source());
                return withSource(parsed, source.since(start));
            }
            parsed = braced(source, line);
        } else if (source.peek() == '"') {
            parsed = quoted(source, origin, line);
        } else {
            parsed = bare(source, origin, line, bracketed);
        }
        return withSource(parsed, source.since(start));
    }

    private static Word withSource(Word word, String source) {
        return new Word(word.parts(), word.body(), word.braced(), word.line(), source);
    }

    private static boolean startsExpansion(Source source) throws IOException {
        return source.peek(0) == '{' && source.peek(1) == '*' && source.peek(2) == '}';
    }

    private static Word braced(Source source, int line) throws IOException {
        var raw = new StringBuilder();
        var body = new StringBuilder();
        raw.append((char) source.read());
        var depth = 1;
        while (source.peek() >= 0) {
            var character = source.read();
            raw.append((char) character);
            if (character == '\\') {
                if (source.peek() == '\n') {
                    raw.append((char) source.read());
                    while (source.peek() == ' ' || source.peek() == '\t') raw.append((char) source.read());
                    body.append(' ');
                } else {
                    body.append('\\');
                    if (source.peek() >= 0) {
                        var escaped = source.read();
                        raw.append((char) escaped);
                        body.append((char) escaped);
                    }
                }
                continue;
            }
            if (character == '{') depth++;
            if (character == '}') {
                depth--;
                if (depth == 0) return new Word(List.of(new Part.Text(body.toString())), body.toString(), true, line, raw.toString());
            }
            body.append((char) character);
        }
        error("unclosed brace", line);
        return null;
    }

    private static Word quoted(Source source, String origin, int line) throws IOException {
        var raw = new StringBuilder();
        var parts = new ArrayList<Part>();
        raw.append((char) source.read());
        var text = new StringBuilder();
        while (source.peek() >= 0 && source.peek() != '"') part(source, origin, true, text, parts, raw);
        if (source.peek() < 0) error("unclosed quote", line);
        raw.append((char) source.read());
        flush(text, parts);
        return new Word(parts, "", false, line, raw.toString());
    }

    private static Word bare(Source source, String origin, int line, boolean bracketed) throws IOException {
        var raw = new StringBuilder();
        var parts = new ArrayList<Part>();
        var text = new StringBuilder();
        while (source.peek() >= 0 && !blank(source.peek()) && source.peek() != '\n' && source.peek() != ';'
                && !(bracketed && source.peek() == ']')) {
            part(source, origin, false, text, parts, raw);
        }
        flush(text, parts);
        return new Word(parts, "", false, line, raw.toString());
    }

    private static void part(Source source, String origin, boolean quoted, StringBuilder text,
            List<Part> parts, StringBuilder raw) throws IOException {
        if (source.peek() == '\\') {
            raw.append((char) source.read());
            text.append(escape(source, raw));
            return;
        }
        if (source.peek() == '$') {
            flush(text, parts);
            parts.add(variable(source, origin, raw));
            return;
        }
        if (source.peek() == '[') {
            flush(text, parts);
            raw.append((char) source.read());
            var commands = new ArrayList<Command>();
            for (var command = next(source, origin, true); command != null; command = next(source, origin, true)) commands.add(command);
            if (source.peek() != ']') error("unclosed command substitution", source.line());
            raw.append((char) source.read());
            parts.add(new Part.Substitution(new Script(commands, origin)));
            return;
        }
        if (quoted && source.peek() == '"') return;
        var character = source.read();
        raw.append((char) character);
        text.append((char) character);
    }

    private static Part.Variable variable(Source source, String origin, StringBuilder raw) throws IOException {
        raw.append((char) source.read());
        if (source.peek() == '{') {
            raw.append((char) source.read());
            var name = new StringBuilder();
            while (source.peek() >= 0 && source.peek() != '}') {
                var character = source.read();
                raw.append((char) character);
                name.append((char) character);
            }
            if (source.peek() != '}') error("unclosed variable name", source.line());
            raw.append((char) source.read());
            return new Part.Variable(name.toString(), null);
        }
        var name = new StringBuilder();
        while (nameCharacter(source.peek()) || source.peek() == ':') {
            var character = source.read();
            raw.append((char) character);
            name.append((char) character);
        }
        if (name.isEmpty()) {
            raw.append('$');
            return new Part.Variable("$", null);
        }
        Word index = null;
        if (source.peek() == '(') {
            raw.append((char) source.read());
            var indexRaw = new StringBuilder();
            var parts = new ArrayList<Part>();
            var text = new StringBuilder();
            while (source.peek() >= 0 && source.peek() != ')') part(source, origin, false, text, parts, indexRaw);
            if (source.peek() != ')') error("unclosed array index", source.line());
            raw.append(indexRaw).append((char) source.read());
            flush(text, parts);
            index = new Word(parts, "", false, source.line(), indexRaw.toString());
        }
        return new Part.Variable(name.toString(), index);
    }

    private static char escape(Source source, StringBuilder raw) throws IOException {
        if (source.peek() < 0) return '\\';
        var next = source.read();
        raw.append((char) next);
        if (next == '\n') {
            while (source.peek() == ' ' || source.peek() == '\t') raw.append((char) source.read());
            return ' ';
        }
        var simple = switch (next) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case 'a' -> '\u0007';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'v' -> '\u000b';
            default -> (char) next;
        };
        if (next == 'x') return digits(source, raw, 16, 2, 'x');
        if (next == 'u') return digits(source, raw, 16, 4, 'u');
        if (next >= '0' && next <= '7') {
            var value = next - '0';
            for (var count = 1; count < 3; count++) {
                var digit = Character.digit(source.peek(), 8);
                if (digit < 0) break;
                var character = source.read();
                raw.append((char) character);
                value = value * 8 + digit;
            }
            return (char) value;
        }
        return simple;
    }

    private static char digits(Source source, StringBuilder raw, int radix, int limit, char fallback) throws IOException {
        var value = 0;
        var count = 0;
        while (count < limit) {
            var digit = Character.digit(source.peek(), radix);
            if (digit < 0) break;
            var character = source.read();
            raw.append((char) character);
            value = value * radix + digit;
            count++;
        }
        return count == 0 ? fallback : (char) value;
    }

    private static void flush(StringBuilder text, List<Part> parts) {
        if (text.isEmpty()) return;
        parts.add(new Part.Text(text.toString()));
        text.setLength(0);
    }

    private static boolean nameCharacter(int character) {
        return character >= 0 && (Character.isLetterOrDigit(character) || character == '_');
    }

    private static boolean blank(int character) {
        return character == ' ' || character == '\t' || character == '\r';
    }

    private static void error(String message, int line) {
        throw new TclException.Error(message, List.of("TCL", "PARSE", line));
    }

    static final class Source {

        private final Reader reader;
        private final ArrayList<Integer> ahead = new ArrayList<>();
        private final StringBuilder history = new StringBuilder();
        private int line;

        Source(Reader reader, int firstLine) {
            this.reader = reader;
            line = firstLine;
        }

        int line() {
            return line;
        }

        int peek() throws IOException {
            return peek(0);
        }

        int peek(int offset) throws IOException {
            while (ahead.size() <= offset) ahead.add(reader.read());
            return ahead.get(offset);
        }

        int read() throws IOException {
            var result = ahead.isEmpty() ? reader.read() : ahead.removeFirst();
            if (result >= 0) history.append((char) result);
            if (result == '\n') line++;
            return result;
        }

        int mark() {
            return history.length();
        }

        String since(int mark) {
            return history.substring(mark);
        }

        void clearHistory() {
            history.setLength(0);
        }

        String take(int count) throws IOException {
            var result = new StringBuilder();
            while (count-- > 0) result.append((char) read());
            return result.toString();
        }
    }
}
