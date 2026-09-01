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
/// A collection or derived-view actor backed by a database is the case that
/// makes this matter. Its state lives in a database it writes through effects,
/// so recording a second copy in a log would mean two records of one truth.
/// It runs with [#ephemeral()] and keeps no log. A basket, whose decisions have
/// no other home, runs with [#durable()].
///
/// The rule that follows: **an actor is durable, or its state lives in a store
/// it writes through effects. Never both.**
///
/// ## Passivation
///
/// An actor that nobody has spoken to for [#idle()] is evicted. Summoning on
/// demand and evicting when idle are the two halves of the virtual actor
/// pattern, and a system with only the first half holds every actor it has ever
/// touched for as long as it runs.
///
/// Eviction is not loss. A durable actor comes back from its log with the same
/// state, so the only cost of passivating one is the replay that brings it back.
/// An undurable actor loses its state, which is why an undurable actor that must
/// stay in memory sets [#idle(Duration)] to zero and never passivates.
///
/// ## What a crash costs, and the choice it forces
///
/// A command is appended before it is handled, and its effects run after that.
/// A process that stops in between leaves a command in the log whose effects
/// never happened. A redelivering actor uses [Log#applied()] to record how far
/// its effects got. Everything above that mark is the *tail*. The default mode
/// does not read or write the mark.
///
/// The two ways to treat the tail are genuinely different promises, and neither
/// is free:
///
/// | | `redelivers` false — the default | `redelivers` true |
/// |---|---|---|
/// | The tail on summon | advanced into the state, effects suppressed | advanced **and** its effects carried out |
/// | Effects happen | at most once | at least once |
/// | A crash mid-step costs | the effect never happens | the effect may happen twice |
/// | An actor charging a card | undercharges | double-charges, unless charging is idempotent |
/// | Requires of the definition | nothing | **every effect must be idempotent** |
///
/// The default is at-most-once because it is what this package has always done,
/// and because a behaviour that can double-charge a customer has to be asked
/// for rather than arrive with an upgrade. The state is durable either way. It
/// is the *actions* that differ.
///
/// Turn it on for an actor whose effects are safe to repeat: writing a row keyed
/// by something the command already carries, sending a message that the receiver
/// deduplicates, telling another actor a fact it can be told twice. Leave it off
/// for anything that spends money, sends mail, or otherwise cannot be taken
/// back, unless that effect carries an idempotency key of its own.
///
/// Redelivery also recovers a loss that is otherwise silent. An effect that
/// finished and handed back a message is only durable once that message has
/// itself been appended, and a crash in the gap loses it with no trace. Running
/// the effect again produces the message again.
///
/// @param keepsLog whether commands are appended to a log and replayed on
///                 summon. When false the actor uses [Log#none()] and starts
///                 empty every time it is summoned.
/// @param mailbox  how many inbound messages may wait before a tell returns
///                 [DeliveryStatus#busy]
/// @param restarts how many times an actor may die inside `window` before it is
///                 quarantined
/// @param window   the period the restart count is measured over
/// @param effects  how long the effects of one step may run before the actor
///                 stops waiting for them and reports `handle-timeout`
/// @param idle     how long an actor may sit unused before it is evicted. Zero
///                 or less means it is never evicted for being idle.
/// @param durability how hard the log works to keep a command it has just
///                 appended. See [Durability].
/// @param redelivers whether commands that were logged but whose effects did
///                 not finish are carried out again on the next summon. False
///                 by default: see the table above.
public record Spawn(boolean keepsLog, int mailbox, int restarts, Duration window,
        Duration effects, Duration idle, Durability durability, boolean redelivers) {

    private static final Spawn DURABLE = new Spawn(true, 1024, 5,
            Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofMinutes(5), Durability.normal, false);

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
        return new Spawn(keepsLog, mailbox, restarts, window, effects, idle, durability, redelivers);
    }

    public Spawn mailbox(int mailbox) {
        return new Spawn(keepsLog, mailbox, restarts, window, effects, idle, durability, redelivers);
    }

    public Spawn restarts(int restarts, Duration window) {
        return new Spawn(keepsLog, mailbox, restarts, window, effects, idle, durability, redelivers);
    }

    /// How long the effects of one step may run.
    ///
    /// The actor runtime applies this bound to the external effects of one
    /// step. Actor-routing effects run inline and do not use this bound.
    public Spawn effects(Duration effects) {
        return new Spawn(keepsLog, mailbox, restarts, window, effects, idle, durability, redelivers);
    }

    /// How long this actor may sit unused before it is evicted.
    ///
    /// Zero or less turns passivation off for this actor. Use that for the few
    /// actors that must answer without a replay first, and for undurable actors
    /// whose state has nowhere to come back from.
    public Spawn idle(Duration idle) {
        return new Spawn(keepsLog, mailbox, restarts, window, effects, idle, durability, redelivers);
    }

    /// How hard this actor's log works to keep a command it has just appended.
    ///
    /// [Durability#normal] is the default and can lose the last few commands to
    /// a power cut. [Durability#full] cannot, and pays one flush per message.
    public Spawn durability(Durability durability) {
        return new Spawn(keepsLog, mailbox, restarts, window, effects, idle, durability, redelivers);
    }

    /// Whether the unapplied tail of the log is carried out again on the next
    /// summon.
    ///
    /// Turning this on changes what a crash costs, in both directions. Read the
    /// table in this class before doing it, and make the actor's effects
    /// idempotent first.
    public Spawn redelivers(boolean redelivers) {
        return new Spawn(keepsLog, mailbox, restarts, window, effects, idle, durability, redelivers);
    }

    /// Whether this actor is ever evicted for sitting idle.
    public boolean passivates() {
        return !idle.isZero() && !idle.isNegative();
    }
}
