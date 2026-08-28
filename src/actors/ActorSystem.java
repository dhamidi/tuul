package actors;

import application.Application;
import application.Effect;
import application.Message;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import json.Json;

/// A named group of actors, and everything that decides how they run.
///
/// ## Summoning
///
/// An actor is not created and it is not started. It is *summoned*: send a
/// message to an address, and if nothing is loaded under that address the
/// system builds an instance from the definition registered right now, replays
/// its log through it, and hands it the message. Every address always exists,
/// and an address nobody has written to yet is an actor with an empty log and
/// an initial state. Nothing distinguishes "new" from "never said anything".
///
/// That is also why there is no restart machinery. When an actor's thread dies
/// the system drops it from the loaded map, and the next message summons it
/// again — which replays the log and produces the real state rather than a
/// fresh one. **Summoning is restart.** A supervision tree would add a second
/// way to do what this already does.
///
/// ## Effects are how an actor reaches anything
///
/// A definition never holds a reference to a system. It asks for an effect, and
/// the system installs the handlers for those effects on every application it
/// builds:
///
///   - `actor.tell` sends a message to an address.
///   - `actor.reply` answers whoever is waiting on the message being handled.
///     It carries no address, because the system already knows.
///   - `actor.ask` sends a message and names this actor as the reply address,
///     which is how a durable conversation is started.
///   - `actor.spawn`, `actor.evict` and `actor.schedule` do what they say.
///
/// Two things follow. A definition can be tested with no system at all, by
/// swapping the handler for one that records. And an address in another system
/// is not a new concept — it is the same effect with a system name in the
/// address, and [Transport] carries it.
///
/// ## Ownership, passivation and watching
///
/// A durable actor is claimed before it is summoned and the claim is released
/// when its thread stops, so two processes cannot run one actor into one log.
/// An actor idle for [Spawn#idle()] is evicted and summoned again by the next
/// message. [#traces()] publishes what the system does as it happens, and
/// [Flight] turns that into JDK Flight Recorder events.
///
/// ## What is deliberately not here
///
/// Hot-reload generations and their class loaders, the browser inspector, the
/// control socket, log snapshots, supervision trees, and links and monitors.
/// Each of them is a real feature and none of them is needed to know whether the
/// core is right. The design leaves room for all of them: [Definition] is
/// already the unit a reload would swap, [Logs] is already the seam a snapshot
/// would use, [#known()] is already what an inspector would read, and
/// [#traces()] is already the feed it would subscribe to.
public final class ActorSystem implements AutoCloseable {

    /// Effect types the system handles for every actor.
    static final String TELL = "actor.tell";
    static final String REPLY = "actor.reply";
    static final String ASK = "actor.ask";
    static final String SPAWN = "actor.spawn";
    static final String EVICT = "actor.evict";
    static final String SCHEDULE = "actor.schedule";

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private final String name;
    private final Map<String, Definition<?>> definitions = new ConcurrentHashMap<>();
    private final Map<String, Spawn> defaults = new ConcurrentHashMap<>();
    private final Map<Address, Spawn> overrides = new ConcurrentHashMap<>();
    private final Map<Address, Actor> loaded = new ConcurrentHashMap<>();
    private final Map<Address, Ownership.Claim> claims = new ConcurrentHashMap<>();
    private final Map<Address, CompletableFuture<Message>> asks = new ConcurrentHashMap<>();
    private final Map<String, Effect.Handler> shared = new LinkedHashMap<>();
    private final Registry registry = new Registry();
    private final Traces traces = new Traces();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong asked = new AtomicLong();
    private final ScheduledExecutorService timers =
            Executors.newScheduledThreadPool(0, Thread.ofVirtual().factory());
    private Logs logs = Logs.none();
    private Ownership ownership = Ownership.shared();
    private Transport transport;
    private Duration sweep = Duration.ofSeconds(30);
    private volatile boolean tracingMessages;
    private final java.util.concurrent.atomic.AtomicBoolean sweeping =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;

    private ActorSystem(String name) {
        this.name = name;
    }

    /// A system with no durable actors. Everything it runs keeps its state in
    /// memory and loses it when the system closes.
    public static ActorSystem named(String name) {
        return new ActorSystem(name);
    }

    public String name() {
        return name;
    }

