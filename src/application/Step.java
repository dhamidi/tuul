package application;

import java.util.ArrayList;
import java.util.List;

/// What an update function returns: the next state, plus the effects to apply
/// before the application settles.
public record Step<S>(S state, List<Effect> effects) {

    public Step {
        effects = List.copyOf(effects);
    }

    public static <S> Step<S> of(S state, Effect... effects) {
        return new Step<>(state, List.of(effects));
    }

    public Step<S> then(Effect effect) {
        var next = new ArrayList<>(effects);
        next.add(effect);
        return new Step<>(state, next);
    }

    /// Keeps `other`'s state and runs both sets of effects — how two update
    /// functions handling the same message are combined.
    public Step<S> merge(Step<S> other) {
        var next = new ArrayList<>(effects);
        next.addAll(other.effects());
        return new Step<>(other.state(), next);
    }
}
