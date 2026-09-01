/// Durable virtual actors, built out of [application.Application].
///
/// An actor is an application with a name, a mailbox, and a log. You address it
/// by type and id, and the system loads it when a message arrives. Loading
/// means replaying its recorded commands through the definition that is
/// registered right now, which is why changing an update function and evicting
/// the actor is the whole upgrade procedure. There is nothing stored to
/// migrate, because the state is a fold and not a document.
///
/// ## A first actor
///
/// A [Definition] says what one type of actor is. It declares imperative
/// commands and queries with their update functions.
///
/// ```
/// record Counter(long total) {}
///
/// final class Counting implements Definition<Counter> {
///     static final MessageType ADD = MessageType.command("add");
///     static final MessageType GET_TOTAL = MessageType.query("get-total");
///
///     public String type() { return "counter"; }
///
///     public Behavior<Counter> instantiate(Address self) {
///         return Behavior.of(new Counter(0))
///                 .on(ADD, (state, message) -> Step.of(new Counter(state.total() + 1)))
///                 .on(GET_TOTAL, (state, message) -> Step.of(state,
///                         ActorEffect.reply(
///                                 Message.of("total").with("value", Json.of(state.total())))));
///     }
///
///     public Json inspect(Counter state) {
///         return Json.Object.of().with("total", state.total());
///     }
/// }
/// ```
///
/// Register it with a system and send it something. Nothing is created and
/// nothing is started: every address always exists, and an address nobody has
/// written to is an actor with an empty log.
///
/// ```
/// try (var system = ActorSystem.named("shop").rooted(Path.of("logs")).define(new Counting())) {
///     var counter = Address.of("counter", "42");
///     system.tell(counter, Counting.ADD.message());
///     var answer = system.ask(counter, Counting.GET_TOTAL.message(), Duration.ofSeconds(2)).get();
/// }
/// ```
///
/// `actor.reply` carries no address. The system knows who is waiting on the
/// message being handled. A reply address never reaches an update function or
/// a journal.
///
/// `add` is a command, so the actor writes it before handling it. `get-total`
/// is a query, so the actor does not write it. A query handler can emit a reply,
/// but [Behavior] prevents it from changing state.
///
/// ## The law
///
/// **Effects never run during replay.** Everything an effect learns comes back
/// as an imperative command. The actor journals that command, so the journal
/// already holds what the outside world said. Replaying it rebuilds the state
/// without sending yesterday's email a second time.
///
/// That puts one obligation on a definition, and it is the only rule that
/// really matters here: **an update function must be a pure function of its
/// state and its message.** No clock, no random number, no file, no socket. The
/// only "now" available is the timestamp of the message being handled, which is
/// recorded and therefore replays unchanged. An update reads it as
/// `message.at()`: the actor stamps it before the update runs, live and on
/// replay alike, so the same message always produces the same state.
///
/// ## Reaching anything else
///
/// A definition never holds a reference to a system. It creates an
/// [ActorEffect], and the system runs it:
///
///   - `actor.tell` sends a message to an address.
///   - `actor.reply` answers whoever is waiting.
///   - `actor.spawn`, `actor.evict` and `actor.schedule` do what they say.
///
/// Handlers for anything else — a database, an HTTP client, a mailer — are
/// registered once on the system with [ActorSystem#effect(String,
/// application.Effect.Handler)]. They belong there because they own connections
/// that must outlive an actor being evicted, and keeping them out of a
/// definition is what leaves a definition pure enough to replay.
///
/// [ActorEffect] has no ask operation. An actor sends a request with
/// [ActorEffect#tell(Address, application.Message)]. The request payload must
/// contain any correlation and reply address that state needs. The actor uses
/// [ActorEffect#schedule(java.time.Duration, application.Message)] to request a
/// timeout message.
///
/// ## Durable, or not
///
/// Durability is chosen when an actor is spawned, not when its type is defined:
///
/// ```
/// system.define(new Counting(), Spawn.ephemeral());   // keeps no log
/// system.spawn(Address.of("counter", "9"), Spawn.durable());  // except this one
/// ```
///
/// The rule that decides which to use is short. **An actor is durable, or its
/// state lives in a store it writes through effects. Never both.** A basket's
/// decisions have no other home, so it is durable. A collection actor whose
/// records live in a SQL table is ephemeral, because a log beside the table
/// would be a second record of one truth.
///
/// ## Modeling a domain with actors
///
/// Choose an actor boundary from the decisions that must be serialized. The
/// actor that owns those decisions is the authority for that part of the
/// domain.
///
/// An entity actor owns one entity with an independent lifecycle. Give it one
/// [Address] and put its state-changing rules in command handlers. Give it
/// queries when callers need a read that follows earlier accepted messages.
/// Derive child addresses with [Address#child(String, String)] when the child
/// belongs to that entity. Do not create one actor per record only because a
/// record has an id.
///
/// An authoritative collection actor owns a set of records and the decisions
/// that change that set. It can implement create, update, delete, lookup,
/// ordering, and cursor pagination as commands and queries on one actor.
/// Records do not need separate actors when they share that collection's
/// consistency boundary. Partition the collection when one actor would hold
/// unrelated work. Use the business owner as the partition key, such as
/// `account/42/orders` or `customer/42/notes`, instead of inventing a global
/// collection actor.
///
/// A collection actor is authoritative when its state is the source of truth.
/// Spawn it with [Spawn#durable()] when its state is only in its command log.
/// Spawn it with [Spawn#ephemeral()] when an external store is the source of
/// truth and the actor writes that store through an effect handler registered
/// with [ActorSystem#effect(String, application.Effect.Handler)]. One actor may
/// own that store. The store does not become a second actor runtime.
///
/// A derived view actor keeps a read model that is useful for a query but is
/// not authoritative. The authoritative actor sends it changes with
/// [ActorEffect#tell(Address, application.Message)], or a coordinator sends
/// them on its behalf. A derived view may be rebuilt from the authority. A
/// view can be ephemeral when its state lives in an external store, because
/// the store holds the view and not a second authoritative history.
///
/// A derived view is not a transaction boundary. A local actor tell completes
/// when the destination mailbox admits the delivery. It does not wait for the
/// destination to handle the message or append it to its journal. A crash in
/// that interval can lose the delivery. A source actor can redeliver an effect
/// after its own crash when [Spawn#redelivers()] is enabled, but that option
/// does not make two actor journals commit atomically. Make view updates
/// idempotent and carry a deduplication key when a repeated update is possible.
/// Treat a view as eventually consistent unless the authority and the view are
/// the same actor.
///
/// A registry actor serializes a narrow global decision, such as claiming a
/// normalized email address or slug. The registry owns the claim and release
/// rules. An entity or collection actor refers to the registry's result rather
/// than relying on an eventually updated view for uniqueness. Address registry
/// entries by the normalized key when that gives one serialization point.
///
/// A coordinator actor owns a workflow that spans several actors. It records
/// the workflow decisions when they must survive a restart, and it sends
/// commands to entity, collection, or registry actors. Keep business state in
/// the actor that owns it. Use [ActorEffect#schedule(java.time.Duration,
/// application.Message)] for deadlines and [ActorEffect#spawn(Address)] for
/// children that the workflow creates.
///
/// The actor address provides a simple partitioning tool. Put the ownership
/// key in the id, use [Address#child(String, String)] for hierarchical names,
/// and use [Fleet#under(String)] when a bounded fan-out over one owner is
/// needed. This is an application naming decision. It is not a hidden shard
/// manager, and [Registry] does not contain domain membership.
///
/// ## Looking at a running system
///
/// ```
/// system.known()                        // every actor, loaded or on disk
/// system.inspect(address)               // its state, as JSON
/// system.history(address, 0, 100)       // the commands it recorded
/// system.inspectAt(address, 12)         // the state it had after 12 commands
/// ```
///
/// [ActorSystem#inspectAt(Address, long)] is replay with a stopping point. It builds
/// a throwaway instance, advances it, reads it and drops it, so it never
/// disturbs the running actor and never runs an effect. A scrubber over an
/// actor's history is that method in a loop.
///
/// ## Many actors at once
///
/// ```
/// var total = system.fleet("counter")
///         .over(IntStream.rangeClosed(1, 100).mapToObj(String::valueOf))
///         .tell(address -> Counting.ADD.message())
///         .ask(address -> Counting.GET_TOTAL.message())
///         .mapToDouble(reply -> reply.number("value"))
///         .sum();
/// ```
///
/// [Fleet] names the addresses and runs the asks concurrently, then hands back
/// a [java.util.stream.Stream] so that the JDK's own combinators finish the
/// job. The concurrency is [java.util.stream.Gatherers#mapConcurrent(int,
/// java.util.function.Function)] on virtual threads, so there is no executor to
/// own.
///
/// ## Another system
///
/// An address carries a system name, written `orders:counter/42`. A message to
/// an address in another system goes to a [Transport], which this package
/// defines and does not implement beyond
/// [actors.transport.Loopback] for the tests. Sockets, framing and retries live
/// outside this package, exactly as they do for `jsonrpc2`.
///
/// ## The types in this package
///
///   - [ActorSystem] is the front door: define, tell, ask, evict, inspect.
///   - [Definition] is what you write. [ActorEffect] creates runtime effects.
///   - [Address] names an instance. [Spawn] decides how it runs.
///   - [Log] and [Logs] are the store, with [Journal] keeping one SQLite file
///     per actor. [Log#none()] is the whole of the undurable case.
///   - [Fleet] fans out. [Transport] reaches another system.
///   - [Undeliverable] is the failure a sender hears about.
///   - [Registry] is what the runtime knows about itself.
///
/// ## Surprises worth knowing
///
///   - **A reply address is never logged, and losing it on replay is correct.**
///     The caller that was waiting died with the process, so there is nothing
///     left to answer. A conversation that must survive a restart puts the
///     address in the payload on purpose and re-sends from `actors.resume`.
///   - **`actors.resume` arrives after replay, before any live message.** It
///     is where a timer is re-armed or a cache is reloaded, because replay
///     rebuilt the state and ran nothing. It is a control message, so it is
///     never logged.
///   - **A command that makes an update throw is skipped on replay exactly as
///     it failed live.** The failure was itself a message, so it is in the log
///     too, and replay reproduces the history with no special case.
///   - **An `Error` is different from an `Exception`.** Failing open catches
///     exceptions. An `Error` kills the actor's thread, and for a durable actor
///     the command that caused it replays and kills it again. The crash-loop
///     brake stops that after a few tries and quarantines the actor for a
///     person to look at.
///   - **Summoning is restart.** A dead actor is dropped from memory and the
///     next message loads it again from its log. That is why there is no
///     supervision tree.
///   - **A full mailbox does not block the sender.** [ActorSystem#tell(Address,
///     application.Message)] returns [DeliveryStatus#busy]. An actor sender also
///     receives `handle-delivery-error` with cause `busy`.
///   - **Actor effects run in list order.** Two tells from one actor to one
///     destination enter that destination in the same order. External effect
///     handlers run concurrently and can emit in any order.
///   - **Self-messages are continuations.** They keep effect order. The actor
///     handles them before it reads the next inbound message.
///   - **Queries use the mailbox and not the journal.** They follow earlier
///     accepted messages and can reply through ordinary effects. [Behavior]
///     keeps their returned state from being committed.
///   - **Schemas apply to payloads.** A schema does not see the message type,
///     timestamp, reply address, or deadline. An invalid payload is refused
///     before mailbox admission with [DeliveryStatus#invalid].
///   - **Declarations are discoverable.**
///     [ActorSystem#messageTypes(Address)] returns every command and query with
///     its kind and optional schema. It does not summon the actor.
///
/// ## One process owns one actor
///
/// A durable actor is claimed before it is summoned, and the claim is held until
/// its thread stops. Two processes over one log directory would otherwise each
/// replay the same commands and each append to the same file, and neither would
/// notice: the states diverge and the log ends up holding a sequence no single
/// actor ever saw.
///
/// [ActorSystem#rooted(java.nio.file.Path)] installs [Ownership#files] for this.
/// Summoning an actor another process is running raises [OwnershipException],
/// immediately and without waiting, because a second owner is a deployment
/// fault rather than congestion. [Ownership] is an interface so that a cluster
/// lease can replace the file lock without [ActorSystem] changing.
///
/// ## Actors are evicted when they go quiet
///
/// An actor idle for [Spawn#idle()] is evicted, and the next message summons it
/// again from its log. Summoning on demand and evicting when idle are the two
/// halves of the virtual actor pattern, and a system with only the first half
/// holds every actor it has ever touched.
///
/// A definition may also call an actor [Definition#settled(Object)], which
/// evicts it without waiting out the timeout. That is a hint and never a fact: a
/// settled actor still accepts messages, and a later definition is free to
/// disagree, because a durable actor is always summonable again from the same
/// commands.
///
/// ## What a crash costs
///
/// A command is appended before it is handled and its effects run afterwards, so
/// a process that stops in between leaves a command whose effects never
/// happened. [Spawn#redelivers()] decides whether the log records an applied
/// mark. The default suppresses the tail and writes no mark. Redelivery tracks
/// the mark and requires idempotent effects. [Spawn] contains the full table.
///
/// [Spawn#durability()] decides how hard the log works to keep a command it has
/// just appended. [Durability#normal] can lose the newest commands to a power
/// cut and never corrupts anything; [Durability#full] cannot lose one, and pays
/// a flush per message.
///
/// ## Watching a system work
///
/// [ActorSystem#traces()] publishes a [Trace] for every summon, eviction,
/// quarantine, undeliverable message, abandoned effect and dropped late
/// emission, live and in this process. A subscriber that falls behind loses the
/// oldest events it had not read and [ActorSystem#tracesDropped()] counts them.
///
/// [Flight] is one subscriber, and it writes those events into JDK Flight
/// Recorder. It is the only file in this package that names `jdk.jfr`, so an
/// image built without that module keeps working until something asks for a
/// recording. A live inspector would be another subscriber, and it gets events
/// as they happen rather than a recording that flushes about once a second.
///
/// `jdk.jfr` is outside `java.base`, so a stripped `jlink` image has to add it
/// explicitly.
///
/// ## Changing an actor's state by hand
///
/// Send it a command. There is no API for editing a state directly and there
/// will not be one.
///
/// A state is the fold of a log through the definition registered now, so a
/// state written in from the outside would be erased by the next replay, and a
/// state that cannot be reproduced from the commands is a state nobody can
/// explain. Correcting an actor is therefore the same operation as using it:
/// send the message that says what happened. The correction is recorded, it
/// replays, and it is visible in [ActorSystem#history(Address, long, int)] beside
/// everything else.
///
/// ## What is deliberately not built
///
/// Hot-reload generations and their class loaders, a browser inspector, a
/// control socket, log snapshots, supervision trees, and links and monitors.
/// Each is a real feature and none is needed to know whether the core is right.
/// The seams are there: [Definition] is the unit a reload would swap, [Logs] is
/// where a snapshot or a tiered store would go, [ActorSystem#known()] is what an
/// inspector would read, and [ActorSystem#traces()] is the live feed it would show.
///
/// Log growth is the known limit. Nothing compacts a log and nothing snapshots
/// one, so replay time grows with history for as long as an actor lives. That is
/// accepted rather than overlooked: a snapshot has to be keyed to the definition
/// that produced it, and a snapshot keyed wrongly silently resurrects the bug
/// the definition was changed to fix.
package actors;
