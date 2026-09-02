package project;

import java.io.IOException;
import java.io.Writer;

/// One replaceable terminal progress line. Use it when repeated updates must
/// stay on one row.
///
/// [#render] disables terminal autowrap, writes the newest frame, and flushes
/// the writer. [#close] restores autowrap and ends a rendered line. Calls after
/// `close` write nothing.
final class ProgressBar {

    private final Writer out;
    private boolean drawn;
    private boolean closed;

    ProgressBar(Writer out) {
        this.out = out;
    }

    /// Renders one frame from job counts and status text, then flushes the writer.
    /// A non-positive total renders an empty fill. Carriage returns and line
    /// feeds in `status` or `detail` become spaces.
    void render(int completed, int total, String status, String detail) throws IOException {
        if (closed) return;
        if (drawn) out.write("\r\033[2K");
        else {
            drawn = true;
            out.write("\033[?7l\033[2K");
        }
        out.write("[" + fill(completed, total) + "] " + clean(status));
        var suffix = clean(detail);
        if (!suffix.isEmpty()) out.write(" — " + suffix);
        out.flush();
    }

    /// Restores autowrap, ends the line, and flushes the writer. A close before
    /// any render writes nothing. Repeated closes write nothing.
    void close() throws IOException {
        if (closed) return;
        if (drawn) {
            out.write("\033[?7h\r\n");
            out.flush();
        }
        closed = true;
    }

    private static String fill(int completed, int total) {
        var filled = total <= 0 ? 0 : Math.min(20, Math.max(0, (int) ((long) completed * 20 / total)));
        return "#".repeat(filled) + "-".repeat(20 - filled);
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
    }
}
