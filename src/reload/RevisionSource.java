package reload;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/// Produces revisions without knowing how they are activated.
@FunctionalInterface
public interface RevisionSource extends AutoCloseable {

    /// Starts producing revisions. The callback is safe to call from another
    /// thread. A source must preserve its own submission order.
    void start(Consumer<Revision> submit) throws Exception;

    /// Returns a source that transforms each materialized revision before the
    /// host receives it. This is the seam for a host compiler: an HTTP source
    /// can stage source files, and the host can use
    /// `source.map(compiler::compile).start(reload::submit)` without teaching
    /// the source how candidates are loaded or activated.
    default RevisionSource map(UnaryOperator<Revision> transform) {
        java.util.Objects.requireNonNull(transform, "transform");
        var source = this;
        return new RevisionSource() {
            @Override
            public void start(Consumer<Revision> submit) throws Exception {
                source.start(revision -> submit.accept(transform.apply(revision)));
            }

            @Override
            public void close() throws Exception {
                source.close();
            }
        };
    }

    /// Stops future submissions. Closing a source does not close its coordinator.
    @Override
    default void close() throws Exception {}
}
