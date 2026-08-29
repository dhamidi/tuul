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
/// A [Definition] says what one type of actor is. It registers update functions
/// and nothing else.
///
/// ```
/// record Counter(long total) {}
///
/// final class Counting implements Definition<Counter> {
///     public String type() { return "counter"; }
///
///     public Application<Counter> instantiate(Address self) {
///         return Application.of(new Counter(0))
///                 .on("add", (state, message) -> Step.of(new Counter(state.total() + 1)))
///                 .on("total", (state, message) -> Step.of(state,
///                         Effect.sending("actor.reply",
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
///     system.tell(counter, Message.of("add"));
///     var answer = system.ask(counter, Message.of("total"), Duration.ofSeconds(2)).get();
/// }
/// ```
///
/// `actor.reply` carries no address. The system knows who is waiting on the
/// message being handled, so a reply address never reaches an update function
/// and never reaches a log.
///
/// ## The law
///
/// **Effects never run during replay.** Everything an effect learns comes back
/// as a message, and every message entering a mailbox is logged, so the log
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
/// A definition never holds a reference to a system. It asks for an effect, and
/// the system carries it out:
///
///   - `actor.tell` sends a message to an address.
///   - `actor.reply` answers whoever is waiting.
///   - `actor.ask` sends a message and names this actor as the reply address.
///   - `actor.spawn`, `actor.evict` and `actor.schedule` do what they say.
///
/// Handlers for anything else — a database, an HTTP client, a mailer — are
/// registered once on the system with [ActorSystem#effect(String,
/// application.Effect.Handler)]. They belong there because they own connections
/// that must outlive an actor being evicted, and keeping them out of a
/// definition is what leaves a definition pure enough to replay.
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
/// decisions have no other home, so it is durable. A projection whose state is
/// a SQL table is not, because a log beside the table would be a second record
/// of one truth, and two records of one truth disagree.
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
///         .tell(address -> Message.of("add"))
///         .ask(address -> Message.of("total"))
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
///   - [Definition] is what you write. [Address] is how you name an instance.
///     [Spawn] decides how it runs.
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
///     address in the payload on purpose and re-sends from `actors.resumed`.
///   - **`actors.resumed` arrives after replay, before any live message.** It
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
///   - **A full mailbox blocks the sender** for [Spawn#patience()] and then
///     answers `error.communication` with cause `busy`. A message whose
///     deadline has already passed is dropped before it is logged, because its
///     sender has stopped waiting.
///   - **The effects of one step have no order.** [application.Application]
///     runs every effect of a step on its own virtual thread and waits for all
///     of them, so an `actor.tell` and an `actor.reply` asked for by the same
///     update race. **An actor cannot make an announcement land before the
///     reply its caller is waiting on.** A post that answers its caller and
///     announces itself to an index gives the caller a redirect that can reach
///     a list one mailbox hop behind. Read-your-writes across two actors is
///     therefore not expressible inside a definition: the caller asks A, waits
///     for the answer, and then asks B with what A said. Two effects in one
///     step are only safe when neither one's result is read through the other.
///   - **A query is logged too.** Every message that enters a mailbox is
///     recorded, and a message that only reads the state is still a message. So
///     asking a durable counter for its total adds an entry to its log. That
///     follows from the law and it is not free: an actor that is read far more
///     often than it is written grows a log full of questions. Either read it
///     with [ActorSystem#inspect(Address)], which never enters the mailbox, or spawn
///     the read side as its own undurable actor.
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
/// happened. [Log#applied()] records how far the effects got, and
/// [Spawn#redelivers()] decides what happens to everything above that mark. The
/// default carries out an effect at most once, which is what this package has
/// always done; turning redelivery on makes it at least once and requires every
/// effect of that actor to be idempotent. [Spawn] carries the table.
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
