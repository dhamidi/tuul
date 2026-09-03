package terminal;

import actors.ActorEffect;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/// Owns one ephemeral multi-line progress display. Publish updates from any
/// thread before or after [#attach(ActorSystem)] and close it when work ends.
///
/// The actor coalesces concurrent updates and writes no more than one frame per
/// cadence. `slots` bounds all task, outcome, notice, and overflow lines below
/// the aggregate line. One actor system must attach at most one display because
/// one display owns that system's terminal output effects.
public final class Progress implements Definition<Progress.State>, AutoCloseable {

    private static final String TYPE = "terminal.progress";
    private static final MessageTypeNames MESSAGES = new MessageTypeNames();

    private final Writer out;
    private final ProgressRenderer renderer;
    private final int slots;
    private final Duration cadence;
    private final ConcurrentMap<String, PendingTask> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingNotice> pendingNotices = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ProgressFrame> frames = new ConcurrentHashMap<>();
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final AtomicBoolean totalPending = new AtomicBoolean();
    private final AtomicInteger requestedTotal = new AtomicInteger();
    private final AtomicBoolean summaryPending = new AtomicBoolean();
    private final AtomicReference<String> requestedSummary = new AtomicReference<>("");
    private final AtomicLong ingressSequence = new AtomicLong();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private State directState;
    private long nextFrame;
    private volatile ActorSystem system;
    private volatile Address address;
    private volatile boolean closing;

    /// Builds a terminal progress display with at most `slots` body lines and
    /// a 100 millisecond frame cadence. The aggregate line does not use a slot.
    public Progress(Writer out, int slots) {
        this(out, slots, Duration.ofMillis(100), new TerminalRenderer());
    }

    /// Builds a terminal progress display with a caller-selected frame cadence.
    /// The cadence must be zero or positive.
    public Progress(Writer out, int slots, Duration cadence) {
        this(out, slots, cadence, new TerminalRenderer());
    }

    /// Builds a progress display with a caller-selected frame cadence. A zero
    /// cadence schedules every frame immediately. The renderer must write a
    /// complete frame without flushing the writer.
    public Progress(Writer out, int slots, Duration cadence, ProgressRenderer renderer) {
        if (slots < 1) throw new IllegalArgumentException("progress needs at least one visible slot");
        java.util.Objects.requireNonNull(cadence, "cadence");
        if (cadence.isNegative()) throw new IllegalArgumentException("progress cadence cannot be negative");
        this.out = java.util.Objects.requireNonNull(out, "out");
        this.renderer = java.util.Objects.requireNonNull(renderer, "renderer");
        this.slots = slots;
        this.cadence = cadence;
        this.directState = new State(slots);
    }

    @Override
    public String type() {
        return TYPE;
    }

    /// Defines this progress actor with ephemeral state, registers output
    /// effects, and summons `terminal.progress/run`.
    ///
    /// Call this method at most once. Do not attach another progress display to
    /// the same actor system.
    public void attach(ActorSystem system) {
        if (this.system != null) throw new IllegalStateException("progress is already attached");
        if (closing || closed.isDone()) throw new IllegalStateException("progress is already closed");
        this.system = java.util.Objects.requireNonNull(system, "system");
        this.address = at("run");
        system.define(this, actors.Spawn.ephemeral().mailbox(32));
        system.effect(MESSAGES.render, this::render);
        system.effect(MESSAGES.close, this::closeOutput);
        system.summon(this.address);
        if (hasPending()) signal();
    }

    /// Sets the total number of work items in the aggregate line. A later call
    /// replaces the total. Calls after close do nothing.
    public void schedule(int total) {
        if (total < 0) throw new IllegalArgumentException("progress total cannot be negative");
        if (closing || closed.isDone()) return;
        requestedTotal.set(total);
        totalPending.set(true);
        signal();
    }

    /// Sets summary text for the next frame and the final frame. A later call
    /// replaces the text. Calls after close do nothing.
    public void summary(String summary) {
        if (closing || closed.isDone()) return;
        requestedSummary.set(summary == null ? "" : summary);
        summaryPending.set(true);
        signal();
    }

