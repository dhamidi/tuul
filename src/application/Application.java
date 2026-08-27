package application;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;
import json.Json;

/// An application: a state, the handlers that update it, and the handlers that
/// carry out the effects those updates ask for.
///
/// ```
/// var app = Application.of(0)
///         .on("add", (state, message) -> Step.of(state + 1, Effect.of("log").with("line", "added")))
///         .effect("log", (effect, emit) -> System.out.println(effect.string("line", "")));
///
/// app.dispatch(Message.of("add"));
/// ```
///
/// [#dispatch(Message...)] runs until nothing is pending: update the state,
/// apply the effects of that step, feed whatever they emit back in. The effects
/// of one step run together, each on its own virtual thread, and the step is
/// over only when all of them are.
///
/// The loop fails open at both ends. An update that throws leaves the state
/// alone and produces an `error` message; so does an effect with no handler, or
/// a handler that throws. Nothing an application does to itself can stop it
/// from finishing the messages it already has.
///
/// One application, one thread of dispatch: handlers run concurrently but only
/// ever emit, and the state is touched by the dispatching thread alone.
public final class Application<S> {

    private final Map<String, Update<S>> updates = new LinkedHashMap<>();
    private final Map<String, Effect.Handler> handlers = new LinkedHashMap<>();
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

    /// Runs the messages, and everything they lead to, to a standstill.
    public S dispatch(Message... messages) {
        var pending = new ArrayDeque<Message>(List.of(messages));
        while (!pending.isEmpty()) {
            var step = step(pending.poll(), pending);
            state = step.state();
            pending.addAll(apply(step.effects()));
        }
        return state;
    }

    private Step<S> step(Message message, Queue<Message> pending) {
        try {
            return updates.getOrDefault(message.type(), Updates.ignore()).update(state, message);
        } catch (Exception e) {
            if (!message.type().equals("error")) pending.add(failure(e).with("while", message.type()));
            return Step.of(state);
        }
    }

    /// Structured concurrency, by the route that is not a preview API: the
    /// effects of a step are forked into an executor that is closed before the
    /// method returns, and `close()` does not return until every one of them has
    /// finished. Their lifetime is the block, which is the whole point.
    ///
    /// Nothing here propagates a failure, because there is nothing to propagate
    /// — an effect that throws has already been turned into an `error` message
    /// by [#run]. That is what makes an ordinary executor enough, and it is why
    /// this does not reach for `StructuredTaskScope` while that is still a
    /// preview API: tuul's class files would be pinned to one exact JDK build,
    /// and so would every project that vendors them.
    private List<Message> apply(List<Effect> effects) {
        if (effects.isEmpty()) return List.of();
        var emitted = new ConcurrentLinkedQueue<Message>();
        try (var effecting = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var effect : effects) effecting.execute(() -> run(effect, emitted::add));
        }
        if (Thread.currentThread().isInterrupted()) emitted.add(Message.error("interrupted while applying effects"));
        return List.copyOf(emitted);
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
        if (effect.get("message") instanceof Json.Object message) emit.emit(new Message(message));
    }

    private static Message failure(Throwable e) {
        var cause = e.getCause() != null ? e.getCause() : e;
        var reason = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return Message.error(reason).with("exception", cause.getClass().getName());
    }
}