    /// Keeps the logs of durable actors under this directory, one SQLite file
    /// each, and claims each actor with a file lock before running it.
    ///
    /// A directory is something two processes can both open, so this also
    /// installs [Ownership#files(Path)]. Summoning an actor another process is
    /// already running raises [OwnershipException] rather than quietly starting
    /// a second copy of it.
    public ActorSystem rooted(Path root) {
        logs = Logs.at(root);
        ownership = Ownership.files(root);
        return this;
    }

    /// Uses this store for logs. A test passes [Logs#none()] to make every
    /// actor forgetful without changing a definition.
    ///
    /// Ownership is left as it was, because a store that is not a directory
    /// knows how it arbitrates writers and this class does not. Pair it with
    /// [#owning(Ownership)] when the store needs claims of its own.
    public ActorSystem storing(Logs logs) {
        this.logs = logs;
        return this;
    }

    /// Decides who is allowed to run each actor.
    ///
    /// [#rooted(Path)] already sets this to a file lock. Call this to replace it
    /// — with a cluster lease, or with [Ownership#shared()] for a store that
    /// arbitrates writers itself.
    public ActorSystem owning(Ownership ownership) {
        this.ownership = ownership;
        return this;
    }

    /// How often the system looks for actors to passivate.
    ///
    /// This is the resolution of [Spawn#idle(Duration)] and not a second
    /// timeout. Sweeping more often than an actor's idle period costs a walk of
    /// the loaded map and finds nothing; sweeping less often means an actor
    /// stays loaded for up to one extra period after it went quiet. Neither is
    /// a correctness question, which is why one period serves every actor.
    public ActorSystem sweeping(Duration every) {
        this.sweep = every;
        return this;
    }

    /// Registers a definition. Instances of this type are durable unless a
    /// later call says otherwise.
    public ActorSystem define(Definition<?> definition) {
        return define(definition, Spawn.durable());
    }

    /// Registers a definition and the spawn options its instances use.
    public ActorSystem define(Definition<?> definition, Spawn spawn) {
        definitions.put(definition.type(), definition);
        defaults.put(definition.type(), spawn);
        return this;
    }

    /// Overrides the spawn options for one address. This is how one instance of
    /// a durable type runs without a log, or the other way round.
    public ActorSystem spawn(Address address, Spawn spawn) {
        overrides.put(address.here(), spawn);
        return this;
    }

    /// Registers an effect handler that every actor in this system can use.
    ///
    /// Handlers belong here rather than in a definition because a handler owns
    /// a connection, a file or a socket, and those must survive an actor being
    /// evicted and summoned again. It also keeps definitions pure, which is
    /// what makes replay safe.
    public ActorSystem effect(String type, Effect.Handler handler) {
        shared.put(type, handler);
        return this;
    }

    /// How messages reach systems other than this one.
    public ActorSystem transport(Transport transport) {
        this.transport = transport;
        return this;
    }

    public Registry registry() {
        return registry;
    }

    /// Events from this system as they happen, for a subscriber inside this
    /// process.
    ///
    /// This is the live feed. [Flight] subscribes to it to write JDK Flight
    /// Recorder events, and an inspector would subscribe to it to show a system
    /// working. A subscriber that falls behind loses the oldest events it had
    /// not read, and [#tracesDropped()] counts what was lost.
    public java.util.concurrent.Flow.Publisher<Trace> traces() {
        return traces;
    }

    /// How many trace events a slow subscriber missed.
    public long tracesDropped() {
        return traces.dropped();
    }

    /// Whether to publish a trace for every message an actor handles.
    ///
    /// Off by default. An actor handling ten thousand messages a second would
    /// bury every other event, and the per-message trace is only worth having
    /// while somebody is watching one actor closely.
    public ActorSystem tracingMessages(boolean tracingMessages) {
        this.tracingMessages = tracingMessages;
        return this;
    }

    boolean tracingMessages() {
        return tracingMessages;
    }

    /// Publishes a trace, doing no work when nothing is listening.
    void trace(Address address, Trace.Kind kind, Json detail) {
        if (!traces.watched()) return;
        traces.publish(Trace.of(address, kind, detail));
    }

    /// How many `error.communication` notices had nowhere to go. A notice that
    /// cannot be delivered is dropped rather than answered, because two full
    /// mailboxes pointed at each other would otherwise answer each other
    /// forever.
    public long dropped() {
        return dropped.get();
    }

    // ---- sending ---------------------------------------------------------

