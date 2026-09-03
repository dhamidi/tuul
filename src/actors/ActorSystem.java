package actors;

import application.Application;
import application.Effect;
import application.Message;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import json.Json;
import reload.StatePolicy;

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
/// A definition never holds a reference to a system. It creates an
/// [ActorEffect], and the system runs that effect directly:
///
///   - `actor.tell` sends a message to an address.
///   - `actor.reply` answers whoever is waiting on the message being handled.
///     It carries no address, because the system already knows.
///   - `actor.spawn`, `actor.evict` and `actor.schedule` do what they say.
///
/// External effect handlers run on system-owned virtual threads. An address in
/// another system uses the same actor effect. [Transport] carries that effect's
/// delivery.
///
/// ## Ownership, passivation and watching
///
/// A durable actor is claimed before it is summoned and the claim is released
/// when its thread stops, so two processes cannot run one actor into one log.
/// An actor idle for [Spawn#idle()] is evicted and summoned again by the next
/// message. [#traces()] publishes what the system does as it happens, and
/// [Flight] turns that into JDK Flight Recorder events.
///
/// ## Reload integration
///
/// [#reload(Map, Map, Map, Map)] replaces actor definitions and effect handlers
/// at one awaited type boundary. The method gates new deliveries, drains loaded
/// actors, replays durable logs without effects, and then admits the gated mail.
/// [#generation()] identifies the active handoff for traces and operations.
/// The reload coordinator owns class loaders and combines this boundary with
/// web and application activation.
public final class ActorSystem implements AutoCloseable {

    /// Envelope fields the message-sending effects are routed by.
    ///
    /// They are envelope fields, and they are named for the family that owns
    /// them, because the body of one of these effects is the message's own
    /// payload. A message about a delivery van may say `to`; it must not be
    /// mistaken for where the message goes.
    static final String TO = "actor.to";

    static final String AFTER = "actor.after";

    /// Effect types the system handles for every actor.
    static final String TELL = "actor.tell";
    static final String REPLY = "actor.reply";
    static final String SPAWN = "actor.spawn";
    static final String EVICT = "actor.evict";
    static final String SCHEDULE = "actor.schedule";

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    /// Admission is temporarily detached from an actor mailbox while its
    /// definition is replaced. The queue uses the candidate mailbox capacity
    /// and returns busy when that capacity is full.
    private static final class Gate {
        private final Object lock = new Object();
        private final List<Delivery> deliveries = new ArrayList<>();
        private final List<MessageType> messageTypes;
        private final int capacity;
        private final boolean known;

        private Gate(Definition<?> definition, int capacity) {
            this.capacity = Math.max(1, capacity);
            this.known = definition != null;
            this.messageTypes = definition == null ? List.of() : declared(definition);
        }

        private DeliveryStatus offer(Delivery delivery) {
            if (delivery.expired(Instant.now())) return DeliveryStatus.expired;
            if (!known) return DeliveryStatus.unknown;
            var messageType = messageTypes.stream()
                    .filter(type -> type.type().equals(delivery.message().type())).findFirst().orElse(null);
            if (messageType == null) return DeliveryStatus.unsupported;
            if (!messageType.validate(delivery.message()).valid()) return DeliveryStatus.invalid;
            synchronized (lock) {
                if (deliveries.size() >= capacity) return DeliveryStatus.busy;
                deliveries.add(delivery);
                return DeliveryStatus.accepted;
            }
        }

        private static List<MessageType> declared(Definition<?> definition) {
            try {
                return definition.instantiate(Address.of(definition.type(), "reload")).messageTypes();
            } catch (RuntimeException failure) {
                return List.of();
            }
        }

        private List<Delivery> drain() {
            synchronized (lock) {
                var result = List.copyOf(deliveries);
                deliveries.clear();
                return result;
            }
        }
    }

