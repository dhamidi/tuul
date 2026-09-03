package project;

import actors.ActorSystem;
import actors.Address;
import actors.Behavior;
import actors.Definition;
import actors.DeliveryStatus;
import application.Effect;
import application.Message;
import application.Step;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import terminal.Notice;
import terminal.TaskUpdate;

/// Maps Maven download events to the reusable terminal progress actor.
///
/// TTY mode publishes task and notice updates to [terminal.Progress]. Events
/// mode queues semantic Maven lines and omits byte-level `progress` events.
final class Progress implements Definition<Progress.State>, AutoCloseable {

    private static final String TYPE = "add.progress";
    private static final String RENDER = "add.progress.render";
    private static final String CLOSE = "add.progress.close";

    private final Writer out;
    private final Add.Mode mode;
    private final terminal.Progress tty;
    private final ConcurrentLinkedQueue<Add.Event> lines = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Add.Event> diagnostics = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile ActorSystem system;
    private volatile Address address;
    private volatile boolean closing;

    Progress(Writer out, Add.Mode mode) {
        this.out = java.util.Objects.requireNonNull(out, "out");
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
        this.tty = mode == Add.Mode.TTY ? new terminal.Progress(out, MavenTransport.GLOBAL_LIMIT) : null;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Behavior<State> instantiate(Address self) {
        return Behavior.of(new State())
                .on(Events.WAKE, this::wake)
                .on(Events.CLOSE_MESSAGE, this::closeState);
    }

    /// Registers the Add event actor or the reusable TTY progress actor. Events
    /// published before this call stay queued for the actor.
    void attach(ActorSystem system) {
        if (this.system != null) throw new IllegalStateException("progress is already attached");
        if (closing || closed.isDone()) throw new IllegalStateException("progress is already closed");
        this.system = java.util.Objects.requireNonNull(system, "system");
        if (mode == Add.Mode.TTY) {
            tty.attach(system);
            return;
        }
        this.address = Definition.super.at("run");
        system.define(this, actors.Spawn.ephemeral().mailbox(32));
        system.effect(RENDER, this::render);
        system.effect(CLOSE, this::closeOutput);
        system.summon(address);
        signal();
    }

    /// Schedules the exact number of Maven artifacts for the aggregate bar.
    void schedule(int total) {
        if (total < 0) throw new IllegalArgumentException("progress total cannot be negative");
        if (mode == Add.Mode.TTY) tty.schedule(total);
    }

    /// Maps one Maven event to one task, notice, or semantic event update.
    void publish(Add.Event event) {
        if (closing || closed.isDone()) return;
        if (mode == Add.Mode.TTY) {
            switch (event.type()) {
                case "start", "progress" -> tty.publish(TaskUpdate.running(
                        event.coordinate(), event.coordinate(), event.bytes(), event.total()));
                case "done", "cached" -> tty.publish(TaskUpdate.complete(
                        event.coordinate(), event.coordinate(), event.total(), event.type()));
                case "optional-missing" -> tty.publish(TaskUpdate.complete(
                        event.coordinate(), event.coordinate(), -1, "optional file is not available"));
                case "failed" -> {
                    tty.publish(TaskUpdate.failed(event.coordinate(), event.coordinate(), event.reason()));
                    diagnostics.add(event);
                }
                case "resolve-failed" -> diagnostics.add(event);
                case "warning" -> {
                    tty.publish(new Notice("warning:" + clean(event.reason()), clean(event.reason())));
                    diagnostics.add(event);
                }
                case "complete" -> tty.summary(event.reason());
                default -> { }
            }
            return;
        }
        if (event.type().equals("progress")) return;
        lines.add(event);
        signal();
    }

    /// Closes the selected actor and waits for its final flush and terminal restore.
    @Override
    public void close() {
        if (closed.isDone()) return;
        closing = true;
        if (mode == Add.Mode.TTY) {
            tty.close();
            writeTtyDiagnostics();
            closed.complete(null);
            return;
        }
        var actor = system;
        if (actor == null || actor.tell(address, Events.CLOSE_MESSAGE.message()) != DeliveryStatus.accepted) {
            closeOutput(null, null);
            return;
        }
        closed.join();
    }

    /// Renders one batched semantic event frame in events mode.
    void render(Effect ignored, Effect.Emitter ignoredEmitter) throws IOException {
        if (mode == Add.Mode.TTY) return;
        if (closing && lines.isEmpty()) return;
        for (Add.Event event; (event = lines.poll()) != null;) line(event);
        out.flush();
        wakePending.set(false);
        if (!lines.isEmpty()) signal();
    }

    /// Writes queued event lines, then completes the actor close protocol.
    void closeOutput(Effect effect, Effect.Emitter ignoredEmitter) {
        try {
            for (Add.Event event; (event = lines.poll()) != null;) line(event);
            writeDiagnostics();
            out.flush();
        } catch (IOException outputFailure) {
            // A closed pipe does not change the add result.
        } finally {
            closing = true;
            wakePending.set(false);
            closed.complete(null);
        }
    }

    private Step<State> wake(State state, Message ignored) {
        return Step.of(state, Effect.of(RENDER));
    }

    private Step<State> closeState(State state, Message ignored) {
        return Step.of(state, Effect.of(CLOSE));
    }

    private void signal() {
        var actor = system;
        if (actor == null || closing || !wakePending.compareAndSet(false, true)) return;
        if (actor.tell(address, Events.WAKE.message()) != DeliveryStatus.accepted) wakePending.set(false);
    }

    private void line(Add.Event event) throws IOException {
        if (event.type().equals("progress")) return;
        if (event.type().equals("complete")) {
            out.write("add.complete " + clean(event.reason()) + "\n");
            return;
        }
        out.write("add." + event.type() + " " + clean(event.coordinate()));
        switch (event.type()) {
            case "start", "progress" -> out.write(" " + event.bytes() + "/" + event.total());
            case "done", "cached" -> out.write(" " + clean(event.target()));
            case "resolved" -> out.write(" " + event.bytes() + " artifacts");
            case "selected", "omitted", "limits", "warning", "resolve-failed", "failed", "optional-missing" ->
                out.write(" " + clean(event.reason()));
            default -> { }
        }
        out.write("\n");
    }

    private void writeDiagnostics() throws IOException {
        var warnings = new LinkedHashMap<String, Integer>();
        for (var event : diagnostics) {
            if (event.type().equals("warning")) warnings.merge(clean(event.reason()), 1, Integer::sum);
            else line(event);
        }
        for (var warning : warnings.entrySet()) {
            out.write("add.warning " + warning.getValue() + "x " + warning.getKey() + "\n");
        }
    }

    private void writeTtyDiagnostics() {
        try {
            writeDiagnostics();
            out.flush();
        } catch (IOException ignored) {
            // A closed output does not change the add result.
        }
    }

    private static String clean(String text) {
        if (text == null) return "";
        var clean = new StringBuilder(text.length());
        for (var at = 0; at < text.length(); at++) {
            var character = text.charAt(at);
            clean.append(Character.isISOControl(character) ? ' ' : character);
        }
        return clean.toString();
    }

    record State() {}

    private static final class Events {
        private static final actors.MessageType WAKE = actors.MessageType.command("wake-progress");
        private static final actors.MessageType CLOSE_MESSAGE = actors.MessageType.command("close-progress");
    }
}
