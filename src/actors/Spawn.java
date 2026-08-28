package actors;

import java.time.Duration;

/// The options that decide how one actor runs.
///
/// ## Durability is chosen here, not in the definition
///
/// The same [Definition] can back a durable actor and an undurable one. That is
/// why this is a spawn option rather than a property of the definition: whether
/// a state has another home is a deployment question, not a modelling one.
///
/// A projection actor is the case that makes this matter. Its state lives in a
/// database it writes through effects, so recording a second copy in a log
/// would mean two records of one truth, and two records of one truth disagree.
/// It runs with [#ephemeral()] and keeps no log. A basket, whose decisions have
/// no other home, runs with [#durable()].
///
/// The rule that follows: **an actor is durable, or its state lives in a store
/// it writes through effects. Never both.**
///
/// @param keepsLog whether commands are appended to a log and replayed on
///                 summon. When false the actor uses [Log#none()] and starts
///                 empty every time it is summoned.
/// @param mailbox  how many messages may wait before a sender is made to wait
/// @param patience how long a sender blocks on a full mailbox before it is told
///                 the actor is busy
/// @param restarts how many times an actor may die inside `window` before it is
///                 quarantined
/// @param window   the period the restart count is measured over
/// @param effects  how long the effects of one step may run before the actor
///                 stops waiting for them and reports `error.timeout`
public record Spawn(boolean keepsLog, int mailbox, Duration patience, int restarts, Duration window,
        Duration effects) {

    private static final Spawn DURABLE =
            new Spawn(true, 1024, Duration.ofSeconds(5), 5, Duration.ofMinutes(1), Duration.ofSeconds(30));

    public Spawn {
        if (mailbox < 1) throw new IllegalArgumentException("a mailbox holds at least one message: " + mailbox);
        if (restarts < 1) throw new IllegalArgumentException("allow at least one restart: " + restarts);
    }

    /// An actor whose commands are logged and replayed. This is the default.
    public static Spawn durable() {
        return DURABLE;
    }

    /// An actor that keeps no log. It starts empty every time it is summoned,
    /// and a crash loses its state, because there is nothing to replay.
    public static Spawn ephemeral() {
        return DURABLE.with(false);
    }

    public Spawn with(boolean keepsLog) {
        return new Spawn(keepsLog, mailbox, patience, restarts, window, effects);
    }

    public Spawn mailbox(int mailbox) {
        return new Spawn(keepsLog, mailbox, patience, restarts, window, effects);
    }

    public Spawn patience(Duration patience) {
        return new Spawn(keepsLog, mailbox, patience, restarts, window, effects);
    }

    public Spawn restarts(int restarts, Duration window) {
        return new Spawn(keepsLog, mailbox, patience, restarts, window, effects);
    }

    /// How long the effects of one step may run.
    ///
    /// An actor bounds this where a plain [application.Application] does not.
    /// A hung effect inside an actor does not stop one call. It stops the
    /// mailbox thread, and every sender that blocks on the full mailbox behind
    /// it stops too. Abandoning a leaked thread is the cheaper failure.
    public Spawn effects(Duration effects) {
        return new Spawn(keepsLog, mailbox, patience, restarts, window, effects);
    }
}
