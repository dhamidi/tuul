package project;

import actors.ActorSystem;
import actors.Address;
import actors.Behavior;
import actors.Definition;
import actors.DeliveryStatus;
import actors.MessageType;
import application.Effect;
import application.Message;
import application.Step;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/// Owns one add command's output and renders either one TTY line or event lines.
///
/// Downloads run concurrently, but stdout must not. This ephemeral actor drains
/// worker updates and is the only code that writes the command's output. TTY
/// updates show one aggregate line. Event mode keeps plan, lifecycle, diagnostic,
/// and final events while dropping byte-level progress.
final class Progress implements Definition<Progress.State> {

    private static final long FRAME_NANOS = 100_000_000L;
    static final String TYPE = "add.progress";
    static final MessageType WAKE = MessageType.command("wake-progress");
    static final String CLOSE = "add.progress.close";
    static final MessageType CLOSE_MESSAGE = MessageType.command("close-progress");
    static final String RENDER = "add.progress.render";

    private final Writer out;
    private final Add.Mode mode;
    private final ConcurrentMap<String, Add.Event> pending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Add.Event> lines = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Add.Event> diagnostics = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final Map<String, Add.Event> jobs = new LinkedHashMap<>();
    private final ProgressBar bar;
    private ActorSystem system;
    private Address address;
    private volatile int totalJobs;
    private Add.Event complete;
    private String current;
    private long renderedAt;
    private volatile boolean terminal;

    Progress(Writer out, Add.Mode mode) {
        this.out = out;
        this.mode = mode;
        this.bar = new ProgressBar(out);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Behavior<State> instantiate(Address self) {
        return Behavior.of(new State())
                .on(WAKE, Progress::wake)
                .on(CLOSE_MESSAGE, Progress::close);
    }

    void attach(ActorSystem system, Address address) {
        this.system = system;
        this.address = address;
        if (mode == Add.Mode.TTY ? !pending.isEmpty() : !lines.isEmpty()) signal();
    }

    /// Publishes one worker event. TTY mode retains artifact state and diagnostics.
    /// Event mode drops byte-level `progress` events before it writes output.
    void publish(Add.Event event) {
        if (terminal) return;
        if (mode == Add.Mode.TTY) {
            if (event.type().equals("complete")) {
                pending.put(event.coordinate(), event);
            } else {
                if (diagnostic(event)) diagnostics.add(event);
                if (!visual(event)) return;
                pending.put(event.coordinate(), event);
            }
        } else if (event.type().equals("progress")) {
            return;
        } else {
            lines.add(event);
        }
        signal();
    }

    /// Schedules the exact number of artifact jobs selected by resolution.
    /// The count drives the aggregate TTY bar and rejects negative values.
    void schedule(int jobs) {
        if (jobs < 0) throw new IllegalArgumentException("progress jobs cannot be negative: " + jobs);
        totalJobs = jobs;
    }

    /// Closes the output after the actor renders all events already published.
    /// A TTY render ends its line. Event mode writes every queued event.
    void close() {
        if (closed.isDone()) return;
        if (system == null) {
            closeOutput(null, null);
            return;
        }
        if (system.tell(address, CLOSE_MESSAGE.message()) != DeliveryStatus.accepted) {
            closed.complete(null);
            return;
        }
        closed.join();
    }

    private void signal() {
        if (system == null) return;
        if (!wakePending.compareAndSet(false, true)) return;
        if (system.tell(address, WAKE.message()) != DeliveryStatus.accepted) {
            wakePending.set(false);
        }
    }

    private static Step<State> wake(State state, Message ignored) {
        return Step.of(state, Effect.of(RENDER));
    }

    private static Step<State> close(State state, Message ignored) {
        return Step.of(state, Effect.of(CLOSE));
    }

    /// Renders queued output. TTY mode limits frames to ten per second.
    /// Event mode flushes its lifecycle, plan, diagnostic, and final events.
    void render(Effect effect, Effect.Emitter emit) throws IOException {
        if (terminal) return;
        if (mode == Add.Mode.TTY) {
            waitForFrame();
            for (var event : drainLatest()) accept(event);
            renderAggregate();
        } else {
            for (Add.Event event; (event = lines.poll()) != null;) line(event);
            out.flush();
        }
        readyForWake();
    }

    void closeOutput(Effect effect, Effect.Emitter emit) {
        if (terminal) {
            closed.complete(null);
            return;
        }
        try {
            if (mode == Add.Mode.TTY) {
                for (var event : drainLatest()) accept(event);
                renderAggregate();
                bar.close();
                writeDiagnostics();
            } else {
                for (Add.Event event; (event = lines.poll()) != null;) line(event);
            }
            out.flush();
        } catch (IOException ignored) {
            // Output is a terminal side effect. The add result remains valid
            // even when the stream disappears while it is being rendered.
            if (mode == Add.Mode.TTY) {
                try {
                    bar.close();
                } catch (IOException ignoredAgain) {
                    // The stream is already unwritable.
                }
            }
        } finally {
            terminal = true;
            wakePending.set(false);
            closed.complete(null);
        }
    }

    private List<Add.Event> drainLatest() {
        var events = new ArrayList<Add.Event>();
        for (var entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) events.add(entry.getValue());
        }
        return events;
    }

