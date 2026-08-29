package application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.time.Duration;
import json.Json;

/// An application holds a state, the handlers that update it, and the handlers
/// that carry out the effects those updates ask for.
///
/// ```
/// var app = Application.of(0)
///         .on("add", (state, message) -> Step.of(state + 1, Effect.of("log").with("line", "added")))
///         .effect("log", (effect, emit) -> System.out.println(effect.string("line", "")));
///
/// app.dispatch(Message.of("add"));
/// ```
///
/// ## The loop is two halves
///
/// [#advance(Message)] updates the state and returns the effects the update
/// asked for. It never carries an effect out. [#perform(List)] carries effects
/// out and returns the messages they emitted.
///
/// [#dispatch(Message...)] is one way to put those halves together: advance,
/// perform, feed what came back in, repeat until nothing is pending. It is not
/// the only way. A durable actor advances a logged message without performing
/// anything, which is how replay rebuilds a state without sending last week's
/// email a second time. Splitting the halves is what makes that possible, and
/// it is the reason both are public.
///
/// ## Failing open
///
/// The loop fails open at both ends. An update that throws leaves the state
/// alone and asks for one effect that sends an `error` message back. An effect
/// with no handler, or a handler that throws, emits an `error` message too.
/// Nothing an application does to itself can stop it from finishing the
/// messages it already has.
///
/// A failure reported as an effect rather than as a direct enqueue matters to
/// anything that replays. The failure is a message like any other, so it
/// travels the same path, and a caller that suppresses effects also suppresses
/// the second report of a failure it already recorded.
///
/// ## One thread of dispatch
///
/// Handlers run concurrently but only ever emit. The state is touched by the
/// dispatching thread alone.
public final class Application<S> {

    private final Map<String, Update<S>> updates = new LinkedHashMap<>();
    private final Map<String, Effect.Handler> handlers = new LinkedHashMap<>();
    private final AtomicLong abandoned = new AtomicLong();
    private final AtomicLong fenced = new AtomicLong();

    /// How long one step of effects may run. Null means that the step waits for
    /// as long as the effects take.
    ///
    /// Waiting is the default because an effect here is often the slow, useful
    /// work an application exists to do. `tuul` cross-builds SQLite for six
    /// platforms, scaffolds a project and runs a test suite through effects,
    /// and each of those takes minutes. A default bound would abandon them, the
    /// state would never see the result, and the command would look like it had
    /// done nothing.
    ///
    /// A caller that cannot afford to wait sets a bound with
    /// [#patience(Duration)]. An actor does, because a hung effect there stops
    /// a mailbox and every sender queued behind it.
    private Duration patience;
    private S state;

    private Application(S initial) {
        state = initial;
        effect(Effect.SEND, Application::send);
    }

    public static <S> Application<S> of(S initial) {
        return new Application<>(initial);
    }

    /// What to do when a message of this type arrives. Registering a second
    /// handler for the same type runs both, in registration order — that is how
    /// one message reaches several parts of an application.
    public Application<S> on(String type, Update<S> update) {
        updates.merge(type, update, (first, second) -> Updates.all(List.of(first, second)));
        return this;
    }

    /// How to carry out an effect of this type. There is one way to carry out
    /// an effect, so registering a second handler replaces the first — which is
    /// what makes a handler swappable for a test.
    public Application<S> effect(String type, Effect.Handler handler) {
        handlers.put(type, handler);
        return this;
    }

    /// How long [#perform(List)] waits for the effects of one step. Null
    /// restores the default, which is to wait for as long as they take.
    public Application<S> patience(Duration patience) {
        this.patience = patience;
        return this;
    }

    /// Folds another application into this one: its messages now update a part
    /// of this state, and its effect handlers come along with it. Handlers this
    /// application already has win, so a host can always override a component.
    public <C> Application<S> include(Application<C> child, Function<S, C> read, BiFunction<S, C, S> write) {
        child.updates.forEach((type, update) -> on(type, Updates.nested(read, write, update)));
        child.handlers.forEach(handlers::putIfAbsent);
        return this;
    }

    public S state() {
        return state;
    }

    /// How many effects this application stopped waiting for. Every one of them
    /// is a thread that may still be running. A number that climbs is a handler
    /// that blocks forever, and it needs a person.
    public long abandoned() {
        return abandoned.get();
    }

    /// How many messages arrived from an effect that had already been
    /// abandoned. They were dropped rather than delivered.
    public long fenced() {
        return fenced.get();
    }

    /// Runs the messages, and everything they lead to, to a standstill.
    public S dispatch(Message... messages) {
        var pending = new ArrayDeque<Message>(List.of(messages));
        while (!pending.isEmpty()) {
            var step = advance(pending.poll());
            pending.addAll(perform(step.effects()));
        }
        return state;
    }

    /// Updates the state with one message and answers with the step that
    /// produced it. No effect runs here.
    ///
    /// The state is committed before this returns, so the caller reads it back
    /// from [#state()] or from the step. A caller that wants the state without
    /// the consequences — a replay, a shadow copy rebuilt to a point in its
    /// history — calls this and never calls [#perform(List)].
    ///
    /// An update that throws leaves the state alone and answers with a step
    /// whose one effect sends an `error` message. An update that throws while
    /// handling an `error` message answers with nothing, because an application
    /// that reports an error about an error never stops.
    public Step<S> advance(Message message) {
        var step = update(message);
        state = step.state();
        return step;
    }