    /// Publishes one task update without writing output. Concurrent running
    /// updates coalesce by task ID and their current count does not decrease.
    /// A terminal update prevents later updates for the same task.
    public void publish(TaskUpdate update) {
        if (closing || closed.isDone()) return;
        java.util.Objects.requireNonNull(update, "update");
        pending.compute(update.id(), (ignored, prior) -> prior == null
                ? new PendingTask(ingressSequence.getAndIncrement(), update)
                : new PendingTask(prior.order(), newer(prior.update(), update)));
        signal();
    }

    /// Publishes one notice without writing output. Notices with the same key
    /// become one line with a count.
    public void publish(Notice notice) {
        if (closing || closed.isDone()) return;
        java.util.Objects.requireNonNull(notice, "notice");
        pendingNotices.compute(notice.key(), (ignored, prior) -> prior == null
                ? new PendingNotice(ingressSequence.getAndIncrement(), notice, 1)
                : new PendingNotice(prior.order(), notice, prior.count() + 1));
        signal();
    }

    /// Stops accepting updates and waits for one final flushed frame. The
    /// renderer restores terminal state before this method returns. If the
    /// actor was not attached, the calling thread writes the final frame.
    @Override
    public void close() {
        if (closed.isDone()) return;
        closing = true;
        var actor = system;
        if (actor == null) {
            try {
                synchronized (renderer) {
                    directState = drain(directState);
                    var frame = frame(directState);
                    renderer.write(frame, out);
                    renderer.restore(frame, out);
                    out.flush();
                }
            } catch (IOException ignored) {
                // The caller has no actor to report an output failure to.
            } finally {
                closed.complete(null);
            }
            return;
        }
        if (actor.tell(address, MESSAGES.closeMessage) != DeliveryStatus.accepted) {
            restoreRejectedClose();
            return;
        }
        closed.join();
    }

    @Override
    public Behavior<State> instantiate(Address self) {
        return Behavior.of(new State(slots))
                .on(MESSAGES.wake, this::wake)
                .on(MESSAGES.tick, this::tick)
                .on(MESSAGES.closeMessageType, this::closeState);
    }

    /// Writes one immutable frame supplied by the actor and flushes once.
    private void render(Effect effect, Effect.Emitter ignored) throws IOException {
        var frame = frames.remove((long) effect.number("frame", -1));
        if (frame == null || closed.isDone()) return;
        try {
            synchronized (renderer) {
                renderer.write(frame, out);
                out.flush();
            }
        } finally {
            wakePending.set(false);
        }
        if (hasPending()) signal();
    }

    /// Writes the final immutable frame, restores terminal state, and flushes once.
    private void closeOutput(Effect effect, Effect.Emitter ignored) throws IOException {
        var frame = frames.remove((long) effect.number("frame", -1));
        IOException failure = null;
        synchronized (renderer) {
            try {
                if (frame != null) renderer.write(frame, out);
            } catch (IOException writeFailure) {
                failure = writeFailure;
            }
            try {
                renderer.restore(frame == null ? emptyFrame() : frame, out);
            } catch (IOException restoreFailure) {
                if (failure == null) failure = restoreFailure;
            }
            try {
                out.flush();
            } catch (IOException flushFailure) {
                if (failure == null) failure = flushFailure;
            } finally {
                frames.clear();
                wakePending.set(false);
                closed.complete(null);
            }
        }
        if (failure != null) throw failure;
    }

    private void restoreRejectedClose() {
        try {
            synchronized (renderer) {
                renderer.restore(emptyFrame(), out);
                out.flush();
            }
        } catch (IOException ignored) {
            // The actor rejected close, so there is no output channel for an error.
        } finally {
            frames.clear();
            wakePending.set(false);
            closed.complete(null);
        }
    }

    private Step<State> wake(State state, Message ignored) {
        if (state.closed()) return Step.of(state);
        var next = drain(state);
        if (next.tickScheduled()) return Step.of(next);
        return Step.of(next.withTickScheduled(true), ActorEffect.schedule(cadence, MESSAGES.tick.message()));
    }