    /// Sends a message and does not wait.
    public void tell(Address to, Message message) {
        deliver(Delivery.of(to, message));
    }

    /// Sends a message from one actor to another, so that a failure can be
    /// reported back to the sender.
    public void tell(Address to, Message message, Address from) {
        deliver(Delivery.of(to, message).from(from));
    }

    /// Sends a message and waits for one answer.
    ///
    /// The reply address is an ordinary address in this system, and it is the
    /// correlation: no table of outstanding requests, no sequence numbers, and
    /// an ask works across a [Transport] because the answer routes back the
    /// same way any message does.
    ///
    /// Every ask has a deadline. There is no version of this that waits
    /// forever, because an ask that is never answered would otherwise leave a
    /// reply address behind for as long as the system runs.
    public CompletableFuture<Message> ask(Address to, Message message, Duration deadline) {
        var reply = Address.of("actors.ask", java.lang.Long.toString(asked.incrementAndGet()));
        var answer = new CompletableFuture<Message>();
        asks.put(reply, answer);
        var timeout = timers.schedule(() -> {
            if (asks.remove(reply) != null) {
                answer.completeExceptionally(new java.util.concurrent.TimeoutException(
                        "no answer from " + to + " within " + deadline));
            }
        }, deadline.toMillis(), TimeUnit.MILLISECONDS);
        answer.whenComplete((ignored, failure) -> {
            asks.remove(reply);
            timeout.cancel(false);
        });
        deliver(Delivery.of(to, message).replyTo(reply).deadline(Instant.now().plus(deadline)));
        return answer;
    }

    public CompletableFuture<Message> ask(Address to, Message message) {
        return ask(to, message, PATIENCE);
    }