    private final String name;
    private final Object lifecycle = new Object();
    private final Map<String, Definition<?>> definitions = new ConcurrentHashMap<>();
    private final Map<String, Spawn> defaults = new ConcurrentHashMap<>();
    private final Map<Address, Spawn> overrides = new ConcurrentHashMap<>();
    private final Map<Address, Actor> loaded = new ConcurrentHashMap<>();
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();
    private final Map<Address, Ownership.Claim> claims = new ConcurrentHashMap<>();
    private final Map<Address, CompletableFuture<Message>> asks = new ConcurrentHashMap<>();
    private final Map<String, Effect.Handler> shared = new ConcurrentHashMap<>();
    private final Registry registry = new Registry();
    private final Traces traces = new Traces();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong asked = new AtomicLong();
    private final ScheduledExecutorService timers =
            Executors.newScheduledThreadPool(0, Thread.ofVirtual().factory());
    private final ExecutorService effects = Executors.newVirtualThreadPerTaskExecutor();
    private Logs logs = Logs.none();
    private Ownership ownership = Ownership.shared();
    private Transport transport;
    private Duration sweep = Duration.ofSeconds(30);
    private volatile boolean tracingMessages;
    private final java.util.concurrent.atomic.AtomicBoolean sweeping =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private volatile long generation;

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

