package actors;

import application.Application;
import application.Message;
import application.Step;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import json.Json;

/// One live actor: an [application.Application] with a name, a mailbox, and a
/// log.
///
/// ## How it works
///
/// A virtual thread owns the actor. It replays the log, delivers
/// `actors.resumed`, and then takes one message at a time from the mailbox
/// forever. Because that thread is the only one that touches the state, no
/// update function ever needs a lock.
///
/// Handling one message is three steps in a fixed order:
///
/// 1. Append the command to the log, unless it is a control message.
/// 2. Advance the state with it. No effect runs here.
/// 3. Carry out the effects, and put whatever they emit back into this actor's
///    own mailbox, where each one is logged in its turn.
///
/// Step 3 is what makes step 1 sufficient. An effect that reads a socket or a
/// clock hands back what it learned as a message, that message enters the
/// mailbox, and the log records it. Replay therefore never needs to run an
/// effect to know what the outside world said.
///
/// ## The law
///
/// **Effects never run during replay.** [#replay()] calls
/// [Application#advance(Message)] and drops the effects on the floor. That is
/// the entire mechanism, and every other guarantee in this package rests on it.
///
/// The obligation it puts on a definition is one sentence: an update function
/// must be a pure function of its state and its message. No clock, no random
/// number, no file, no socket. The only "now" available is [Delivery#at()], the
/// timestamp of the message being handled, which is recorded and so replays
/// unchanged.
///
/// An update reads it as [application.Message#at()], because the actor stamps
/// the timestamp into the message before the update sees it. The log keeps the
/// command as it was sent and the timestamp in its own column, so what is
/// stamped live and what is stamped on replay are the same number, and a
/// deadline computed from it survives replay exactly. A message that already
/// carries an `at` keeps the one its sender gave it.
///
/// ## Failing open, and poison commands
///
/// A command is appended before it is handled, so a command that makes an
/// update throw is in the log forever. That sounds like a way to break an actor
/// permanently, and it is not, because of how failing open composes with the
/// log:
///
/// ```
/// live:    seq 12  add    {"sku": …}       throws, state unchanged
///          seq 13  error  {"reason": …}    the failure, logged like any message
///
/// replay:  seq 12  throws again, state unchanged   the same outcome as live
///          seq 13  replays                         the same outcome as live
/// ```
///
/// Replay reproduces the live history exactly, with no special case for it.
/// [Application#advance(Message)] returns before any effect runs, so a throwing
/// step also emits nothing, and a half-applied step is not possible.
///
/// ## Exceptions and Errors are not the same here
///
/// Failing open catches an `Exception`. It does not catch an `Error`, and it
/// should not: an `OutOfMemoryError` or a `StackOverflowError` says the thread
/// cannot be trusted to continue, not that one message was bad.
///
/// The consequence for a durable actor is worth knowing before it happens. The
/// command that caused the `Error` was appended before it was handled, so
/// summoning the actor again replays it and meets the same `Error` again. The
/// actor cannot recover on its own. That is what the crash-loop brake in
/// [ActorSystem] is for: after [Spawn#restarts()] deaths inside [Spawn#window()] the
/// system stops summoning it, senders are told
/// [Undeliverable.Cause#quarantined], and it takes a person to decide what to
/// do. [ActorSystem#inspectAt(Address, long)] still reads the state up to the
/// command before the poison, which is usually where that person starts.
final class Actor implements Flow.Subscriber<Message> {

    /// The message an actor sends itself to stop.
    static final String STOP = "actors.stop";

    /// The message delivered once, after replay and before any live message.
    static final String RESUMED = "actors.resumed";

    /// Pairs a definition with the application it built, so that the state type
    /// stays inside this object.
    ///
    /// [ActorSystem] holds actors of many types in one map and must not name their
    /// state types. Capturing `S` here is what lets `inspect` call
    /// `definition.inspect(application.state())` without a cast, and it is why
    /// no `S` ever escapes to a caller.
    private static final class Body<S> {

        private final Definition<S> definition;
        private final Application<S> application;

        private Body(Definition<S> definition, Address self) {
            this.definition = definition;
            this.application = definition.instantiate(self);
        }

        static <S> Body<S> of(Definition<S> definition, Address self) {
            return new Body<>(definition, self);
        }

        Step<S> advance(Message message) {
            return application.advance(message);
        }

        List<Message> perform(List<application.Effect> effects) {
            return application.perform(effects);
        }

        Json inspect() {
            return definition.inspect(application.state());
        }

        boolean settled() {
            return definition.settled(application.state());
        }

        Application<S> application() {
            return application;
        }
    }

    private final ActorSystem system;
    private final Address address;
    private final Body<?> body;
    private final Log log;
    private final Mailbox mailbox;
    private final Spawn spawn;
    private final AtomicLong handled = new AtomicLong();
    private volatile Delivery current;
    private volatile Thread thread;
    private volatile boolean finished;
    private volatile boolean settled;
    private volatile long lastAt = System.currentTimeMillis();
    private long seenAbandoned;
    private long seenFenced;
    private Flow.Subscription subscription;