    private void accept(Add.Event event) {
        if (event.type().equals("complete")) {
            complete = event;
            current = null;
            return;
        }
        jobs.put(event.coordinate(), event);
        switch (event.type()) {
            case "start", "progress" -> current = event.coordinate();
            case "done", "cached", "optional-missing", "failed" -> {
                if (current != null && current.equals(event.coordinate())) current = nextCurrent();
            }
            default -> {}
        }
    }

    private void readyForWake() {
        wakePending.set(false);
        if (mode == Add.Mode.TTY ? !pending.isEmpty() : !lines.isEmpty()) signal();
    }

    private void line(Add.Event event) throws IOException {
        if (event.type().equals("progress")) return;
        if (event.type().equals("complete")) {
            out.write("add.complete " + clean(event.reason()) + "\n");
            return;
        }
        out.write("add." + event.type() + " " + event.coordinate());
        switch (event.type()) {
            case "start", "progress" -> out.write(" " + event.bytes() + "/" + event.total());
            case "done", "cached" -> out.write(" " + event.target());
            case "resolved" -> out.write(" " + event.bytes() + " artifacts");
            case "selected", "omitted", "limits", "warning", "resolve-failed", "failed", "optional-missing" ->
                out.write(" " + clean(event.reason()));
            default -> {}
        }
        out.write("\n");
    }

    private void writeDiagnostics() throws IOException {
        var warnings = new LinkedHashMap<String, Integer>();
        for (var event : diagnostics) {
            if (event.type().equals("warning")) {
                warnings.merge(clean(event.reason()), 1, Integer::sum);
            } else {
                line(event);
            }
        }
        for (var warning : warnings.entrySet()) {
            out.write("add.warning " + warning.getValue() + "x " + warning.getKey() + "\n");
        }
    }

    private void waitForFrame() {
        if (renderedAt == 0) return;
        var remaining = FRAME_NANOS - (System.nanoTime() - renderedAt);
        while (remaining > 0 && !Thread.currentThread().isInterrupted()) {
            LockSupport.parkNanos(remaining);
            remaining = FRAME_NANOS - (System.nanoTime() - renderedAt);
        }
    }

    private void renderAggregate() throws IOException {
        var finished = completed();
        var total = totalJobs;
        var status = finished + "/" + total + " complete";
        var detail = complete == null ? current == null ? "" : transfer(jobs.get(current))
                : complete.reason();
        bar.render(finished, total, status, detail);
        renderedAt = System.nanoTime();
    }

    private int completed() {
        return (int) jobs.values().stream().filter(event -> switch (event.type()) {
            case "done", "cached", "optional-missing", "failed" -> true;
            default -> false;
        }).count();
    }

    private String nextCurrent() {
        return jobs.entrySet().stream()
                .filter(entry -> entry.getValue().type().equals("start") || entry.getValue().type().equals("progress"))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static String transfer(Add.Event event) {
        if (event == null) return "";
        var total = event.total() < 0 ? "?" : bytes(event.total());
        return event.coordinate() + " " + bytes(event.bytes()) + "/" + total;
    }

    private static boolean visual(Add.Event event) {
        return switch (event.type()) {
            case "start", "progress", "done", "cached", "optional-missing", "failed" -> true;
            default -> false;
        };
    }

    private static boolean diagnostic(Add.Event event) {
        return switch (event.type()) {
            case "warning", "resolve-failed", "failed" -> true;
            default -> false;
        };
    }

    private static String bytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return "%.1f KiB".formatted(bytes / 1024d);
        if (bytes < 1024 * 1024 * 1024) return "%.1f MiB".formatted(bytes / (1024d * 1024));
        return "%.1f GiB".formatted(bytes / (1024d * 1024 * 1024));
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
    }

    record State() {}
}
