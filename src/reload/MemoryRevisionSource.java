package reload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/// An in-process revision source for tests and embedding applications.
///
/// Revisions submitted before [#start(Consumer)] are retained in submission
/// order and delivered when the source starts. The source never activates a
/// revision; the callback normally calls [Reload#submit(Revision)].
public final class MemoryRevisionSource implements RevisionSource {

    private final Object monitor = new Object();
    private final List<Revision> waiting = new ArrayList<>();
    private Consumer<Revision> consumer;
    private boolean closed;

    /// Queues a revision, or delivers it to the started source callback.
    public void submit(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        Consumer<Revision> deliver;
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("revision source is closed");
            deliver = consumer;
            if (deliver == null) waiting.add(revision);
        }
        if (deliver != null) deliver.accept(revision);
    }

    /// Starts delivery. Calling this more than once is an error.
    @Override
    public void start(Consumer<Revision> submit) {
        Objects.requireNonNull(submit, "submit");
        List<Revision> queued;
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("revision source is closed");
            if (consumer != null) throw new IllegalStateException("revision source already started");
            consumer = submit;
            queued = List.copyOf(waiting);
            waiting.clear();
        }
        for (var revision : queued) submit.accept(revision);
    }

    /// Stops future submissions. Already delivered revisions remain owned by
    /// their receiver.
    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
            waiting.clear();
            consumer = null;
        }
    }
}