    /// The active actor generation number. It starts at zero and increments
    /// after each successful definition and handler handoff.
    public long generation() {
        return generation;
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
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(spawn, "spawn");
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("the actor system " + name + " is closed");
            definitions.put(definition.type(), definition);
            defaults.put(definition.type(), spawn);
        }
        return this;
    }

    /// Overrides the spawn options for one address. This is how one instance of
    /// a durable type runs without a log, or the other way round.
    public ActorSystem spawn(Address address, Spawn spawn) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(spawn, "spawn");
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("the actor system " + name + " is closed");
            overrides.put(address.here(), spawn);
        }
        return this;
    }

    /// Registers an effect handler that every actor in this system can use.
    ///
    /// Handlers belong here rather than in a definition because a handler owns
    /// a connection, a file or a socket, and those must survive an actor being
    /// evicted and summoned again. It also keeps definitions pure, which is
    /// what makes replay safe.
    public ActorSystem effect(String type, Effect.Handler handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("the actor system " + name + " is closed");
            shared.put(type, handler);
        }
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
        var withGeneration = detail instanceof Json.Object object
                ? object.with("generation", generation) : detail;
        traces.publish(Trace.of(address, kind, withGeneration));
    }

    /// How many `handle-delivery-error` notices had nowhere to go. A notice that
    /// cannot be delivered is dropped rather than answered, because two full
    /// mailboxes pointed at each other would otherwise answer each other
    /// forever.
    public long dropped() {
        return dropped.get();
    }

    // ---- sending ---------------------------------------------------------

    /// Attempts delivery without waiting for message processing. For a local
    /// address, `accepted` means that the destination mailbox admitted the
    /// message. It does not mean that the destination handled or journaled it.
    /// A foreign call returns after the transport accepts or refuses delivery.
    public DeliveryStatus tell(Address to, Message message) {
        return deliver(Delivery.of(to, message));
    }

    /// Sends a message from one actor to another, so that a failure can be
    /// reported back to the sender.
    public DeliveryStatus tell(Address to, Message message, Address from) {
        return deliver(Delivery.of(to, message).from(from));
    }

    /// Sends a message and waits for one answer.
    ///
    /// Use a declared query to read through the mailbox without writing to the
    /// journal. The query follows messages that the mailbox accepted earlier.
    /// Its effects can reply, but [Behavior] prevents its handler from changing
    /// state. A command sent with this method is still a command and is still
    /// journaled.
    ///
    /// Use [#inspect(Address)] for the definition's operational JSON view. An
    /// inspection does not run a user handler or an effect.
    ///
    /// The reply address contains this system's name and a sequence number. A
    /// pending table maps that address to one future. A transport routes the
    /// qualified address back to this system.
    ///
    /// Every ask has a deadline. There is no version of this that waits
    /// forever, because an ask that is never answered would otherwise leave a
    /// reply address behind for as long as the system runs. A closed system
    /// returns a future that contains an `IllegalStateException`.
    public CompletableFuture<Message> ask(Address to, Message message, Duration deadline) {
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("an ask deadline must be positive: " + deadline);
        }
        if (closed) return CompletableFuture.failedFuture(
                new IllegalStateException("the actor system " + name + " is closed"));
        var reply = Address.of("actors.ask", name + "/" + asked.incrementAndGet());
        var answer = new CompletableFuture<Message>();
        asks.put(reply, answer);
        java.util.concurrent.ScheduledFuture<?> timeout;
        try {
            timeout = timers.schedule(() -> {
                if (asks.remove(reply) != null) {
                    answer.completeExceptionally(new java.util.concurrent.TimeoutException(
                            "no answer from " + to + " within " + deadline));
                }
            }, deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException closedDuringAsk) {
            asks.remove(reply);
            answer.completeExceptionally(new IllegalStateException(
                    "the actor system " + name + " is closed", closedDuringAsk));
            return answer;
        }
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

    /// Loads an actor and runs its resume turn without sending a user message.
    ///
    /// This is useful for actors whose startup work is represented by effects,
    /// such as settings initializers. It is idempotent while the actor is
    /// loaded and returns once the actor has accepted the summon request.
    public void summon(Address address) {
        load(address.here());
    }

    /// Routes one delivery.
    ///
    /// The order of these tests is part of the routing contract. A foreign
    /// address goes to the transport before local ask correlation runs. A local
    /// reply address then completes one pending ask. Every other local address
    /// enters an actor mailbox.
    DeliveryStatus deliver(Delivery delivery) {
        if (closed) return DeliveryStatus.closed;
        var to = delivery.to();
        if (to.foreign(name)) {
            return remote(delivery);
        }
        var here = to.here();
        var waiting = asks.remove(here);
        if (waiting != null) {
            waiting.complete(delivery.message());
            return DeliveryStatus.accepted;
        }
        synchronized (lifecycle) {
            var gate = gates.get(here.type());
            if (gate != null) {
                var admission = gate.offer(delivery);
                if (admission == DeliveryStatus.accepted) return admission;
                if (admission == DeliveryStatus.expired) {
                    dropped.incrementAndGet();
                    return admission;
                }
                var cause = switch (admission) {
                    case unknown -> Undeliverable.Cause.unknown;
                    case unsupported -> Undeliverable.Cause.unsupported;
                    case invalid -> Undeliverable.Cause.invalid;
                    case busy -> Undeliverable.Cause.busy;
                    default -> Undeliverable.Cause.unreachable;
                };
                refuse(delivery, cause);
                return admission;
            }
            if (!definitions.containsKey(here.type())) {
                refuse(delivery, Undeliverable.Cause.unknown);
                return DeliveryStatus.unknown;
            }
            if (registry.quarantined(here)) {
                refuse(delivery, Undeliverable.Cause.quarantined);
                return DeliveryStatus.quarantined;
            }
            Actor target;
            try {
                target = load(here);
            } catch (IllegalStateException stopped) {
                if (closed) return DeliveryStatus.closed;
                throw stopped;
            }
            var messageType = target.messageType(delivery.message().type());
            if (messageType == null) {
                refuse(delivery, Undeliverable.Cause.unsupported);
                return DeliveryStatus.unsupported;
            }
            if (!messageType.validate(delivery.message()).valid()) {
                refuse(delivery, Undeliverable.Cause.invalid);
                return DeliveryStatus.invalid;
            }
            if (delivery.from() != null && here.equals(delivery.from().here()) && target.ownsCurrentThread()) {
                target.self(delivery.to(here));
                return DeliveryStatus.accepted;
            }
            var admission = target.offer(delivery.to(here));
            return switch (admission) {
                case accepted -> DeliveryStatus.accepted;
                case busy -> {
                    refuse(delivery, Undeliverable.Cause.busy);
                    yield DeliveryStatus.busy;
                }
                case expired -> {
                    dropped.incrementAndGet();
                    yield DeliveryStatus.expired;
                }
            };
        }
    }

    /// Hands a delivery to the transport.
    ///
    /// The reply address is stamped with this system's name on the way out. It
    /// was local while it stayed here, and the system on the other side has to
    /// be able to route an answer back, so the qualification happens at exactly
    /// the boundary that makes it necessary.
    private DeliveryStatus remote(Delivery delivery) {
        if (transport == null) {
            refuse(delivery, Undeliverable.Cause.unreachable);
            return DeliveryStatus.unreachable;
        }
        var replyTo = delivery.replyTo() == null ? null : delivery.replyTo().in(name);
        try {
            transport.deliver(delivery.to(), delivery.message(), replyTo);
            return DeliveryStatus.accepted;
        } catch (Exception e) {
            refuse(delivery, Undeliverable.Cause.unreachable);
            return DeliveryStatus.unreachable;
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
                .with("type", delivery.message().type()));
        var notice = new Undeliverable(delivery.to(), cause, delivery.message()).notice();
        if (delivery.replyTo() != null) {
            if (delivery.replyTo().foreign(name)) {
                deliver(Delivery.of(delivery.replyTo(), notice));
                return;
            }
            var waiting = asks.remove(delivery.replyTo().here());
            if (waiting != null) {
                waiting.complete(notice);
                return;
            }
        }
        if (delivery.from() == null || delivery.message().type().equals(Undeliverable.TYPE)) {
            dropped.incrementAndGet();
            return;
        }
        var back = Delivery.of(delivery.from(), notice);
        var target = back.to().here();
        if (!definitions.containsKey(target.type()) || registry.quarantined(target)) {
            dropped.incrementAndGet();
            return;
        }
        var sender = loaded.get(target);
        if (sender != null && !sender.finished()) {
            sender.self(back);
            return;
        }
        if (load(target).offer(back) != Mailbox.Admission.accepted) dropped.incrementAndGet();
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
    private Actor load(Address address) {
        var active = loaded.get(address);
        if (active != null && !active.finished()) return active;
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("the actor system " + name + " is closed");
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
            log.redelivers(spawn.redelivers());
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

    /// Installs the actor definitions and effect handlers for one reload
    /// generation. The operation is synchronous and its future form is useful
    /// to a coordinator that must await several systems without blocking its
    /// own control thread.
    public ReloadResult reload(Map<String, Definition<?>> nextDefinitions,
            Map<String, Spawn> nextSpawns, Map<String, Effect.Handler> nextEffects,
            Map<String, StatePolicy> policies) {
        return activate(nextDefinitions, nextSpawns, nextEffects, policies).join();
    }

    /// Reloads one complete set with the default durable replay and ephemeral
    /// refusal policies.
    public ReloadResult reload(Map<String, Definition<?>> nextDefinitions,
            Map<String, Spawn> nextSpawns, Map<String, Effect.Handler> nextEffects) {
        return reload(nextDefinitions, nextSpawns, nextEffects, Map.of());
    }

    /// Performs one type-level actor handoff. New deliveries for affected types
    /// are accepted into a gate while loaded actors finish their current turn
    /// and all mail accepted before the gate. Durable actors are then summoned
    /// with the candidate definition and replay their logs without effects.
    public CompletableFuture<ReloadResult> activate(Map<String, Definition<?>> nextDefinitions,
            Map<String, Spawn> nextSpawns, Map<String, Effect.Handler> nextEffects,
            Map<String, StatePolicy> policies) {
        var result = new CompletableFuture<ReloadResult>();
        Thread.startVirtualThread(() -> {
            try {
                result.complete(activateNow(nextDefinitions, nextSpawns, nextEffects, policies));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /// Activates one complete set with the default state policies.
    public CompletableFuture<ReloadResult> activate(Map<String, Definition<?>> nextDefinitions,
            Map<String, Spawn> nextSpawns, Map<String, Effect.Handler> nextEffects) {
        return activate(nextDefinitions, nextSpawns, nextEffects, Map.of());
    }

    /// Checks one complete proposed actor set without changing the running
    /// system. The result is an immutable list of replay, definition, policy,
    /// and loaded-state problems. An empty list means that activation may run.
    /// Durable logs are replayed with effects suppressed. The caller still
    /// needs to call [#activate(Map, Map, Map, Map)] to perform the handoff.
    public List<String> preflight(Map<String, Definition<?>> nextDefinitions,
            Map<String, Spawn> nextSpawns, Map<String, Effect.Handler> nextEffects,
            Map<String, StatePolicy> policies) {
        try {
            var definitionsCopy = Map.copyOf(nextDefinitions == null ? Map.of() : nextDefinitions);
            var spawnsCopy = Map.copyOf(nextSpawns == null ? Map.of() : nextSpawns);
            var effectsCopy = Map.copyOf(nextEffects == null ? Map.of() : nextEffects);
            var policyCopy = Map.copyOf(policies == null ? Map.of() : policies);
            synchronized (lifecycle) {
                return validateReload(definitionsCopy, spawnsCopy, effectsCopy, policyCopy);
            }
        } catch (RuntimeException failure) {
            return List.of(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    /// Checks a proposed actor set with the default state policies.
    public List<String> preflight(Map<String, Definition<?>> nextDefinitions,
            Map<String, Spawn> nextSpawns, Map<String, Effect.Handler> nextEffects) {
        return preflight(nextDefinitions, nextSpawns, nextEffects, Map.of());
    }

    private ReloadResult activateNow(Map<String, Definition<?>> nextDefinitions,
            Map<String, Spawn> nextSpawns, Map<String, Effect.Handler> nextEffects,
            Map<String, StatePolicy> policies) {
        var definitionsCopy = Map.copyOf(nextDefinitions == null ? Map.of() : nextDefinitions);
        var spawnsCopy = Map.copyOf(nextSpawns == null ? Map.of() : nextSpawns);
        var effectsCopy = Map.copyOf(nextEffects == null ? Map.of() : nextEffects);
        var policyCopy = Map.copyOf(policies == null ? Map.of() : policies);
        var problems = validateReload(definitionsCopy, spawnsCopy, effectsCopy, policyCopy);
        if (!problems.isEmpty()) return new ReloadResult(false, generation, problems);

        var affected = affectedTypes(definitionsCopy, spawnsCopy, effectsCopy);
        var queued = List.<Delivery>of();
        synchronized (lifecycle) {
            if (closed) return new ReloadResult(false, generation,
                    List.of("the actor system " + name + " is closed"));
            if (!gates.isEmpty()) return new ReloadResult(false, generation,
                    List.of("an actor handoff is still draining"));
            for (var type : affected) {
                var definition = definitionsCopy.get(type);
                var spawn = spawnsCopy.getOrDefault(type, defaults.getOrDefault(type, Spawn.durable()));
                gates.put(type, new Gate(definition, spawn.mailbox()));
            }
            loaded.values().stream().filter(actor -> affected.contains(actor.address().type()))
                    .forEach(Actor::stop);
        }

        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (true) {
                var waiting = loaded.values().stream()
                        .filter(actor -> affected.contains(actor.address().type()) && !actor.finished())
                        .toList();
                if (waiting.isEmpty()) break;
                if (System.nanoTime() >= deadline) {
                    return abortReload(affected, "actor handoff timed out");
                }
                for (var actor : waiting) {
                    try { actor.await(Math.max(1, Math.min(50,
                            (deadline - System.nanoTime()) / 1_000_000))); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return abortReload(affected, "actor handoff interrupted");
                    }
                }
            }
            synchronized (lifecycle) {
                definitions.clear();
                definitions.putAll(definitionsCopy);
                defaults.clear();
                defaults.putAll(spawnsCopy);
                shared.clear();
                shared.putAll(effectsCopy);
                generation++;
                for (var type : affected) {
                    var gate = gates.remove(type);
                    if (gate != null) queued = concat(queued, gate.drain());
                }
            }
            for (var delivery : queued) deliver(delivery);
            return new ReloadResult(true, generation, List.of());
        } catch (RuntimeException failure) {
            return abortReload(affected, failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    private ReloadResult abortReload(java.util.Set<String> affected, String problem) {
        var queued = List.<Delivery>of();
        var waiting = false;
        synchronized (lifecycle) {
            waiting = loaded.values().stream()
                    .anyMatch(actor -> affected.contains(actor.address().type()) && !actor.finished());
            if (!waiting) for (var type : affected) {
                var gate = gates.remove(type);
                if (gate != null) queued = concat(queued, gate.drain());
            }
        }
        if (waiting) {
            // Keep accepted deliveries behind the gate until every stopping
            // actor has left its mailbox. Draining now would offer them to a
            // mailbox that has already closed admission and would silently
            // turn an awaited activation failure into message loss.
            Thread.startVirtualThread(() -> awaitAbort(affected));
        } else for (var delivery : queued) deliver(delivery);
        return new ReloadResult(false, generation, List.of(problem));
    }

    private void awaitAbort(java.util.Set<String> affected) {
        while (!closed && loaded.values().stream()
                .anyMatch(actor -> affected.contains(actor.address().type()) && !actor.finished())) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
        if (closed) return;
        var queued = List.<Delivery>of();
        synchronized (lifecycle) {
            for (var type : affected) {
                var gate = gates.remove(type);
                if (gate != null) queued = concat(queued, gate.drain());
            }
        }
        for (var delivery : queued) deliver(delivery);
    }

    private List<String> validateReload(Map<String, Definition<?>> nextDefinitions,
            Map<String, Spawn> nextSpawns, Map<String, Effect.Handler> nextEffects,
            Map<String, StatePolicy> policies) {
        var problems = new ArrayList<String>();
        nextDefinitions.forEach((type, definition) -> {
            if (definition == null) problems.add("definition for " + type + " is null");
            else {
                if (!type.equals(definition.type())) problems.add("definition key does not match type " + type);
                try {
                    definition.instantiate(Address.of(type, "preflight"));
                } catch (RuntimeException failure) {
                    problems.add("cannot instantiate " + type + ": "
                            + (failure.getMessage() == null ? failure.toString() : failure.getMessage()));
                }
            }
            if (!nextSpawns.containsKey(type)) problems.add("missing spawn options for " + type);
        });
        for (var entry : logs.catalogue().toList()) {
            var definition = nextDefinitions.get(entry.type());
            if (definition == null) {
                problems.add("no definition for recorded actor type " + entry.type());
                continue;
            }
            try {
                validateReplay(definition, entry);
            } catch (RuntimeException failure) {
                problems.add("replay " + entry + ": " + (failure.getMessage() == null
                        ? failure.toString() : failure.getMessage()));
            }
        }
        for (var actor : loaded.values()) {
            if (!affectedTypes(nextDefinitions, nextSpawns, nextEffects).contains(actor.address().type())) continue;
            var policy = policies.getOrDefault(actor.address().type(),
                    actor.spawn().keepsLog() ? StatePolicy.REPLAY : StatePolicy.REFUSE);
            if (!actor.spawn().keepsLog() && policy == StatePolicy.REFUSE) {
                problems.add("loaded ephemeral actor " + actor.address() + " refuses reload");
            }
            if (policy == StatePolicy.TRANSFER) problems.add("state transfer is not supported for " + actor.address());
        }
        return List.copyOf(problems);
    }

    private <S> void validateReplay(Definition<S> definition, Address address) {
        var behavior = definition.instantiate(address);
        var application = behavior.application();
        try (var commands = logs.open(address).replay()) {
            commands.forEach(command -> {
                var declared = behavior.messageType(command.type());
                if (declared == null || !declared.validate(command).valid()) {
                    throw new IllegalArgumentException("unknown or invalid command " + command.type());
                }
                application.advance(command);
            });
        }
    }

    private java.util.Set<String> affectedTypes(Map<String, Definition<?>> nextDefinitions,
            Map<String, Spawn> nextSpawns, Map<String, Effect.Handler> nextEffects) {
        var affected = new java.util.HashSet<String>();
        definitions.forEach((type, definition) -> {
            if (!Objects.equals(definition, nextDefinitions.get(type))
                    || !Objects.equals(defaults.get(type), nextSpawns.get(type))) affected.add(type);
        });
        nextDefinitions.keySet().forEach(type -> {
            if (!Objects.equals(definitions.get(type), nextDefinitions.get(type))) affected.add(type);
        });
        if (!shared.equals(nextEffects)) loaded.values().forEach(actor -> affected.add(actor.address().type()));
        return affected;
    }

    private static List<Delivery> concat(List<Delivery> first, List<Delivery> second) {
        if (first.isEmpty()) return List.copyOf(second);
        var all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    /// Adds the external handlers to one actor application. The runtime runs
    /// `actor.*` effects directly and does not register handlers for them.
    private void install(Actor actor) {
        var application = actor.application();
        shared.forEach(application::effect);
    }

    /// Runs one actor step. Local runtime effects run inline in list order.
    /// External handlers and foreign deliveries run on system-owned virtual
    /// threads.
    List<Message> perform(Actor actor, List<Effect> requested) {
        if (requested.isEmpty()) return List.of();
        var step = new EffectStep(actor.address());
        var external = new ArrayList<PendingEffect>();
        for (var effect : requested) {
            if (external(actor, effect)) {
                var future = effects.submit(() -> perform(actor, effect, step));
                external.add(new PendingEffect(effect, future));
                continue;
            }
            if (perform(actor, effect, step)) continue;
            var future = effects.submit(() -> actor.application().perform(effect, step));
            external.add(new PendingEffect(effect, future));
        }
        await(actor, external, step);
        return step.close();
    }

    private boolean external(Actor actor, Effect effect) {
        if (effect.type().equals(TELL)) {
            try {
                return addressed(effect).foreign(name);
            } catch (RuntimeException malformed) {
                return false;
            }
        }
        if (!effect.type().equals(REPLY)) return false;
        var current = actor.current();
        return current != null && current.replyTo() != null && current.replyTo().foreign(name);
    }

    /// Returns true when the runtime handled the effect directly.
    private boolean perform(Actor actor, Effect effect, EffectStep emit) {
        try {
            switch (effect.type()) {
                case Effect.SEND -> emit.emit(effect.message());
                case TELL -> tell(actor, effect, emit);
                case REPLY -> reply(actor, effect.message());
                case SPAWN -> spawn(effect);
                case EVICT -> evict(effect.get("address") == null
                        ? actor.address() : Address.from(effect.get("address")));
                case SCHEDULE -> schedule(actor.address(), effect);
                default -> { return false; }
            }
        } catch (Exception failure) {
            var cause = failure.getCause() == null ? failure : failure.getCause();
            var reason = cause.getMessage() == null ? cause.toString() : cause.getMessage();
            emit.emit(Message.error(reason)
                    .with("exception", cause.getClass().getName())
                    .with("while", effect.type()));
        }
        return true;
    }

    private void tell(Actor actor, Effect effect, EffectStep emit) {
        var to = addressed(effect);
        if (!to.foreign(name) && to.here().equals(actor.address())) {
            emit.emit(effect.message());
            return;
        }
        deliver(Delivery.of(to, effect.message()).from(actor.address()));
    }

    private void spawn(Effect effect) {
        var address = Address.from(effect.get("address"));
        if (effect.get("durable") instanceof Json.Bool(var durable)) {
            spawn(address, durable ? Spawn.durable() : Spawn.ephemeral());
        }
        load(address.here());
    }

    private void await(Actor actor, List<PendingEffect> pending, EffectStep step) {
        var limit = System.nanoTime() + actor.spawn().effects().toNanos();
        for (var item : pending) {
            try {
                var remaining = limit - System.nanoTime();
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
                item.future().get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.TimeoutException timeout) {
                break;
            } catch (java.util.concurrent.ExecutionException impossible) {
                // Application.perform reports handler exceptions through the emitter.
            }
        }
        for (var item : pending) {
            if (item.future().isDone()) continue;
            item.future().cancel(true);
            step.emit(Message.of(Message.HANDLE_TIMEOUT)
                    .with("reason", "the effect did not finish within " + actor.spawn().effects())
                    .with("while", item.effect().type()));
            trace(actor.address(), Trace.Kind.abandoned,
                    Json.Object.of().with("while", item.effect().type()));
        }
    }

    private record PendingEffect(Effect effect, Future<?> future) {}

    /// Collects external effect results until the actor step ends.
    private final class EffectStep implements Effect.Emitter {
        private final Address actor;
        private final List<Message> emitted = new ArrayList<>();
        private boolean open = true;

        private EffectStep(Address actor) {
            this.actor = actor;
        }

        @Override
        public synchronized void emit(Message message) {
            if (open) {
                emitted.add(message);
                return;
            }
            trace(actor, Trace.Kind.fenced,
                    Json.Object.of().with("type", message.type()));
        }

        synchronized List<Message> close() {
            open = false;
            return List.copyOf(emitted);
        }
    }

    /// Where a message-sending effect is addressed.
    private static Address addressed(Effect effect) {
        return Address.from(effect.envelope().get(TO));
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
    /// [Delivery#at()], and arms the timer again from `actors.resume`. The
    /// message a timer delivers should carry the deadline it was armed for, so
    /// that a duplicate firing after a restart is recognised and ignored.
    private void schedule(Address self, Effect effect) {
        var message = effect.message();
        var to = effect.envelope().get(TO) == null ? self : Address.from(effect.envelope().get(TO));
        var after = (long) effect.envelope().number(AFTER, 0);
        timers.schedule(() -> deliver(Delivery.of(to, message).from(self)), after, TimeUnit.MILLISECONDS);
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
        registry.handled(actor.address(), actor.lastAt());
        loaded.remove(actor.address(), actor);
        release(actor.address());
    }

    @Override
    public void close() {
        List<Actor> actors;
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            actors = List.copyOf(loaded.values());
        }
        actors.forEach(Actor::stop);
        var deadline = System.currentTimeMillis() + 2_000;
        for (var actor : actors) {
            var remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                actor.await(remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        actors.stream().filter(actor -> !actor.finished()).forEach(Actor::interrupt);
        var stopped = new IllegalStateException("the actor system " + name + " is closed");
        asks.values().forEach(answer -> answer.completeExceptionally(stopped));
        asks.clear();
        timers.shutdownNow();
        effects.shutdownNow();
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
                actor.lastAt(), actor.settled(), registry.health(address))));
        logs.catalogue().forEach(address -> seen.computeIfAbsent(address, at -> new Registry.Entry(
                at, true, false, 0, 0, registry.lastAt(at), false, registry.health(at))));
        return seen.values().stream();
    }

    /// The state of one actor, as its definition chooses to show it.
    ///
    /// A loaded actor performs the inspection as a serial mailbox turn. The
    /// inspection follows all inbound messages that this system accepted first.
    /// An unloaded actor uses replay and does not run effects.
    public Json inspect(Address address) {
        var actor = loaded.get(address.here());
        if (actor != null) {
            try {
                var state = actor.inspect();
                if (state != null) return state;
            } catch (RuntimeException failure) {
                if (!actor.finished()) throw failure;
            }
            try {
                actor.await(PATIENCE.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while inspecting " + address, interrupted);
            }
            if (!actor.finished()) {
                throw new IllegalStateException("the actor did not stop while inspecting " + address);
            }
        }
        return inspectAt(address, Long.MAX_VALUE);
    }

    /// The state this actor had after `seq` commands.
    ///
    /// This is replay with a stopping point, and it never touches the running
    /// actor. A shadow instance is built from the definition, advanced through
    /// the log up to that sequence number, read, and thrown away. It cannot
    /// summon anything, so it never fires `actors.resume` and never re-arms a
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
    /// An actor with no log has no history to read, and asking the store for
    /// one would create an empty database as a side effect of a read. So the
    /// store is only opened for an actor that keeps a log **and already has
    /// one**, and every other actor inspects as its initial state, which is
    /// exactly what it would be if it were summoned.
    ///
    /// Both halves of that test matter. An actor id comes from a URL, so
    /// `GET /posts/<anything>` would otherwise write three files per name
    /// anybody typed, and nothing bounds how many names there are.
    private <S> Json shadow(Definition<S> definition, Address address, long seq) {
        var behavior = definition.instantiate(address);
        var application = behavior.application();
        if (!spawnFor(address).keepsLog() || !logs.exists(address)) return definition.inspect(application.state());
        try (var commands = logs.open(address).replay(0, seq)) {
            commands.forEach(application::advance);
        }
        return definition.inspect(application.state());
    }

    /// The commands one actor recorded, in order.
    ///
    /// An actor with no log recorded nothing, and this says so without making
    /// one — for the same reason [#shadow] does not.
    public Stream<Message> history(Address address, long from, int limit) {
        var here = address.here();
        if (!logs.exists(here)) return Stream.of();
        return logs.open(here).replay(from, limit);
    }

    /// The imperative command and query types that one actor accepts.
    ///
    /// A loaded actor returns the declarations of its live behavior. An
    /// unloaded actor builds a fresh behavior from the registered definition.
    /// This method does not summon the actor, open its log, send
    /// `actors.resume`, or run an effect. The returned list is a snapshot in
    /// declaration order.
    ///
    /// An unknown actor type throws. A foreign address also throws because a
    /// transport carries deliveries and does not expose remote definitions.
    public List<MessageType> messageTypes(Address address) {
        if (address.foreign(name)) {
            throw new IllegalArgumentException("cannot inspect message types in another actor system: " + address);
        }
        var here = address.here();
        var actor = loaded.get(here);
        if (actor != null && !actor.finished()) return actor.messageTypes();
        var definition = definitions.get(here.type());
        if (definition == null) throw new IllegalArgumentException("no definition for " + here.type());
        return types(definition, here);
    }

    private static <S> List<MessageType> types(Definition<S> definition, Address address) {
        return definition.instantiate(address).messageTypes();
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

    void activate(Address address) {
        load(address.here());
    }

    List<Address> loadedAddresses() {
        return new ArrayList<>(loaded.keySet());
    }
}