    Actor(ActorSystem system, Address address, Definition<?> definition, Log log, Spawn spawn) {
        this.system = system;
        this.address = address;
        this.body = Body.of(definition, address);
        this.log = log;
        this.spawn = spawn;
        // An application waits for its effects for as long as they take. An
        // actor cannot: a hung effect holds the mailbox thread, and every
        // sender blocked on the full mailbox behind it is held too.
        this.body.application().patience(spawn.effects());
        this.mailbox = new Mailbox(spawn);
    }

    Address address() {
        return address;
    }

    Spawn spawn() {
        return spawn;
    }

    Application<?> application() {
        return body.application();
    }

    /// The delivery being handled right now, which is how `actor.reply` finds
    /// out who asked. It is set for the whole step, so the effect handlers of
    /// that step read the same value.
    Delivery current() {
        return current;
    }

    Json inspect() {
        return body.inspect();
    }

    long commands() {
        return log.length();
    }

    int depth() {
        return mailbox.depth();
    }

    boolean finished() {
        return finished;
    }

    /// Whether the definition called this actor settled after the last message
    /// it handled.
    ///
    /// The value is computed on the actor's own thread and published here, so a
    /// sweeper can read it without touching the state. Asking the definition
    /// from another thread would read a state that the actor thread is
    /// allowed to be replacing at that moment.
    boolean settled() {
        return settled;
    }

    /// When this actor last finished handling a message, in epoch
    /// milliseconds. A freshly built actor counts as active, so that summoning
    /// one and sweeping immediately afterwards cannot evict it before it has
    /// read its first message.
    long lastAt() {
        return lastAt;
    }

    /// Whether this actor is in the middle of something.
    ///
    /// A sweeper must not evict an actor with mail waiting or a step in
    /// flight. Eviction goes through the mailbox as a control message, so it
    /// would be handled in order and would not interrupt anything, but it would
    /// still throw away messages that a sender has already been told were
    /// accepted.
    boolean busy() {
        return current != null || mailbox.depth() > 0;
    }

    /// Starts the thread that owns this actor. Replay happens on that thread
    /// rather than on the caller's, so summoning an actor with a long history
    /// does not block whoever sent the message that summoned it.
    ///
    /// An actor never closes its log. [Logs] opens one log per address and
    /// hands the same one back every time, because two connections writing one
    /// SQLite file is a way to meet `SQLITE_BUSY` for no reason. An actor that
    /// closed the log on its way out would leave a closed handle in that map
    /// and break the next summon, and it would also stop
    /// [ActorSystem#inspectAt(Address, long)] from reading the history of an actor
    /// that has been evicted. The store owns the lifetime and closes everything
    /// when the system closes.
    void start() {
        thread = Thread.ofVirtual().name("actor:" + address).start(this::loop);
    }

    Mailbox.Admission offer(Delivery delivery) throws InterruptedException {
        if (finished) return Mailbox.Admission.busy;
        return mailbox.offer(delivery);
    }

    void control(Delivery delivery) {
        mailbox.control(delivery);
    }

    /// Asks the actor to stop once it reaches this message. Stopping is a
    /// message rather than an interrupt, so it can never arrive in the middle
    /// of a step and leave a command logged but unhandled.
    void stop() {
        mailbox.control(Delivery.of(address, Message.of(STOP)));
    }

    List<Delivery> abandonedMail() {
        return mailbox.drain();
    }