    /// Routes one delivery.
    ///
    /// The order of the tests is the design. A pending ask is checked first,
    /// because a reply address is not an actor and never needs a definition. A
    /// foreign address goes to the transport. Everything else is local, and a
    /// local address with no definition fails at once rather than blocking,
    /// because a misspelled type is a mistake and not congestion.
    void deliver(Delivery delivery) {
        var to = delivery.to();
        var waiting = asks.get(to.here());
        if (waiting != null) {
            waiting.complete(delivery.command());
            return;
        }
        if (to.foreign(name)) {
            remote(delivery);
            return;
        }
        var here = to.here();
        if (!definitions.containsKey(here.type())) {
            refuse(delivery, Undeliverable.Cause.unknown);
            return;
        }
        if (registry.quarantined(here)) {
            refuse(delivery, Undeliverable.Cause.quarantined);
            return;
        }
        try {
            var admission = summon(here).offer(delivery.to(here));
            switch (admission) {
                case accepted -> {}
                case busy -> refuse(delivery, Undeliverable.Cause.busy);
                case expired -> dropped.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            refuse(delivery, Undeliverable.Cause.busy);
        }
    }

    /// Hands a delivery to the transport.
    ///
    /// The reply address is stamped with this system's name on the way out. It
    /// was local while it stayed here, and the system on the other side has to
    /// be able to route an answer back, so the qualification happens at exactly
    /// the boundary that makes it necessary.
    private void remote(Delivery delivery) {
        if (transport == null) {
            refuse(delivery, Undeliverable.Cause.unreachable);
            return;
        }
        var replyTo = delivery.replyTo() == null ? null : delivery.replyTo().in(name);
        try {
            transport.deliver(delivery.to(), delivery.command(), replyTo);
        } catch (Exception e) {
            refuse(delivery, Undeliverable.Cause.unreachable);
        }
    }

    /// Accepts a message that a [Transport] carried in from another system.
    ///
    /// The reply address arrives already qualified with the sending system's
    /// name, so an `actor.reply` from an actor here routes straight back out
    /// through the transport with no special case.
    public void receive(Address to, Message message, Address replyTo) {
        var delivery = Delivery.of(to.here(), message);
        deliver(replyTo == null ? delivery : delivery.from(replyTo).replyTo(replyTo));
    }

    /// Tells the sender that a message did not arrive.
    ///
    /// A notice that cannot itself be delivered is counted and dropped. A
    /// notice about a notice is the loop this rule exists to prevent.
    private void refuse(Delivery delivery, Undeliverable.Cause cause) {
        trace(delivery.to(), Trace.Kind.undeliverable, Json.Object.of()
                .with("cause", cause.name())
                .with("type", delivery.command().type()));
        var notice = new Undeliverable(delivery.to(), cause, delivery.command()).notice();
        var waiting = delivery.replyTo() == null ? null : asks.get(delivery.replyTo().here());
        if (waiting != null) {
            waiting.complete(notice);
            return;
        }
        if (delivery.from() == null || delivery.command().type().equals(Undeliverable.TYPE)) {
            dropped.incrementAndGet();
            return;
        }
        var back = Delivery.of(delivery.from(), notice);
        var target = back.to().here();
        if (!definitions.containsKey(target.type()) || registry.quarantined(target)) {
            dropped.incrementAndGet();
            return;
        }
        try {
            if (summon(target).offer(back) != Mailbox.Admission.accepted) dropped.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dropped.incrementAndGet();
        }
    }

    // ---- loading ---------------------------------------------------------

    /// Loads an actor if it is not loaded, and answers with it.
    ///
    /// Building the instance and opening the log happen here, on the caller's
    /// thread. Replaying the log does not: that runs on the actor's own thread,
    /// so a sender is not made to wait for a history it did not ask about.
    ///
    /// A durable actor is claimed before anything is built. The claim fails by
    /// throwing, and throwing out of the mapping function leaves nothing in the
    /// loaded map, so a system that cannot own an actor does not half-load it.
    /// An actor that keeps no log is not claimed, because there is no shared
    /// history for a second owner to corrupt.
    private Actor summon(Address address) {
        passivating();
        return loaded.compute(address, (at, existing) -> {
            if (existing != null && !existing.finished()) return existing;
            var definition = definitions.get(at.type());
            var spawn = spawnFor(at);
            var log = spawn.keepsLog() ? claim(at, spawn) : Log.none();
            var actor = new Actor(this, at, definition, log, spawn);
            install(actor);
            actor.start();
            return actor;
        });
    }

    /// Takes the claim on one actor and opens its log.
    ///
    /// The order matters. Claiming first means a second owner is refused before
    /// this one has opened anything, and refreshing after the claim is what
    /// corrects a log this process cached while another process was appending
    /// to it.
    private Log claim(Address address, Spawn spawn) {
        var claim = ownership.claim(address);
        try {
            var log = logs.open(address, spawn.durability());
            log.refresh();
            claims.put(address, claim);
            return log;
        } catch (RuntimeException opening) {
            claim.release();
            throw opening;
        }
    }

    /// Gives up the claim on one actor.
    ///
    /// The claim's life is the actor's life and not the log's. [Logs] keeps a
    /// log open after its actor has gone so that an inspector can still read it,
    /// and holding the claim for that long would stop any other process from
    /// ever taking over an actor this one has finished with.
    private void release(Address address) {
        var claim = claims.remove(address.here());
        if (claim != null) claim.release();
    }

    /// Starts the passivation sweep the first time an actor is summoned.
    ///
    /// It starts here rather than in the constructor because a system is
    /// configured after it is built, and [#sweeping(Duration)] would otherwise
    /// come too late to be read. A system that never summons anything never
    /// starts a sweeper.
    private void passivating() {
        if (closed || !sweeping.compareAndSet(false, true)) return;
        var period = Math.max(1, sweep.toMillis());
        timers.scheduleWithFixedDelay(this::passivate, period, period, TimeUnit.MILLISECONDS);
    }

    /// Evicts the actors that have gone quiet.
    ///
    /// An actor is left alone while it is busy: mail waiting or a step in
    /// flight means a sender has already been told its message was accepted,
    /// and evicting would throw that message away. A settled actor goes as soon
    /// as it is idle at all, without waiting out its timeout, because its
    /// definition has said there is nothing more coming.
    ///
    /// Nothing here can throw into the timer thread. A sweep that failed once
    /// and stopped would turn a passivating system into a leaking one, and the
    /// failure would be invisible.
    private void passivate() {
        if (closed) return;
        var now = System.currentTimeMillis();
        for (var actor : loaded.values()) {
            try {
                if (stale(actor, now)) actor.stop();
            } catch (RuntimeException e) {
                // one actor that cannot be examined must not stop the sweep
            }
        }
    }

    private boolean stale(Actor actor, long now) {
        var spawn = actor.spawn();
        if (actor.finished() || actor.busy()) return false;
        if (actor.settled()) return true;
        if (!spawn.passivates()) return false;
        return now - actor.lastAt() >= spawn.idle().toMillis();
    }

    private Spawn spawnFor(Address address) {
        var override = overrides.get(address.here());
        if (override != null) return override;
        return defaults.getOrDefault(address.type(), Spawn.durable());
    }

    /// Puts the shared handlers and the `actor.*` handlers on one actor's
    /// application.
    ///
    /// The shared handlers go on first so that an actor's own definition can
    /// replace one for a test, and the `actor.*` handlers go on afterwards so
    /// that nothing can replace the routing.
    private void install(Actor actor) {
        var application = actor.application();
        shared.forEach(application::effect);
        application.effect(TELL, (effect, emit) ->
                deliver(Delivery.of(Address.from(effect.get("to")), carried(effect)).from(actor.address())));
        application.effect(ASK, (effect, emit) ->
                deliver(Delivery.of(Address.from(effect.get("to")), carried(effect))
                        .from(actor.address())
                        .replyTo(actor.address())));
        application.effect(REPLY, (effect, emit) -> reply(actor, carried(effect)));
        application.effect(SPAWN, (effect, emit) -> {
            var address = Address.from(effect.get("address"));
            if (effect.get("durable") instanceof Json.Bool(var durable)) {
                spawn(address, durable ? Spawn.durable() : Spawn.ephemeral());
            }
            summon(address.here());
        });
        application.effect(EVICT, (effect, emit) ->
                evict(effect.get("address") == null ? actor.address() : Address.from(effect.get("address"))));
        application.effect(SCHEDULE, (effect, emit) -> schedule(actor.address(), effect));
    }

    /// Answers whoever sent the message being handled.
    ///
    /// The address comes from the delivery in flight rather than from the
    /// effect, so a reply address never reaches an update function and never
    /// reaches a log. A reply with nobody waiting is dropped, because a
    /// notification has no one to answer.
    private void reply(Actor actor, Message message) {
        var delivery = actor.current();
        if (delivery == null || delivery.replyTo() == null) {
            dropped.incrementAndGet();
            return;
        }
        deliver(Delivery.of(delivery.replyTo(), message).from(actor.address()));
    }

    /// Sends a message to an actor later.
    ///
    /// A timer is an effect, so it does not survive replay. An actor that needs
    /// a deadline to survive keeps the deadline in its state, computed from
    /// [Delivery#at()], and arms the timer again from `actors.resumed`. The
    /// message a timer delivers should carry the deadline it was armed for, so
    /// that a duplicate firing after a restart is recognised and ignored.
    private void schedule(Address self, Effect effect) {
        var message = carried(effect);
        var to = effect.get("to") == null ? self : Address.from(effect.get("to"));
        var after = effect.get("after") instanceof Json.Num(var millis) ? (long) millis : 0L;
        timers.schedule(() -> deliver(Delivery.of(to, message).from(self)), after, TimeUnit.MILLISECONDS);
    }

    private static Message carried(Effect effect) {
        if (effect.get("message") instanceof Json.Object body) return new Message(body);
        return Message.of("");
    }

    // ---- lifecycle -------------------------------------------------------

    /// Asks an actor to stop once it has finished what it is doing.
    ///
    /// Eviction is not death. The log stays, and the next message summons the
    /// actor again and replays it. Evicting a durable actor is how a definition
    /// change takes effect, and evicting an undurable one throws its state away.
    public void evict(Address address) {
        var actor = loaded.get(address.here());
        if (actor != null) actor.stop();
    }

    /// Clears a quarantine so that the next message summons the actor again.
    public void revive(Address address) {
        registry.revive(address);
    }

    /// Called by an actor whose thread died. The actor is dropped from the
    /// loaded map, its waiting mail is returned to its senders, and its health
    /// decides whether the next message may summon it again.
    void died(Actor actor, Throwable death) {
        var reason = death.getMessage() == null ? death.toString() : death.getMessage();
        var spawn = actor.spawn();
        registry.died(actor.address(), reason, spawn.restarts(), spawn.window());
        var health = registry.health(actor.address());
        if (health.quarantined()) {
            trace(actor.address(), Trace.Kind.quarantined, Json.Object.of()
                    .with("reason", reason)
                    .with("restarts", health.restarts()));
        }
        for (var waiting : actor.abandonedMail()) refuse(waiting, Undeliverable.Cause.died);
    }

    void unloaded(Actor actor) {
        loaded.remove(actor.address(), actor);
        release(actor.address());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        loaded.values().forEach(Actor::stop);
        var deadline = System.currentTimeMillis() + 2_000;
        while (!loaded.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        timers.shutdownNow();
        if (transport != null) {
            try {
                transport.close();
            } catch (Exception e) {
                // a transport that will not close cannot stop the system from closing
            }
        }
        // Every actor that finished released its own claim. This covers the
        // ones that did not finish inside the deadline above, because a claim
        // left behind would keep another process out of an actor nobody is
        // running.
        ownership.close();
        claims.clear();
        traces.close();
        logs.close();
    }

    // ---- inspection ------------------------------------------------------

    /// Every actor the runtime knows about: the ones loaded now, and the ones
    /// with a log on disk.
    public Stream<Registry.Entry> known() {
        var seen = new java.util.LinkedHashMap<Address, Registry.Entry>();
        loaded.forEach((address, actor) -> seen.put(address, new Registry.Entry(
                address, actor.spawn().keepsLog(), true, actor.commands(), actor.depth(),
                registry.lastAt(address), actor.settled(), registry.health(address))));
        logs.catalogue().forEach(address -> seen.computeIfAbsent(address, at -> new Registry.Entry(
                at, true, false, 0, 0, registry.lastAt(at), false, registry.health(at))));
        return seen.values().stream();
    }

    /// The state of one actor, as its definition chooses to show it.
    public Json inspect(Address address) {
        var actor = loaded.get(address.here());
        if (actor != null) return actor.inspect();
        return inspectAt(address, Long.MAX_VALUE);
    }

    /// The state this actor had after `seq` commands.
    ///
    /// This is replay with a stopping point, and it never touches the running
    /// actor. A shadow instance is built from the definition, advanced through
    /// the log up to that sequence number, read, and thrown away. It cannot
    /// summon anything, so it never fires `actors.resumed` and never re-arms a
    /// timer, and because no effect runs it cannot reach the outside world.
    ///
    /// A scrubber over an actor's history is this method in a loop.
    public Json inspectAt(Address address, long seq) {
        var here = address.here();
        var definition = definitions.get(here.type());
        if (definition == null) throw new IllegalArgumentException("no definition for " + here.type());
        return shadow(definition, here, seq);
    }

    /// Builds a throwaway instance and advances it through the log.
    ///
    /// An actor that keeps no log has no history to read, and asking the store
    /// for one would create an empty database as a side effect of a read. So
    /// the store is only touched for an actor that keeps a log, and an
    /// undurable actor that is not loaded inspects as its initial state, which
    /// is exactly what it would be if it were summoned.
    private <S> Json shadow(Definition<S> definition, Address address, long seq) {
        var application = definition.instantiate(address);
        if (!spawnFor(address).keepsLog()) return definition.inspect(application.state());
        try (var entries = logs.open(address).replay(0, seq)) {
            entries.forEach(entry -> application.advance(entry.command()));
        }
        return definition.inspect(application.state());
    }

    /// The commands one actor recorded, in order.
    public Stream<Message> history(Address address, long from, int limit) {
        var log = logs.open(address.here());
        return log.replay(from, limit).map(Log.Entry::command);
    }

    /// The state of many actors, read without summoning any of them.
    ///
    /// This is expensive on purpose and it should look expensive at the call
    /// site. Every actor it touches is replayed from its log, so a scan over
    /// ten thousand actors reads ten thousand histories. It is the right tool
    /// for a backfill or a reconciliation, run deliberately, and the wrong tool
    /// for answering a question during a request.
    public Stream<Scanned> scan(Predicate<Registry.Entry> wanted) {
        return known().filter(wanted).map(entry -> new Scanned(entry.address(), inspect(entry.address())));
    }

    /// One actor's state, read by a scan.
    public record Scanned(Address address, Json state) {}

    /// A fan-out over many actors of one type.
    public Fleet fleet(String type) {
        return new Fleet(this, type);
    }

    /// The actor at this address, as a [java.util.concurrent.Flow.Subscriber],
    /// so that a publisher can feed it with the mailbox bound as its demand.
    public java.util.concurrent.Flow.Subscriber<Message> subscriber(Address address) {
        return summon(address.here());
    }

    List<Address> loadedAddresses() {
        return new ArrayList<>(loaded.keySet());
    }
}