    /// Carries out effects and answers with the messages they emitted.
    ///
    /// Every effect runs on its own virtual thread and the method returns when
    /// all of them have finished, or when [#patience(Duration)] runs out.
    ///
    /// ## Why this is not a try-with-resources
    ///
    /// An executor closed by try-with-resources does not return from `close()`
    /// until every task has finished, with no way to say how long that may
    /// take. One handler blocked on a socket that never answers would hold the
    /// step open forever, and for an actor that means a mailbox that never
    /// moves again and senders that block behind it. So this shuts the executor
    /// down, waits for the patience, and then interrupts what is left.
    ///
    /// ## The trade-off, stated plainly
    ///
    /// A Java thread cannot be reliably stopped. `shutdownNow` interrupts, and
    /// an interrupt unblocks most I/O on a virtual thread, but it does not
    /// unblock a call into native code through the foreign function interface.
    /// The choice is therefore a wedged application or a leaked thread. This
    /// leaks the thread and counts it in [#abandoned()]. A handler should still
    /// set a timeout of its own; this bound is the backstop, not the plan.
    ///
    /// An abandoned effect that finishes later must not emit into a loop that
    /// has moved on, because its message would arrive out of order and, for a
    /// durable actor, after the log had already recorded what happened instead.
    /// Emissions past the end of the step are dropped and counted in
    /// [#fenced()].
    ///
    /// Nothing here propagates a failure, because there is nothing to propagate
    /// — an effect that throws has already been turned into an `error` message
    /// by [#run]. That is why an ordinary executor is enough, and it is why this
    /// does not reach for `StructuredTaskScope` while that is still a preview
    /// API: tuul's class files would be pinned to one exact JDK build, and so
    /// would every project that vendors them.
    public List<Message> perform(List<Effect> effects) {
        if (effects.isEmpty()) return List.of();
        var fence = new Fence(fenced);
        var outstanding = new ConcurrentHashMap<Integer, Effect>();
        for (var index = 0; index < effects.size(); index++) outstanding.put(index, effects.get(index));

        var effecting = Executors.newVirtualThreadPerTaskExecutor();
        for (var index = 0; index < effects.size(); index++) {
            var position = index;
            var effect = effects.get(index);
            effecting.execute(() -> {
                try {
                    run(effect, fence);
                } finally {
                    outstanding.remove(position);
                }
            });
        }
        effecting.shutdown();
        waitFor(effecting, outstanding, fence);

        var emitted = fence.close();
        if (Thread.currentThread().isInterrupted()) emitted.add(Message.error("interrupted while applying effects"));
        return List.copyOf(emitted);
    }

    private void waitFor(java.util.concurrent.ExecutorService effecting,
            Map<Integer, Effect> outstanding, Fence fence) {
        try {
            if (patience == null) {
                while (!effecting.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) continue;
                return;
            }
            if (effecting.awaitTermination(patience.toMillis(), TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            effecting.shutdownNow();
            return;
        }
        effecting.shutdownNow();
        abandoned.addAndGet(outstanding.size());
        outstanding.values().forEach(effect -> fence.emit(Message.of("error.timeout")
                .with("reason", "the effect did not finish within " + patience)
                .with("while", effect.type())));
    }

    private Step<S> update(Message message) {
        try {
            return updates.getOrDefault(message.type(), Updates.ignore()).update(state, message);
        } catch (Exception e) {
            if (message.type().equals("error")) return Step.of(state);
            return Step.of(state, Effect.send(failure(e).with("while", message.type())));
        }
    }

    private void run(Effect effect, Effect.Emitter emit) {
        var handler = handlers.get(effect.type());
        if (handler == null) {
            emit.emit(Message.error("no handler for effect: " + effect.type()));
            return;
        }
        try {
            handler.run(effect, emit);
        } catch (Exception e) {
            emit.emit(failure(e).with("while", effect.type()));
        }
    }

    private static void send(Effect effect, Effect.Emitter emit) {
        emit.emit(effect.message());
    }

    private static Message failure(Throwable e) {
        var cause = e.getCause() != null ? e.getCause() : e;
        var reason = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return Message.error(reason).with("exception", cause.getClass().getName());
    }

    /// Collects what the effects of one step emit, and stops collecting when
    /// the step is over.
    ///
    /// The gate is what makes an abandoned effect harmless. Once `close` has
    /// run, a late emission is counted and thrown away instead of being handed
    /// to a loop that has already recorded a different outcome.
    ///
    /// A late emission may arrive minutes after the step ended, so the count
    /// goes straight to the application's own counter rather than being read
    /// back when the step finishes. Reading it at the end of the step would
    /// always report zero, because nothing has been dropped yet.
    private static final class Fence implements Effect.Emitter {

        private final List<Message> emitted = new ArrayList<>();
        private final AtomicLong dropped;
        private boolean open = true;

        private Fence(AtomicLong dropped) {
            this.dropped = dropped;
        }

        @Override
        public synchronized void emit(Message message) {
            if (!open) {
                dropped.incrementAndGet();
                return;
            }
            emitted.add(message);
        }

        private synchronized List<Message> close() {
            open = false;
            return new ArrayList<>(emitted);
        }
    }
}
