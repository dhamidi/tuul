package terminal;

import java.io.IOException;
import java.io.Writer;

/// Replaces one bounded multi-line ANSI terminal region. Each write leaves the
/// cursor on the first row so the next write can clear stale rows. [#restore]
/// returns the cursor to the bottom, enables autowrap, shows the cursor, and
/// writes one final CRLF. Call methods in sequence from one output owner.
public final class TerminalRenderer implements ProgressRenderer {

    private int previousLines;
    private boolean active;

    @Override
    public void write(ProgressFrame frame, Writer out) throws IOException {
        if (!active) {
            out.write("\033[?25l\033[?7l");
            active = true;
        }
        // The cursor is left at the first row after every write. The next
        // frame therefore starts at the current cursor; stale rows are erased
        // by writing the larger of the old and new frame heights.
        var written = frame.lines();
        var lines = Math.max(previousLines, written);
        for (var line = 0; line < lines; line++) {
            out.write("\r\033[2K");
            if (line < written) {
                if (line == 0) writeSummary(frame, out);
                else if (line <= frame.tasks().size()) writeTask(frame.tasks().get(line - 1), out);
                else writeTail(frame, line - frame.tasks().size() - 1, out);
            }
            if (line + 1 < lines) out.write('\n');
        }
        previousLines = lines;
        if (previousLines > 1) {
            out.write("\033[");
            out.write(Integer.toString(previousLines - 1));
            out.write('A');
        }
    }

    @Override
    public void restore(ProgressFrame frame, Writer out) throws IOException {
        if (!active) return;
        if (previousLines > 1) {
            out.write("\033[");
            out.write(Integer.toString(previousLines - 1));
            out.write('B');
        }
        out.write("\033[?7h\033[?25h\r\n");
        active = false;
        previousLines = 0;
    }

    private static void writeTask(TaskLine task, Writer out) throws IOException {
        writeClean(task.label(), out);
        out.write(' ');
        out.write(task.status().name());
        if (task.status() == TaskUpdate.Status.RUNNING) {
            out.write(" [");
            if (task.total() < 0) {
                out.write("??");
            } else {
                var filled = ratio(task.current(), task.total(), 10);
                for (var at = 0; at < 10; at++) out.write(at < filled ? '#' : '-');
            }
            out.write("] ");
            out.write(Long.toString(task.current()));
            out.write('/');
            if (task.total() < 0) out.write('?');
            else out.write(Long.toString(task.total()));
        }
        if (!task.detail().isBlank()) {
            out.write(" — ");
            writeClean(task.detail(), out);
        }
    }

    private static void writeSummary(ProgressFrame frame, Writer out) throws IOException {
        out.write('[');
        var filled = ratio(frame.finished(), frame.total(), 20);
        for (var at = 0; at < 20; at++) out.write(at < filled ? '#' : '-');
        out.write("] ");
        out.write(Integer.toString(frame.finished()));
        out.write('/');
        out.write(Integer.toString(frame.total()));
        out.write(" finished");
        if (frame.failed() > 0) {
            out.write(" (failed ");
            out.write(Integer.toString(frame.failed()));
            out.write(')');
        }
        if (!frame.summary().isBlank()) {
            out.write(" — ");
            writeClean(frame.summary(), out);
        }
    }

    private static void writeTail(ProgressFrame frame, int at, Writer out) throws IOException {
        if (at < frame.notices().size()) {
            var notice = frame.notices().get(at);
            out.write("notice ");
            out.write(Integer.toString(notice.count()));
            out.write('x');
            out.write(' ');
            writeClean(notice.text(), out);
            return;
        }
        if (at != frame.notices().size()) return;
        out.write("… ");
        if (frame.overflowTasks() > 0) {
            out.write(Integer.toString(frame.overflowTasks()));
            out.write(" more tasks");
            if (frame.overflowNotices() > 0) out.write(", ");
        }
        if (frame.overflowNotices() > 0) {
            out.write(Integer.toString(frame.overflowNotices()));
            out.write(" more notices");
        }
    }

    private static void writeClean(String text, Writer out) throws IOException {
        if (text == null) return;
        for (var at = 0; at < text.length(); at++) {
            var character = text.charAt(at);
            out.write(Character.isISOControl(character) ? ' ' : character);
        }
    }

    private static int ratio(long current, long total, int width) {
        if (current <= 0 || total <= 0) return 0;
        if (current >= total) return width;
        return Math.clamp((int) ((double) current / total * width), 0, width);
    }
}