    private void loop() {
        try {
            var started = System.nanoTime();
            replay();
            system.trace(address, Trace.Kind.summoned, Json.Object.of()
                    .with("commands", log.length())
                    .with("millis", (System.nanoTime() - started) / 1_000_000.0));
            resume();
            while (true) {
                var delivery = mailbox.take();
                if (delivery.command().type().equals(STOP)) return;
                handle(delivery);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable death) {
            system.died(this, death);
        } finally {
            finished = true;
            system.trace(address, Trace.Kind.evicted, Json.Object.of()
                    .with("handled", handled.get())
                    .with("settled", settled));
            system.unloaded(this);
        }
    }

    /// Rebuilds the state from the log.
    ///
    /// Everything at or below [Log#applied()] is advanced with its effects
    /// suppressed, because those effects already happened and running them again
    /// would send last week's mail a second time. That part is the law of this
    /// package and it never changes.
    ///
    /// The tail above the mark is a command that was written down and whose
    /// effects may not have finished. What happens to it is
    /// [Spawn#redelivers()]: by default it is advanced quietly like everything
    /// else, so its effects are lost; when redelivery is on it is handled
    /// properly, effects and all, without being appended a second time.
    private void replay() {
        var quiet = spawn.redelivers() ? log.applied() : java.lang.Long.MAX_VALUE;
        try (var commands = log.replay()) {
            commands.forEach(command -> {
                if (Log.seq(command) <= quiet) {
                    advanceQuietly(command);
                    return;
                }
                redeliver(command);
            });
        }
    }

    private void advanceQuietly(Message command) {
        current = Delivery.replayed(address, command);
        try {
            body.advance(command);
        } finally {
            current = null;
        }
    }

    /// Handles a logged command whose effects did not finish.
    ///
    /// The command is already in the log, so this must not append it again.
    /// Everything else is an ordinary step: the state advances, the effects run,
    /// what they emit goes into the mailbox, and the mark moves up to cover it.
    private void redeliver(Message command) {
        apply(Delivery.replayed(address, command), Log.seq(command));
    }

    /// Delivers `actors.resumed` with effects enabled.
    ///
    /// Replay rebuilt the state and ran nothing, so anything that lived outside
    /// the log is gone: a timer that had been scheduled, a request that was in
    /// flight, a cache that was loaded from a database. This is the one place
    /// an actor gets to put them back. It is a control message, so it is not
    /// logged and it does not become part of the history.
    private void resume() {
        handle(Delivery.of(address, Message.of(RESUMED)));
    }

    private void handle(Delivery delivery) {
        var seq = delivery.control() ? 0 : log.append(delivery.command());
        apply(delivery, seq);
    }

    /// Advances the state, carries out the effects, and marks the command
    /// applied.
    ///
    /// The order is the whole durability story, so it is worth being exact about
    /// what each gap costs. `seq` is zero for a control message, which is never
    /// logged and so never marked.
    ///
    ///   1. The command is already appended when this runs. A crash before that
    ///      point loses it completely, and no mark can help, because the mailbox
    ///      is in memory and a message it accepted was never written anywhere.
    ///   2. The state advances. A crash here leaves the command above the mark.
    ///   3. The effects run. A crash part way through leaves some of them done
    ///      and the command still above the mark, which is why redelivery
    ///      demands idempotent effects: it repeats all of them, including the
    ///      ones that finished.
    ///   4. What the effects emitted goes into the mailbox. Those messages are
    ///      in memory and nowhere else until they are themselves handled and
    ///      appended, so a crash here loses them. Redelivery recovers this case
    ///      by running the effect again; the default mode does not, and the loss
    ///      leaves no trace.
    ///   5. The mark moves up. From here the command is settled and a later
    ///      summon replays it quietly.
    private void apply(Delivery delivery, long seq) {
        current = delivery;
        try {
            var step = body.advance(delivery.command());
            var emitted = body.perform(step.effects());
            for (var message : emitted) mailbox.self(new Delivery(message.at(clock()), address, address, null, null));
            if (seq > 0) log.applied(seq);
            handled.incrementAndGet();
            lastAt = clock();
            settled = asks();
            system.registry().handled(address, delivery.at());
            losses();
            if (system.tracingMessages()) {
                system.trace(address, Trace.Kind.handled, Json.Object.of()
                        .with("type", delivery.command().type())
                        .with("seq", seq));
            }
        } finally {
            current = null;
        }
    }

    /// Publishes a trace when the application has abandoned an effect or thrown
    /// away a late one since the last message.
    ///
    /// [application.Application] counts both and has no callback, so this reads
    /// the counters after each step and reports the difference. An abandoned
    /// effect is therefore seen as soon as the step that abandoned it ends. A
    /// fenced emission is seen later, because an effect that was abandoned may
    /// finish minutes afterwards and there is no step in progress when it does.
    /// It is picked up by the next message, or by the periodic gauge.
    private void losses() {
        var stopped = body.application().abandoned();
        if (stopped > seenAbandoned) {
            seenAbandoned = stopped;
            system.trace(address, Trace.Kind.abandoned, Json.Object.of().with("total", stopped));
        }
        var late = body.application().fenced();
        if (late > seenFenced) {
            seenFenced = late;
            system.trace(address, Trace.Kind.fenced, Json.Object.of().with("total", late));
        }
    }

    /// Asks the definition whether this actor is settled, on the thread that
    /// owns the state.
    ///
    /// A definition that throws here answers false. The value is a hint for
    /// passivation, so a broken hint must not be able to kill an actor that is
    /// otherwise working.
    private boolean asks() {
        try {
            return body.settled();
        } catch (Exception e) {
            return false;
        }
    }

    /// The timestamp a message an effect produced is stamped with.
    ///
    /// A message from an effect is new to the actor, so it carries the moment
    /// it entered the mailbox rather than the moment of the message that caused
    /// it. That value is logged, which is what makes it replay.
    private long clock() {
        return System.currentTimeMillis();
    }

    // ---- Flow.Subscriber -------------------------------------------------
    //
    // An actor subscribes to a publisher of messages, and the mailbox bound
    // becomes the demand. A publisher is therefore slowed down by a slow actor
    // instead of filling memory in front of it.

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(spawn.mailbox());
    }

    @Override
    public void onNext(Message message) {
        try {
            mailbox.offer(Delivery.of(address, message));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (subscription != null) subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        var reason = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
        mailbox.control(Delivery.of(address, Message.error(reason).with("while", "subscription")));
    }

    @Override
    public void onComplete() {
        // the publisher is finished; the actor is not
    }
}