    private Step<State> tick(State state, Message ignored) {
        if (state.closed()) return Step.of(state);
        var next = drain(state).withTickScheduled(false);
        var frame = frame(next);
        var id = ++nextFrame;
        frames.put(id, frame);
        return Step.of(next, Effect.of(MESSAGES.render).with("frame", id));
    }

    private Step<State> closeState(State state, Message ignored) {
        if (state.closed()) return Step.of(state);
        var next = drain(state).withClosed(true).withTickScheduled(false);
        var frame = frame(next);
        var id = ++nextFrame;
        frames.put(id, frame);
        return Step.of(next, Effect.of(MESSAGES.close).with("frame", id));
    }

    private State drain(State state) {
        var tasks = new LinkedHashMap<>(state.tasks());
        var outcomes = new ArrayList<>(state.outcomes());
        var terminal = new LinkedHashMap<>(state.terminal());
        var completed = state.completed();
        var failed = state.failed();
        var notices = new LinkedHashMap<>(state.notices());
        var entries = pending.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().order()))
                .toList();
        for (var entry : entries) {
            if (!pending.remove(entry.getKey(), entry.getValue())) continue;
            var update = entry.getValue().update();
            if (terminal.containsKey(update.id())) continue;
            var prior = tasks.get(update.id());
            if (prior != null && prior.update().terminal()) continue;
            if (prior == null) {
                var slot = firstFree(tasks);
                tasks.put(update.id(), Task.of(update, slot));
            } else {
                update = newer(prior.update(), update);
                tasks.put(update.id(), prior.update(update));
            }
            if (update.terminal()) {
                var task = tasks.remove(update.id());
                if (update.status() == TaskUpdate.Status.COMPLETE) completed++;
                if (update.status() == TaskUpdate.Status.FAILED) failed++;
                terminal.put(update.id(), update.status());
                outcomes.add(TaskLine.of(update, task == null ? -1 : task.slot()));
                if (outcomes.size() > slots) outcomes.removeFirst();
                assignWaiting(tasks);
            }
        }
        if (totalPending.compareAndSet(true, false)) state = state.withTotal(requestedTotal.get());
        var noticeEntries = pendingNotices.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().order()))
                .toList();
        for (var entry : noticeEntries) {
            if (!pendingNotices.remove(entry.getKey(), entry.getValue())) continue;
            var update = entry.getValue();
            var prior = notices.get(update.notice().key());
            notices.put(update.notice().key(), prior == null
                    ? new NoticeCount(update.notice(), update.count())
                    : new NoticeCount(update.notice(), prior.count() + update.count()));
        }
        var summary = summaryPending.compareAndSet(true, false) ? requestedSummary.get() : state.summary();
        return new State(state.slots(), state.total(), completed, failed, tasks, outcomes, notices, terminal, summary,
                state.tickScheduled(), state.closed());
    }

    private int firstFree(Map<String, Task> tasks) {
        for (var slot = 0; slot < slots; slot++) {
            var used = false;
            for (var task : tasks.values()) {
                if (task.slot() == slot) {
                    used = true;
                    break;
                }
            }
            if (!used) return slot;
        }
        return -1;
    }

    private void assignWaiting(Map<String, Task> tasks) {
        for (var key : List.copyOf(tasks.keySet())) {
            var task = tasks.get(key);
            if (task == null || task.slot() >= 0) continue;
            var slot = firstFree(tasks);
            if (slot < 0) return;
            tasks.put(key, task.withSlot(slot));
        }
    }

    private ProgressFrame emptyFrame() {
        return new ProgressFrame(0, 0, 0, List.of(), 0, List.of(), 0, "");
    }

    private ProgressFrame frame(State state) {
        var active = state.tasks().values().stream()
                .filter(task -> task.slot() >= 0)
                .sorted(Comparator.comparingInt(Task::slot))
                .map(task -> TaskLine.of(task.update(), task.slot())).toList();
        var waiting = (int) state.tasks().values().stream().filter(task -> task.slot() < 0).count();
        var grouped = state.notices().values().stream().map(item ->
                new NoticeLine(item.notice().key(), item.notice().text(), item.count())).toList();
        var candidates = active.size() + state.outcomes().size() + grouped.size();
        var reserve = waiting > 0 || candidates > slots ? 1 : 0;
        var body = slots - reserve;
        var activeCount = Math.min(active.size(), body);
        body -= activeCount;
        var outcomeCount = Math.min(state.outcomes().size(), Math.max(0, body));
        body -= outcomeCount;
        var noticeCount = Math.min(grouped.size(), Math.max(0, body));
        var overflowTasks = waiting + active.size() - activeCount + state.outcomes().size() - outcomeCount;
        var overflowNotices = grouped.size() - noticeCount;
        var tasks = new ArrayList<TaskLine>(activeCount + outcomeCount);
        tasks.addAll(active.subList(0, activeCount));
        tasks.addAll(state.outcomes().subList(
                Math.max(0, state.outcomes().size() - outcomeCount), state.outcomes().size()));
        var notices = grouped.subList(0, noticeCount);
        return new ProgressFrame(state.total(), state.completed(), state.failed(), tasks,
                overflowTasks, notices, overflowNotices, state.summary());
    }

    private static TaskUpdate newer(TaskUpdate old, TaskUpdate next) {
        if (old.terminal()) return old;
        if (next.terminal()) return next;
        var total = old.total() < 0 ? next.total() : next.total() < 0 ? old.total()
                : Math.max(old.total(), next.total());
        return new TaskUpdate(next.id(), next.label(), TaskUpdate.Status.RUNNING,
                Math.max(old.current(), next.current()), total, next.detail());
    }

    private void signal() {
        var actor = system;
        if (actor == null || closing) return;
        if (!wakePending.compareAndSet(false, true)) return;
        if (actor.tell(address, MESSAGES.wakeMessage) != DeliveryStatus.accepted) wakePending.set(false);
    }

    private boolean hasPending() {
        return !pending.isEmpty() || !pendingNotices.isEmpty() || totalPending.get() || summaryPending.get();
    }

    record State(int slots, int total, int completed, int failed,
            Map<String, Task> tasks, List<TaskLine> outcomes, Map<String, NoticeCount> notices,
            Map<String, TaskUpdate.Status> terminal,
            String summary, boolean tickScheduled, boolean closed) {
        private State(int slots) {
            this(slots, 0, 0, 0, Map.of(), List.of(), Map.of(), Map.of(), "", false, false);
        }

        State {
            tasks = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
            outcomes = List.copyOf(outcomes);
            notices = Collections.unmodifiableMap(new LinkedHashMap<>(notices));
            terminal = Collections.unmodifiableMap(new LinkedHashMap<>(terminal));
        }

        private State withTickScheduled(boolean value) {
            return new State(slots, total, completed, failed, tasks, outcomes, notices, terminal, summary, value, closed);
        }
        private State withClosed(boolean value) {
            return new State(slots, total, completed, failed, tasks, outcomes, notices, terminal, summary, tickScheduled, value);
        }
        private State withTotal(int value) {
            return new State(slots, value, completed, failed, tasks, outcomes, notices, terminal, summary, tickScheduled, closed);
        }
    }

    private record Task(TaskUpdate update, int slot) {
        private static Task of(TaskUpdate update, int slot) { return new Task(update, slot); }
        private Task update(TaskUpdate update) { return new Task(update, slot); }
        private Task withSlot(int slot) { return new Task(update, slot); }
    }

    private record PendingTask(long order, TaskUpdate update) {}

    private record PendingNotice(long order, Notice notice, int count) {}

    private record NoticeCount(Notice notice, int count) {}

    private static final class MessageTypeNames {
        private final actors.MessageType wake = actors.MessageType.command("wake-progress");
        private final actors.MessageType tick = actors.MessageType.command("tick-progress");
        private final actors.MessageType closeMessageType = actors.MessageType.command("close-progress");
        private final Message wakeMessage = wake.message();
        private final Message closeMessage = closeMessageType.message();
        private final String render = "terminal.progress.render";
        private final String close = "terminal.progress.close";
    }
}
