package application;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/// Ways to build a bigger update function out of smaller ones. [Application]
/// uses these to register several handlers for one message type and to fold one
/// application into another.
public final class Updates {

    private Updates() {}

    /// Ignores every message. The identity of [#all(List)].
    public static <S> Update<S> ignore() {
        return (state, message) -> Step.of(state);
    }

    /// Runs every update against the same message, threading the state through
    /// them and collecting all of their effects.
    public static <S> Update<S> all(List<Update<S>> updates) {
        return (state, message) -> {
            var step = Step.of(state);
            for (var update : updates) step = step.merge(update.update(step.state(), message));
            return step;
        };
    }

    /// Lifts an update over part of a state into an update over the whole,
    /// which is what lets one application be a component of another.
    public static <S, C> Update<S> nested(Function<S, C> read, BiFunction<S, C, S> write, Update<C> child) {
        return (state, message) -> {
            var step = child.update(read.apply(state), message);
            return new Step<>(write.apply(state, step.state()), step.effects());
        };
    }
}
