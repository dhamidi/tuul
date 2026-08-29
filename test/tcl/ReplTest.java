package tcl;

import harness.Check;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public final class ReplTest {

    private ReplTest() {}

    public static void run() throws Exception {
        evaluatesCompleteCommands();
        retainsOnlyTheIncompleteCommand();
        evaluatesBeforeItReadsTheNextLine();
        reportsFailuresAndContinues();
        flushesPromptsBeforeItReads();
        leavesCallerStreamsOpen();
        checksTclCompleteness();
    }

    private static void evaluatesCompleteCommands() throws IOException {
        var commands = new ArrayList<String>();
        var output = new StringWriter();
        var result = Repl.of(reader -> {
            var command = read(reader);
            commands.add(command);
            return command.strip();
        }).run(new StringReader("one\ntwo\n"), output);

        Check.equal("each complete line reaches the evaluator", List.of("one\n", "two\n"), commands);
        Check.equal("each non-empty result reaches the output", "one\ntwo\n", output.toString());
        Check.equal("the result counts evaluator calls", 2L, result.evaluated());
        Check.that("complete successful input succeeds", result.successful());
    }

    private static void retainsOnlyTheIncompleteCommand() throws IOException {
        var commands = new ArrayList<String>();
        var output = new StringWriter();
        Repl.of(reader -> {
            commands.add(read(reader));
            return "done";
        }).run(new StringReader("set x {\nvalue}\n"), output);

        Check.equal("one multiline command reaches the evaluator once", List.of("set x {\nvalue}\n"), commands);
        Check.equal("one multiline command writes one result", "done\n", output.toString());
    }

    private static void evaluatesBeforeItReadsTheNextLine() throws IOException {
        var input = new GuardedReader("one\ntwo\n", 4);
        Repl.of(reader -> {
            read(reader);
            input.release();
            return "";
        }).run(input, new StringWriter());

        Check.that("the evaluator releases the next input line", input.released());
    }

    private static void reportsFailuresAndContinues() throws IOException {
        var output = new StringWriter();
        var errors = new StringWriter();
        var result = Repl.of(reader -> {
            var command = read(reader);
            if (command.startsWith("bad")) throw new IllegalArgumentException("not valid");
            return command.strip();
        }).run(new StringReader("bad\ngood\n"), output, errors);

        Check.equal("a failed command does not stop input", "good\n", output.toString());
        Check.equal("a failure reaches the error writer", "error: not valid\n", errors.toString());
        Check.equal("the result counts evaluator failures", 1L, result.failures());
        Check.that("an evaluator failure does not make input incomplete", !result.incomplete());
    }

    private static void flushesPromptsBeforeItReads() throws IOException {
        var output = new StringWriter();
        var input = new PromptReader("one\n", output);
        Repl.of(reader -> "ok").interactive().run(input, output);

        Check.equal("interactive mode writes both primary prompts", "% ok\n% ", output.toString());
        Check.that("the first prompt exists before the first read", input.promptWasReady());
    }

    private static void leavesCallerStreamsOpen() throws IOException {
        var input = new CloseTrackingReader("one\n");
        var output = new CloseTrackingWriter();
        Repl.of(reader -> "ok").run(input, output);

        Check.that("the REPL leaves input open", !input.closed);
        Check.that("the REPL leaves output open", !output.closed);
    }

    private static void checksTclCompleteness() {
        Check.that("empty source is complete", Repl.complete(""));
        Check.that("a simple command is complete", Repl.complete("set x 1"));
        Check.that("an open brace is incomplete", !Repl.complete("set x {value"));
        Check.that("nested braces are counted", !Repl.complete("set x {{value}"));
        Check.that("closed nested braces are complete", Repl.complete("set x {{value}}"));
        Check.that("an open quote is incomplete", !Repl.complete("set x \"value"));
        Check.that("an open command substitution is incomplete", !Repl.complete("set x [value"));
        Check.that("a braced substitution word can be complete", Repl.complete("set x [value {text}]"));
        Check.that("a comment cannot open a brace", Repl.complete("# { comment"));
        Check.that("a brace in a quote is text", Repl.complete("set x \"{value\""));
        Check.that("an escaped brace does not close a braced word", !Repl.complete("set x {value\\}"));
    }

    private static String read(Reader reader) throws IOException {
        var text = new StringBuilder();
        var characters = new char[64];
        for (var count = reader.read(characters); count >= 0; count = reader.read(characters)) {
            text.append(characters, 0, count);
        }
        return text.toString();
    }

    private static class TextReader extends Reader {

        private final String text;
        private int at;

        private TextReader(String text) {
            this.text = text;
        }

        @Override
        public int read(char[] target, int offset, int length) {
            if (at == text.length()) return -1;
            var count = Math.min(length, text.length() - at);
            text.getChars(at, at + count, target, offset);
            at += count;
            return count;
        }

        @Override
        public void close() {}

        protected int position() {
            return at;
        }
    }

    private static final class GuardedReader extends TextReader {

        private final int boundary;
        private boolean released;

        private GuardedReader(String text, int boundary) {
            super(text);
            this.boundary = boundary;
        }

        @Override
        public int read(char[] target, int offset, int length) {
            if (position() == boundary && !released) throw new AssertionError("the REPL read the next line too early");
            return super.read(target, offset, Math.min(length, 1));
        }

        private void release() {
            released = true;
        }

        private boolean released() {
            return released;
        }
    }

    private static final class PromptReader extends TextReader {

        private final StringWriter output;
        private boolean checked;
        private boolean ready;

        private PromptReader(String text, StringWriter output) {
            super(text);
            this.output = output;
        }

        @Override
        public int read(char[] target, int offset, int length) {
            if (!checked) {
                checked = true;
                ready = output.toString().equals("% ");
            }
            return super.read(target, offset, Math.min(length, 1));
        }

        private boolean promptWasReady() {
            return ready;
        }
    }

    private static final class CloseTrackingReader extends TextReader {

        private boolean closed;

        private CloseTrackingReader(String text) {
            super(text);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class CloseTrackingWriter extends StringWriter {

        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
