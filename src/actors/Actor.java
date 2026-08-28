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
/// ## Failing open, and poison commands
///
/// A command is appended before it is handled, so a command that makes an
/// update throw is in the log forever. That sounds like a way to break an actor
/// permanently, and it is not, because of how failing open composes with the
/// log:
///
/// ```
/// live:    seq 12  {"type":"add", …}          throws, state unchanged
///          seq 13  {"type":"error", …}        the failure, logged like any message
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
/// [System] is for: after [Spawn#restarts()] deaths inside [Spawn#window()] the
/// system stops summoning it, senders are told
/// [Undeliverable.Cause#quarantined], and it takes a person to decide what to
/// do. [System#inspectAt(Address, long)] still reads the state up to the
/// command before the poison, which is usually where that person starts.
final class Actor implements Flow.Subscriber<Message> {

    /// The message an actor sends itself to stop.
    static final String STOP = "actors.stop";

    /// The message delivered once, after replay and before any live message.
    static final String RESUMED = "actors.resumed";

    /// Pairs a definition with the application it built, so that the state type
    /// stays inside this object.
    ///
    /// [System] holds actors of many types in one map and must not name their
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

        Application<S> application() {
            return application;
        }
    }

    private final System system;
    private final Address address;
    private final Body<?> body;
    private final Log log;
    private final Mailbox mailbox;
    private final Spawn spawn;
    private final AtomicLong handled = new AtomicLong();
    private volatile Delivery current;
    private volatile Thread thread;
    private volatile boolean finished;
    private Flow.Subscription subscription;

    Actor(System system, Address address, Definition<?> definition, Log log, Spawn spawn) {
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

    /// Starts the thread that owns this actor. Replay happens on that thread
    /// rather than on the caller's, so summoning an actor with a long history
    /// does not block whoever sent the message that summoned it.
    ///
    /// An actor never closes its log. [Logs] opens one log per address and
    /// hands the same one back every time, because two connections writing one
    /// SQLite file is a way to meet `SQLITE_BUSY` for no reason. An actor that
    /// closed the log on its way out would leave a closed handle in that map
    /// and break the next summon, and it would also stop
    /// [System#inspectAt(Address, long)] from reading the history of an actor
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
            replay();
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
            system.unloaded(this);
        }
    }

    /// Rebuilds the state by advancing every logged command through the
    /// definition that is registered now. Nothing is performed.
    private void replay() {
        try (var entries = log.replay()) {
            entries.forEach(entry -> {
                current = Delivery.replayed(address, entry.command(), entry.at());
                body.advance(entry.command());
                current = null;
            });
        }
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
        current = delivery;
        try {
            if (!delivery.control()) log.append(delivery.command(), delivery.at());
            var step = body.advance(delivery.command());
            var emitted = body.perform(step.effects());
            for (var message : emitted) mailbox.self(new Delivery(message, address, address, null, null, clock()));
            handled.incrementAndGet();
            system.registry().handled(address, delivery.at());
        } finally {
            current = null;
        }
    }

    /// The timestamp a message an effect produced is stamped with.
    ///
    /// A message from an effect is new to the actor, so it carries the moment
    /// it entered the mailbox rather than the moment of the message that caused
    /// it. That value is logged, which is what makes it replay.
    private long clock() {
        return java.lang.System.currentTimeMillis();
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
