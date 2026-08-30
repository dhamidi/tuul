package project;

import actors.ActorSystem;
import actors.Address;
import actors.Definition;
import actors.DeliveryStatus;
import application.Application;
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

/// Owns one add command's output.
///
/// Downloads run concurrently, but stdout must not. This is an ephemeral actor
/// with a small wake-up mailbox: workers put the newest state in a queue and
/// send one wake-up, then the actor drains that queue and is the only code that
/// writes the command's output. TTY updates are coalesced per artifact; event
/// mode keeps every event.
final class Progress implements Definition<Progress.State> {

    static final String TYPE = "add.progress";
    static final String WAKE = "add.progress.wake";
    static final String CLOSE = "add.progress.close";
    static final String RENDER = "add.progress.render";

    private final Writer out;
    private final Add.Mode mode;
    private final ConcurrentMap<String, Add.Event> pending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Add.Event> lines = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final List<String> order = new ArrayList<>();
    private final Map<String, Add.Event> latest = new LinkedHashMap<>();
    private ActorSystem system;
    private Address address;
    private int drawn;
    private boolean terminal;

    Progress(Writer out, Add.Mode mode) {
        this.out = out;
        this.mode = mode;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Application<State> instantiate(Address self) {
        return Application.of(new State())
                .on(WAKE, Progress::wake)
                .on(CLOSE, Progress::close);
    }

    void attach(ActorSystem system, Address address) {
        this.system = system;
        this.address = address;
    }

    void publish(Add.Event event) {
        if (terminal) return;
        if (mode == Add.Mode.TTY) pending.put(event.coordinate(), event);
        else lines.add(event);
        signal();
    }

    void close() {
        if (system == null || closed.isDone()) return;
        if (system.tell(address, Message.of(CLOSE)) != DeliveryStatus.accepted) {
            closed.complete(null);
            return;
        }
        closed.join();
    }

    private void signal() {
        if (!wakePending.compareAndSet(false, true)) return;
        if (system.tell(address, Message.of(WAKE)) != DeliveryStatus.accepted) {
            wakePending.set(false);
        }
    }

    private static Step<State> wake(State state, Message ignored) {
        return Step.of(state, Effect.of(RENDER));
    }

    private static Step<State> close(State state, Message ignored) {
        return Step.of(state, Effect.of(CLOSE));
    }

    void render(Effect effect, Effect.Emitter emit) throws IOException {
        if (terminal) return;
        if (mode == Add.Mode.TTY) {
            for (var event : drainLatest()) accept(event);
            renderBars();
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
                renderBars();
                if (drawn > 0) out.write("\n");
                out.write("\033[?7h\033[?25h");
            } else {
                for (Add.Event event; (event = lines.poll()) != null;) line(event);
            }
            out.flush();
        } catch (IOException ignored) {
            // Output is a terminal side effect. The add result remains valid
            // even when the stream disappears while it is being rendered.
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
        latest.put(event.coordinate(), event);
        if (!event.type().startsWith("resolve") && !event.type().equals("complete")
                && !order.contains(event.coordinate())) order.add(event.coordinate());
    }

    private void readyForWake() {
        wakePending.set(false);
        if (mode == Add.Mode.TTY ? !pending.isEmpty() : !lines.isEmpty()) signal();
    }

    private void line(Add.Event event) throws IOException {
        if (event.type().equals("complete")) {
            out.write("add.complete " + clean(event.reason()) + "\n");
            return;
        }
        out.write("add." + event.type() + " " + event.coordinate());
        switch (event.type()) {
            case "start", "progress" -> out.write(" " + event.bytes() + "/" + event.total());
            case "done", "cached" -> out.write(" " + event.target());
            case "resolved" -> out.write(" " + event.bytes() + " artifacts");
            case "resolve-failed", "failed", "optional-missing" -> out.write(" " + clean(event.reason()));
            default -> {}
        }
        out.write("\n");
    }

    private void renderBars() throws IOException {
        if (order.isEmpty()) return;
        if (drawn == 0) out.write("\033[?7l\033[?25l");
        else out.write("\033[" + drawn + "A");
        for (var index = 0; index < order.size(); index++) {
            var coordinate = order.get(index);
            out.write("\r\033[2K");
            out.write(bar(coordinate, latest.get(coordinate)));
            if (index + 1 < order.size()) out.write("\n");
        }
        drawn = order.size();
    }

    private static String bar(String coordinate, Add.Event event) {
        if (event == null) return "[                    ] " + coordinate + " waiting";
        if (event.type().equals("failed")) return "[--------------------] " + coordinate + " failed: " + clean(event.reason());
        if (event.type().equals("optional-missing")) return "[....................] " + coordinate + " missing (optional)";
        if (event.type().equals("cached")) return "[####################] " + coordinate + " cached";
        if (event.type().equals("done")) return "[####################] " + coordinate + " done";
        var total = event.total();
        if (total <= 0) return "[????????????????????] " + coordinate + " " + bytes(event.bytes());
        var complete = Math.min(20, (int) (event.bytes() * 20 / total));
        return "[" + "#".repeat(complete) + "-".repeat(20 - complete) + "] "
                + coordinate + " " + bytes(event.bytes()) + "/" + bytes(total);
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
