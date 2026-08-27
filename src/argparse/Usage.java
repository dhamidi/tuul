package argparse;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/// The help, written out of the definition.
///
/// Nothing here is a sentence about a particular command — every word comes
/// from what the command was declared to accept, so help that is wrong is a
/// definition that is wrong, and there is only one place to fix it.
public final class Usage {

    private static final int WIDEST = 34;

    private Usage() {}

    public static void help(String path, Command command, Writer out) throws IOException {
        if (!command.summary().isEmpty()) out.write(path + " — " + command.summary() + "\n\n");
        line(path, command, out);
        section("commands", commands(command), out);
        section("options", options(command), out);
        out.flush();
    }

    /// The one line that says what shape a call takes.
    public static void line(String path, Command command, Writer out) throws IOException {
        var written = new StringBuilder("usage:\n  ").append(path);
        if (!command.commands().isEmpty()) written.append(" <command>");
        if (!command.options().isEmpty()) written.append(" [options]");
        for (var argument : command.arguments()) written.append(' ').append(argument.written());
        out.write(written.append('\n').toString());
    }

    private record Entry(String written, String description) {}

    private static List<Entry> commands(Command command) {
        return command.commands().stream()
                .map(child -> new Entry(child.name() + arguments(child), child.summary()))
                .toList();
    }

    private static String arguments(Command command) {
        var written = command.arguments().stream().map(Argument::written).collect(Collectors.joining(" "));
        return written.isEmpty() ? "" : " " + written;
    }

    /// `-j, --json`, and `--json` indented to where the long forms line up when
    /// there is no short one — a column of dashes is easier to read down than a
    /// ragged edge.
    private static List<Entry> options(Command command) {
        var entries = new ArrayList<Entry>();
        for (var option : command.options()) {
            var written = new StringBuilder(option.abbreviated().map(form -> form + ", ").orElse("    "));
            written.append(option.written());
            if (option.takesValue()) written.append(" <value>");
            entries.add(new Entry(written.toString(), option.description()));
        }
        return entries;
    }

    private static void section(String title, List<Entry> entries, Writer out) throws IOException {
        if (entries.isEmpty()) return;
        out.write("\n" + title + ":\n");
        var width = entries.stream().mapToInt(entry -> entry.written().length()).max().orElse(0);
        for (var entry : entries) {
            out.write("  " + entry.written());
            if (!entry.description().isEmpty()) {
                out.write(" ".repeat(Math.max(1, Math.min(WIDEST, width) - entry.written().length() + 2)));
                out.write(entry.description());
            }
            out.write("\n");
        }
    }
}
