package terminal;

import java.io.IOException;
import java.io.Writer;

/// Writes complete progress frames to one writer. Implementations are stateful
/// and receive calls in sequence from one progress actor.
public interface ProgressRenderer {

    /// Writes one complete frame without flushing `out`. The actor flushes once
    /// after this method returns.
    void write(ProgressFrame frame, Writer out) throws IOException;

    /// Restores output state after the final frame without flushing `out`.
    void restore(ProgressFrame frame, Writer out) throws IOException;
}
