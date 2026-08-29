package actors;

import application.Application;
import application.Effect;
import application.Message;
import application.Step;
import actors.transport.Loopback;
import harness.Check;
import harness.Checkout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import json.Json;

public final class ActorsTest {

    private ActorsTest() {}

    public static void run() throws Exception {
        addresses();
        stamping();
        replays();
        upgrades();
        suppressesEffects();
        forgets();
        asks();
        undeliverable();
        poison();
        restarts();
        quarantines();
        resumes();
        travels();
        fans();
        crossesSystems();
        boundedSteps();
        owns();
        passivates();
        flushes();
        marksApplied();
        traces();
        flies();
        readsNothingIntoExistence();
        stamps();
        shadowing();
        numbers();
    }

    // ---- reading does not write ------------------------------------------

    /// Inspecting an actor that has never existed used to create its log.
    ///
    /// An actor id comes from a URL, so `GET /posts/<anything>` reading the
    /// post directly wrote three files per name anybody typed. Nothing bounded
    /// how many names there were.
    private static void readsNothingIntoExistence() throws Exception {
        var root = root();
        var never = Address.of("counter", "nobody-ever-wrote-this");
        try (var system = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
            Check.equal("nothing is on disk before anything is asked", 0L, files(root));
            Check.equal("an actor with no log inspects as its initial state",
                    0.0, total(system, never));
            Check.equal("and reading it wrote nothing", 0L, files(root));

            Check.equal("its history is empty", 0L, system.history(never, 0, 100).count());
            Check.equal("and reading that wrote nothing either", 0L, files(root));

            Check.equal("the state at a sequence number is the initial state too",
                    0.0, number(field(system.inspectAt(never, 5), "total")));
            Check.equal("and still nothing on disk", 0L, files(root));

            Check.equal("so the system knows of no actor at all", 0L, system.known().count());

            var written = Address.of("counter", "1");
            system.tell(written, Message.of("add").with("by", Json.of(3)));
            settle();
            Check.that("an actor that was written to does have a log", files(root) > 0);
            Check.equal("and it reads back", 3.0, total(system, written));
            Check.equal("its history is what it recorded", 1L, system.history(written, 0, 100).count());
        }
    }

    private static long files(Path root) throws IOException {
        try (var tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile).count();
        }
    }

    // ---- the timestamp of the message being handled ----------------------

    /// The documentation promises an update a "now" that replays unchanged.
    /// The actor stamps it, so [Message#at()] is that promise.
    private static void stamps() throws Exception {
        var root = root();
        var address = Address.of("stamping", "1");
        long before = java.lang.System.currentTimeMillis();
        long live;
        try (var system = ActorSystem.named("test").rooted(root).define(new Stamping())) {
            system.tell(address, Message.of("mark"));
            settle();
            live = (long) number(field(system.inspect(address), "at"));
            Check.that("an update reads the moment its own message arrived", live >= before);
        }
        try (var system = ActorSystem.named("test").rooted(root).define(new Stamping())) {
            Check.equal("and replay hands it the same number, not a new clock reading",
                    (double) live, number(field(system.inspect(address), "at")));
        }

        // `at` used to be a word a payload could not use: the envelope shared
        // the payload's namespace, so stamping a delivery would have destroyed a
        // sender's own `at`, and the stamp had to refuse rather than overwrite.
        // The two are separate objects now, so both fit and neither guards.
        var sent = Message.of("mark").with("at", 42.0);
        var stamped = sent.at(999);
        Check.equal("a payload field called at belongs to the sender", 42L, (long) stamped.number("at", 0));
        Check.equal("and the delivery is stamped beside it, not over it", 999L, stamped.at());
        Check.equal("a message with none is stamped", 999L, Message.of("mark").at(999).at());
        Check.equal("a message nobody stamped says zero", 0L, Message.of("mark").at());
    }

    // ---- the payload may use the envelope's words ------------------------

    /// A payload field called `type` survives everything.
    ///
    /// This is the whole reason the envelope exists. `Message.of(type, payload)`
    /// used to be `payload.with("type", type)`, so a payload carrying its own
    /// `type` lost it — not refused, not renamed, dropped. `type` is not an
    /// exotic name: every description javac writes about a symbol has one.
    ///
    /// Construction is the cheap half. The half worth checking is that it is
    /// still there after the message has been through an actor's mailbox, been
    /// written to a log as a payload and a set of columns, and been read back
    /// out by a replay in a different process lifetime.
    private static void shadowing() throws Exception {
        var payload = Json.Object.of().with("type", "invoice").with("id", Json.of(7));
        var message = Message.of("note.write", payload);
        Check.equal("a payload keeps its own type", "invoice", message.string("type", ""));
        Check.equal("and the envelope keeps the message's", "note.write", message.type());

        var root = root();
        var address = Address.of("shadowing", "1");
        try (var system = ActorSystem.named("test").rooted(root).define(new Shadowing())) {
            system.tell(address, message);
            settle();
            Check.equal("an update reads the payload's own type, not the message's",
                    "invoice", string(field(system.inspect(address), "kept")));
            Check.equal("and the payload's other fields with it",
                    7.0, number(field(system.inspect(address), "id")));
        }
        try (var system = ActorSystem.named("test").rooted(root).define(new Shadowing())) {
            Check.equal("and it is all still there when the log is replayed",
                    "invoice", string(field(system.inspect(address), "kept")));
            Check.equal("including the rest of the payload",
                    7.0, number(field(system.inspect(address), "id")));
        }
    }

    /// An actor that keeps whatever the payload called `type`.
    private record Kept(String type, double id) {}

    private static final class Shadowing implements Definition<Kept> {

        @Override
        public String type() {
            return "shadowing";
        }

        @Override
        public Application<Kept> instantiate(Address self) {
            return Application.of(new Kept("", 0))
                    .on("note.write", (state, message) ->
                            Step.of(new Kept(message.string("type", ""), message.number("id", 0))));
        }

        @Override
        public Json inspect(Kept state) {
            return Json.Object.of().with("kept", state.type()).with("id", state.id());
        }
    }

    /// An actor whose whole state is when it was last spoken to.
    private record Marked(long at) {}

    private static final class Stamping implements Definition<Marked> {

        @Override
        public String type() {
            return "stamping";
        }

        @Override
        public Application<Marked> instantiate(Address self) {
            return Application.of(new Marked(0))
                    .on("mark", (state, message) -> Step.of(new Marked(message.at())));
        }

        @Override
        public Json inspect(Marked state) {
            return Json.Object.of().with("at", (double) state.at());
        }
    }

    // ---- numbers ---------------------------------------------------------

    /// Every message carrying a count, an amount or a timestamp needed this,
    /// and the only numeric accessor lived on [Fleet.Reply].
    private static void numbers() {
        var message = Message.of("counted").with("total", 7.0).with("name", "ada");
        Check.equal("a numeric field reads back", 7.0, message.number("total", -1));
        Check.equal("a field that is not a number is the fallback", -1.0, message.number("name", -1));
        Check.equal("and so is a field nobody set", -1.0, message.number("missing", -1));
        Check.equal("an effect reads one the same way",
                7.0, Effect.of("count").with("total", 7.0).number("total", -1));

        Check.equal("a fleet reply reads its answer through the same accessor",
                7.0, new Fleet.Reply(Address.of("counter", "1"), message).number("total"));
        Check.equal("and an unanswered ask is zero",
                0.0, new Fleet.Reply(Address.of("counter", "1"), null).number("total"));
    }

    // ---- the trace bus ---------------------------------------------------

    /// Events reach a subscriber inside the process, as they happen.
    private static void traces() throws Exception {
        var root = root();
        var address = Address.of("counter", "1");
        var seen = new CopyOnWriteArrayList<Trace>();

        try (var system = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
            system.traces().subscribe(collector(seen));
            system.tell(address, Message.of("add").with("by", Json.of(1)));
            settle();

            Check.that("a summoned actor is announced",
                    seen.stream().anyMatch(trace -> trace.kind() == Trace.Kind.summoned
                            && trace.address().equals(address)));
            Check.that("and the announcement carries the replay cost, which grows with the log",
                    seen.stream().filter(trace -> trace.kind() == Trace.Kind.summoned)
                            .anyMatch(trace -> trace.detail() instanceof Json.Object detail
                                    && detail.get("millis") instanceof Json.Num));
            Check.that("a message is not traced unless it is asked for",
                    seen.stream().noneMatch(trace -> trace.kind() == Trace.Kind.handled));

            system.tell(Address.of("nobody", "1"), Message.of("add"));
            settle();
            Check.that("an undeliverable message is announced with its cause",
                    seen.stream().anyMatch(trace -> trace.kind() == Trace.Kind.undeliverable
                            && trace.detail() instanceof Json.Object detail
                            && detail.string("cause", "").equals("unknown")));

            system.evict(address);
            settle();
            Check.that("an evicted actor is announced",
                    seen.stream().anyMatch(trace -> trace.kind() == Trace.Kind.evicted));
        }

        // Per-message traces only when they are turned on.
        var chatty = new CopyOnWriteArrayList<Trace>();
        try (var system = ActorSystem.named("test").rooted(root()).define(new Counting(1))
                .tracingMessages(true)) {
            system.traces().subscribe(collector(chatty));
            system.tell(Address.of("counter", "2"), Message.of("add"));
            settle();
            Check.that("a message is traced once it is asked for",
                    chatty.stream().anyMatch(trace -> trace.kind() == Trace.Kind.handled));
        }

        Check.equal("a trace carries the address and the kind it says it does",
                "counter/1", seen.getFirst().json().string("address", ""));
        Check.that("and nothing was lost to a slow subscriber in a quiet test", true);
    }

    private static java.util.concurrent.Flow.Subscriber<Trace> collector(List<Trace> into) {
        return new java.util.concurrent.Flow.Subscriber<>() {

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Trace trace) {
                into.add(trace);
            }

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        };
    }

    // ---- flight recording ------------------------------------------------

    /// The JFR subscriber writes real events into a real recording.
    private static void flies() throws Exception {
        var root = root();
        var file = Files.createTempFile("tuul-actors", ".jfr");
        file.toFile().deleteOnExit();
        var address = Address.of("counter", "1");

        try (var system = ActorSystem.named("test").rooted(root).define(new Counting(1));
                var flight = Flight.recording(system);
                var recording = new jdk.jfr.Recording()) {
            recording.enable("tuul.actors.Summoned");
            recording.enable("tuul.actors.Evicted");
            recording.start();
            system.tell(address, Message.of("add").with("by", Json.of(2)));
            settle();
            system.evict(address);
            settle();
            recording.dump(file);
        }

        var events = new ArrayList<String>();
        try (var read = new jdk.jfr.consumer.RecordingFile(file)) {
            while (read.hasMoreEvents()) events.add(read.readEvent().getEventType().getName());
        }
        Check.that("a summon reaches the recording", events.contains("tuul.actors.Summoned"));
        Check.that("an eviction reaches the recording", events.contains("tuul.actors.Evicted"));
        Check.that("and nothing else in the package mentions jdk.jfr", onlyFlightUsesJfr());
    }

    /// [Flight] is meant to be the only file that names `jdk.jfr`, so that an
    /// image built without that module still loads the rest of the package.
    private static boolean onlyFlightUsesJfr() throws IOException {
        try (var sources = Files.list(Checkout.at("src", "actors"))) {
            return sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("Flight.java"))
                    .noneMatch(ActorsTest::mentionsJfr);
        }
    }

    /// Whether the *code* of a file depends on `jdk.jfr`.
    ///
    /// Doc comments are stripped first. Several files talk about flight
    /// recording in prose, and prose creates no dependency: what matters is
    /// whether loading the class needs the module.
    private static boolean mentionsJfr(Path source) {
        try {
            return Files.readAllLines(source).stream()
                    .map(String::strip)
                    .filter(line -> !line.startsWith("///") && !line.startsWith("//"))
                    .anyMatch(line -> line.contains("jdk.jfr"));
        } catch (IOException e) {
            return false;
        }
    }

    // ---- the applied watermark -------------------------------------------

    /// A counter that also asks for an effect, so a test can see whether the
    /// effects of a command ran or not.
    private static final class Acting implements Definition<Counter> {

        @Override
        public String type() {
            return "acting";
        }

        @Override
        public Application<Counter> instantiate(Address self) {
            return Application.of(new Counter(0))
                    .on("add", (state, message) -> Step.of(new Counter(state.total() + amount(message)),
                            Effect.of("record").with("by", Json.of(amount(message)))));
        }

        @Override
        public Json inspect(Counter state) {
            return Json.Object.of().with("total", state.total());
        }
    }

    /// A command that was logged but whose effects did not finish is the tail.
    /// What happens to it is a spawn option, and the default loses it.
    private static void marksApplied() throws Exception {
        // In ordinary running the mark keeps up with the log.
        var busy = root();
        var address = Address.of("acting", "1");
        try (var system = ActorSystem.named("test").rooted(busy).define(new Acting())
                .effect("record", (effect, emit) -> {})) {
            system.tell(address, Message.of("add").with("by", Json.of(2)));
            system.tell(address, Message.of("add").with("by", Json.of(3)));
            settle();
            Check.equal("the state is the fold of the commands", 5.0, total(system, address));
        }
        try (var logs = new Journals(busy)) {
            var log = logs.open(address);
            Check.equal("every command that was handled is marked applied", log.length(), log.applied());
            Check.equal("and there were two of them", 2L, log.length());
        }

        // A command appended with the mark left behind is exactly what a crash
        // between the append and the effects leaves on disk.
        Check.equal("by default the tail is advanced into the state and its effects are lost",
                List.of("total=7.0", "ran=0"), tail(false));

        Check.equal("with redelivery the tail is handled properly, effects and all",
                List.of("total=7.0", "ran=1"), tail(true));

        // And redelivery happens once: the mark moves up, so a later summon
        // replays the same command quietly.
        var settled = root();
        crashed(settled);
        var ran = new java.util.concurrent.atomic.AtomicInteger();
        var redelivering = Spawn.durable().redelivers(true);
        try (var system = ActorSystem.named("test").rooted(settled).define(new Acting(), redelivering)
                .effect("record", (effect, emit) -> ran.incrementAndGet())) {
            summon(system, tailAddress());
            settle();
            Check.equal("the tail runs on the first summon after the crash", 7.0, total(system, tailAddress()));
        }
        try (var system = ActorSystem.named("test").rooted(settled).define(new Acting(), redelivering)
                .effect("record", (effect, emit) -> ran.incrementAndGet())) {
            summon(system, tailAddress());
            settle();
            Check.equal("and the state is unchanged afterwards", 7.0, total(system, tailAddress()));
        }
        Check.equal("a redelivered command is not delivered again on the next summon", 1, ran.get());
    }

    private static Address tailAddress() {
        return Address.of("acting", "crashed");
    }

    /// Writes a log with one command above the applied mark, which is what a
    /// process that stopped between appending and acting leaves behind.
    private static void crashed(Path root) {
        try (var logs = new Journals(root)) {
            var log = logs.open(tailAddress());
            log.append(Message.of("add").with("by", Json.of(7)).at(System.currentTimeMillis()));
        }
    }

    /// Summons an actor whose log has an unapplied tail, and answers with the
    /// state it reached and how many effects ran.
    private static List<String> tail(boolean redelivers) throws Exception {
        var root = root();
        crashed(root);
        var ran = new AtomicInteger();
        var spawn = Spawn.durable().redelivers(redelivers);
        try (var system = ActorSystem.named("test").rooted(root).define(new Acting(), spawn)
                .effect("record", (effect, emit) -> ran.incrementAndGet())) {
            Check.equal("an unloaded actor inspects to the full fold of its log",
                    7.0, total(system, tailAddress()));
            Check.equal("and inspecting it ran no effect, because a shadow replay never redelivers",
                    0, ran.get());
            summon(system, tailAddress());
            settle();
            return List.of("total=" + total(system, tailAddress()), "ran=" + ran.get());
        }
    }

    /// Loads an actor without sending it anything, so that replay runs and the
    /// log gains no entry the test did not intend.
    private static void summon(ActorSystem system, Address address) {
        system.subscriber(address);
    }

    // ---- durability ------------------------------------------------------

    /// The `synchronous` setting an actor asks for is the one its log uses.
    private static void flushes() throws Exception {
        var root = root();
        Check.equal("an actor flushes at checkpoints unless it says otherwise",
                Durability.normal, Spawn.durable().durability());

        try (var logs = new Journals(root)) {
            var careful = (Journal) logs.open(Address.of("ledger", "1"), Durability.full);
            Check.equal("an actor that asks for full durability gets it",
                    Durability.full, careful.synchronous());

            var ordinary = (Journal) logs.open(Address.of("counter", "1"), Durability.normal);
            Check.equal("and one that does not, does not",
                    Durability.normal, ordinary.synchronous());

            var changed = (Journal) logs.open(Address.of("counter", "1"), Durability.full);
            Check.equal("summoning again with a new setting applies it to the open connection",
                    Durability.full, changed.synchronous());
        }

        // A durable actor still behaves the same way with either setting.
        var address = Address.of("counter", "1");
        try (var system = ActorSystem.named("test").rooted(root)
                .define(new Counting(1), Spawn.durable().durability(Durability.full))) {
            system.tell(address, Message.of("add").with("by", Json.of(4)));
            settle();
            Check.equal("an actor with full durability records and replays as usual",
                    4.0, total(system, address));
        }
        try (var system = ActorSystem.named("test").rooted(root)
                .define(new Counting(1), Spawn.durable().durability(Durability.full))) {
            Check.equal("and comes back from the log", 4.0, total(system, address));
        }
    }

    // ---- passivation -----------------------------------------------------

    /// An idle actor is evicted, a busy one is not, and a settled one goes
    /// early.
    private static void passivates() throws Exception {
        var root = root();
        var address = Address.of("counter", "1");
        var brief = Spawn.durable().idle(Duration.ofMillis(600));

        try (var system = ActorSystem.named("test").rooted(root)
                .define(new Counting(1), brief)
                .sweeping(Duration.ofMillis(40))) {
            system.tell(address, Message.of("add").with("by", Json.of(3)));
            Thread.sleep(120);
            Check.that("the actor is loaded while it is being used", isLoaded(system, address));

            Thread.sleep(1_200);
            Check.that("an idle actor is evicted", !isLoaded(system, address));
            Check.that("the log survives passivation",
                    system.known().anyMatch(entry -> entry.address().equals(address)));
            Check.equal("and the state comes back from it", 3.0, total(system, address));
        }

        // An actor that is never idle stays put.
        try (var system = ActorSystem.named("test").rooted(root)
                .define(new Counting(1), brief)
                .sweeping(Duration.ofMillis(40))) {
            var busy = Address.of("counter", "busy");
            for (var round = 0; round < 12; round++) {
                system.tell(busy, Message.of("add"));
                Thread.sleep(100);
            }
            Check.that("an actor that keeps working is not swept out", isLoaded(system, busy));
        }

        // Passivation can be turned off for one actor.
        try (var system = ActorSystem.named("test").rooted(root)
                .define(new Counting(1), brief.idle(Duration.ZERO))
                .sweeping(Duration.ofMillis(40))) {
            var pinned = Address.of("counter", "pinned");
            system.tell(pinned, Message.of("add"));
            Thread.sleep(1_200);
            Check.that("an actor with no idle timeout is never swept", isLoaded(system, pinned));
        }

        // A settled actor goes without waiting out its idle period, and the
        // registry says so before it goes.
        var patient = Spawn.durable().idle(Duration.ofMinutes(10));
        try (var system = ActorSystem.named("test").rooted(root())
                .define(new Closing(), patient)
                .sweeping(Duration.ofMillis(40))) {
            var closing = Address.of("closing", "1");
            system.tell(closing, Message.of("add").with("by", Json.of(1)));
            settle();
            Check.that("an unsettled actor stays loaded", isLoaded(system, closing));
            Check.that("and the registry reports it as unsettled",
                    system.known().noneMatch(entry -> entry.address().equals(closing) && entry.settled()));

            system.tell(closing, Message.of("add").with("by", Json.of(20)));
            Thread.sleep(300);
            Check.that("a settled actor is evicted although its idle period has not passed",
                    !isLoaded(system, closing));
            Check.equal("and it still answers, because settled is a hint and not a closure",
                    21.0, total(system, closing));
        }

        // A definition whose settled() throws must not take the actor down.
        Definition<Counter> broken = new Definition<>() {

            @Override
            public String type() {
                return "broken";
            }

            @Override
            public Application<Counter> instantiate(Address self) {
                return Application.of(new Counter(0))
                        .on("add", (state, message) -> Step.of(new Counter(state.total() + 1)));
            }

            @Override
            public Json inspect(Counter state) {
                return Json.Object.of().with("total", state.total());
            }

            @Override
            public boolean settled(Counter state) {
                throw new IllegalStateException("this hint is broken");
            }
        };

        try (var system = ActorSystem.named("test").rooted(root())
                .define(broken, Spawn.durable().idle(Duration.ofMinutes(10)))
                .sweeping(Duration.ofMillis(40))) {
            var faulty = Address.of("broken", "1");
            system.tell(faulty, Message.of("add"));
            settle();
            Check.equal("a definition whose settled hint throws keeps working", 1.0, total(system, faulty));
            Check.that("and stays loaded, because a broken hint answers false", isLoaded(system, faulty));
        }
    }

    /// A definition that calls itself settled once its total reaches a limit.
    private static final class Closing implements Definition<Counter> {

        @Override
        public String type() {
            return "closing";
        }

        @Override
        public Application<Counter> instantiate(Address self) {
            return Application.of(new Counter(0))
                    .on("add", (state, message) -> Step.of(new Counter(state.total() + amount(message))));
        }

        @Override
        public Json inspect(Counter state) {
            return Json.Object.of().with("total", state.total());
        }

        @Override
        public boolean settled(Counter state) {
            return state.total() >= 10;
        }
    }

    private static boolean isLoaded(ActorSystem system, Address address) {
        return system.known().anyMatch(entry -> entry.address().equals(address) && entry.loaded());
    }

    // ---- ownership -------------------------------------------------------

    /// Two systems over one directory. The second must be refused rather than
    /// quietly running a second copy of the same actor.
    private static void owns() throws Exception {
        var root = root();
        var address = Address.of("counter", "1");

        try (var first = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
            first.tell(address, Message.of("add").with("by", Json.of(2)));
            settle();
            Check.equal("the first owner runs the actor", 2.0, total(first, address));

            try (var second = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
                var refused = new ArrayList<String>();
                try {
                    second.tell(address, Message.of("add").with("by", Json.of(5)));
                } catch (OwnershipException taken) {
                    refused.add(taken.getMessage());
                }
                Check.equal("a second owner is refused", 1, refused.size());
                Check.that("and the refusal names the actor",
                        refused.getFirst().contains("counter/1"));
                Check.that("and says another owner has it",
                        refused.getFirst().contains("another owner"));
                Check.equal("the address of the refusal is the actor",
                        address, ownershipFailure(second, address).address());
            }

            settle();
            Check.equal("the refused message never reached the log", 2.0, total(first, address));
        }

        // The claim belongs to the actor, not to the log, so a handover works
        // once the first owner has let go.
        try (var second = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
            second.tell(address, Message.of("add").with("by", Json.of(5)));
            settle();
            Check.equal("a later owner takes over and appends to the same history",
                    7.0, total(second, address));
        }

        // Eviction releases the claim inside one process too, so the same
        // system can summon the actor again straight afterwards.
        try (var system = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
            system.tell(address, Message.of("add").with("by", Json.of(1)));
            settle();
            system.evict(address);
            settle();
            system.tell(address, Message.of("add").with("by", Json.of(1)));
            settle();
            Check.equal("evicting and summoning again reclaims cleanly", 9.0, total(system, address));
        }

        // An actor with no log has no shared history, so nothing is claimed and
        // two systems can both run one.
        try (var one = ActorSystem.named("test").rooted(root).define(definitionOf("free"), Spawn.ephemeral());
                var two = ActorSystem.named("test").rooted(root).define(definitionOf("free"), Spawn.ephemeral())) {
            var free = Address.of("free", "1");
            one.tell(free, Message.of("nothing"));
            two.tell(free, Message.of("nothing"));
            settle();
            Check.that("an undurable actor is not claimed, because it shares no history", true);
        }
    }

    /// Sends to an address this system cannot claim, and answers with the
    /// refusal.
    private static OwnershipException ownershipFailure(ActorSystem system, Address taken) {
        try {
            system.tell(taken, Message.of("add"));
            throw new IllegalStateException("expected the claim on " + taken + " to be refused");
        } catch (OwnershipException refused) {
            return refused;
        }
    }

    // ---- an effect that sends is a message plus a destination -------------

    /// The handler stamps the destination; the sender never packs a message.
    ///
    /// The shape this replaced nested a whole message inside the effect's
    /// payload, so `with("message", message.body())` compiled and handed over a
    /// payload with no envelope. The message arrived typeless and nothing
    /// dispatched it. Now the effect's body *is* the payload, which moves the
    /// question from "did the sender pack it right" to "did the sender say what
    /// it sends" — and that one can be refused.
    private static void stamping() throws Exception {
        refusesToSendNothing();
        routingDoesNotTouchThePayload();
        keepsNoReplyAddress();
    }

    /// An effect that does not say what it sends is a mistake, and it says so.
    private static void refusesToSendNothing() throws Exception {
        var errors = new CopyOnWriteArrayList<Message>();
        Definition<Long> mute = new Definition<>() {

            @Override
            public String type() {
                return "mute";
            }

            @Override
            public Application<Long> instantiate(Address self) {
                return Application.of(0L)
                        .on("go", (state, message) -> Step.of(state, Effect.of(ActorSystem.REPLY)))
                        .on("error", (state, message) -> {
                            errors.add(message);
                            return Step.of(state);
                        });
            }
        };

        try (var system = ActorSystem.named("test").define(mute, Spawn.ephemeral())) {
            system.tell(mute.at("1"), Message.of("go"));
            settle();
            Check.equal("an effect that sends nothing is refused rather than delivering a typeless message",
                    1, errors.size());
            Check.that("and the refusal says how to build one properly",
                    errors.isEmpty() || errors.getFirst().string("reason", "").contains("Effect.sending"));
        }
    }

    /// Routing is an envelope field, so a payload may have a field called `to`
    /// and mean its own thing by it.
    ///
    /// While routing lived in the effect's body it shared a namespace with the
    /// message's payload — the collision the envelope exists to end, one level
    /// up from where it was found the first time. The payload here says `to`
    /// and means a customer; the envelope says which actor the message goes to.
    /// Nothing about an actor address is postal, and the point is that the two
    /// words never meet.
    private static void routingDoesNotTouchThePayload() throws Exception {
        var delivered = new CopyOnWriteArrayList<String>();
        var seen = new CopyOnWriteArrayList<Message>();
        Definition<Long> ledger = new Definition<>() {

            @Override
            public String type() {
                return "post";
            }

            @Override
            public Application<Long> instantiate(Address self) {
                return Application.of(0L)
                        .on("send", (state, message) -> Step.of(state,
                                Effect.sending(ActorSystem.TELL,
                                                Message.of("parcel").with("to", "Ada Lovelace"))
                                        .about(ActorSystem.TO, Address.of("post", "2").json())))
                        .on("parcel", (state, message) -> {
                            delivered.add(self.toString());
                            seen.add(message);
                            return Step.of(state);
                        });
            }
        };

        try (var system = ActorSystem.named("test").define(ledger, Spawn.ephemeral())) {
            system.tell(ledger.at("1"), Message.of("send"));
            settle();
            Check.equal("exactly one parcel arrived", 1, seen.size());
            Check.equal("at the actor the envelope named", List.of("post/2"), List.copyOf(delivered));
            Check.equal("and the payload's own `to` arrived untouched",
                    "Ada Lovelace", seen.isEmpty() ? "" : seen.getFirst().string("to", ""));
        }
    }

    /// A log keeps the type, the stamp and the payload, and drops the rest of
    /// an envelope.
    ///
    /// [Delivery] keeps a reply address out of the message so that it cannot be
    /// written down, and this is the second half of that: even a message that
    /// does carry an unknown envelope field leaves it at the log. The rule is
    /// asserted here rather than left to whichever columns a store happens to
    /// have, because adding one would otherwise start writing callers'
    /// addresses into a permanent record of intent.
    private static void keepsNoReplyAddress() throws Exception {
        var root = root();
        var address = Address.of("counter", "1");
        try (var system = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
            var smuggled = Message.from(Json.Object.of()
                    .with("type", "add")
                    .with("replyTo", "counter/999")
                    .with("body", Json.Object.of().with("by", Json.of(2))));
            Check.equal("a message read from a document keeps an envelope field nobody knows",
                    "counter/999", smuggled.envelope().string("replyTo", ""));

            system.tell(address, smuggled);
            settle();
            system.ask(address, Message.of("total"), Duration.ofSeconds(2)).get();
            settle();

            try (var history = system.history(address, 0, 100)) {
                var envelopes = history.map(command -> command.envelope().fields().keySet()).toList();
                Check.that("a log keeps only the type, the stamp and the sequence number",
                        envelopes.stream().allMatch(keys -> keys.equals(java.util.Set.of("type", "at", "seq"))));
                Check.that("so no reply address survives being written down",
                        envelopes.stream().noneMatch(keys -> keys.contains("replyTo")));
            }
        }
    }

    // ---- the counter every test below talks to ---------------------------

    /// A counter that adds what it is told and answers with its total.
    private record Counter(long total) {}

    private static final class Counting implements Definition<Counter> {

        private final long weight;

        private Counting(long weight) {
            this.weight = weight;
        }

        @Override
        public String type() {
            return "counter";
        }

        @Override
        public Application<Counter> instantiate(Address self) {
            return Application.of(new Counter(0))
                    .on("add", (state, message) -> Step.of(new Counter(state.total() + weight * amount(message))))
                    .on("total", (state, message) -> Step.of(state,
                            Effect.sending(ActorSystem.REPLY,
                                    Message.of("total").with("value", Json.of(state.total())))));
        }

        @Override
        public Json inspect(Counter state) {
            return Json.Object.of().with("total", state.total());
        }
    }

    private static long amount(Message message) {
        return message.get("by") instanceof Json.Num(var value) ? (long) value : 1;
    }

    private static Path root() throws IOException {
        var directory = Files.createTempDirectory("tuul-actors");
        directory.toFile().deleteOnExit();
        return directory;
    }

    // ---- addresses -------------------------------------------------------

    private static void addresses() {
        Check.equal("a local address reads back the way it was written",
                "counter/42", Address.parse("counter/42").toString());
        Check.equal("an address in another system keeps its system",
                "orders:counter/42", Address.parse("orders:counter/42").toString());
        Check.equal("an id is everything after the first slash",
                "42/7", Address.parse("basket/42/7").id());
        Check.equal("a type is the part before the first slash",
                "basket", Address.parse("basket/42/7").type());
        Check.that("an address with no system is local", Address.parse("counter/1").local());
        Check.that("an address with another system is foreign",
                Address.parse("orders:counter/1").foreign("billing"));
        Check.that("an address in its own system is not foreign",
                !Address.parse("orders:counter/1").foreign("orders"));

        var customer = Address.of("customer", "42");
        Check.equal("a child carries its parent's id as a prefix",
                "basket/42/7", customer.child("basket", "7").toString());
        Check.equal("a sibling keeps the type", "customer/43", customer.sibling("43").toString());
        Check.equal("an address survives a trip through json",
                customer, Address.from(customer.json()));
        Check.equal("an address survives a trip through its written form",
                Address.parse("orders:basket/42/7"),
                Address.from(Json.of(Address.parse("orders:basket/42/7").toString())));

        var definition = new Counting(1);
        Check.equal("a definition names its own addresses, so no caller spells the type",
                "counter/9", definition.at("9").toString());

        Check.throwing("a type with a slash is refused", () -> Address.of("a/b", "1"));
    }

    // ---- replay ----------------------------------------------------------

    private static void replays() throws Exception {
        var root = root();
        var address = Address.of("counter", "1");
        try (var system = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
            system.tell(address, Message.of("add").with("by", Json.of(3)));
            system.tell(address, Message.of("add").with("by", Json.of(4)));
            settle();
            Check.equal("the state is the fold of the commands", 7.0, total(system, address));
            system.evict(address);
            settle();
            Check.equal("an evicted actor comes back with the same state", 7.0, total(system, address));
        }

        try (var system = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
            Check.equal("and comes back in a new system too, from the log alone",
                    7.0, total(system, address));
        }
    }

    /// The upgrade story: the same log, a definition that counts differently,
    /// and a state recomputed by the new rule rather than migrated.
    private static void upgrades() throws Exception {
        var root = root();
        var address = Address.of("counter", "1");
        try (var system = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
            system.tell(address, Message.of("add").with("by", Json.of(5)));
            settle();
            Check.equal("the first definition counts once", 5.0, total(system, address));
        }
        try (var system = ActorSystem.named("test").rooted(root).define(new Counting(10))) {
            Check.equal("replaying the same commands through a new definition recomputes the state",
                    50.0, total(system, address));
        }
    }

    /// The law: an effect that ran live does not run again on replay.
    private static void suppressesEffects() throws Exception {
        var root = root();
        var ran = new CopyOnWriteArrayList<String>();
        var address = Address.of("noisy", "1");

        Definition<Long> noisy = new Definition<>() {

            @Override
            public String type() {
                return "noisy";
            }

            @Override
            public Application<Long> instantiate(Address self) {
                return Application.of(0L)
                        .on("shout", (state, message) -> Step.of(state + 1, Effect.of("record")));
            }

            @Override
            public Json inspect(Long state) {
                return Json.of(state);
            }
        };

        try (var system = ActorSystem.named("test").rooted(root).define(noisy)
                .effect("record", (effect, emit) -> ran.add("ran"))) {
            system.tell(address, Message.of("shout"));
            system.tell(address, Message.of("shout"));
            settle();
            Check.equal("the effect ran once per message while live", 2, ran.size());
        }

        try (var system = ActorSystem.named("test").rooted(root).define(noisy)
                .effect("record", (effect, emit) -> ran.add("ran"))) {
            Check.equal("replay rebuilt the state", 2.0, number(system.inspect(address)));
            Check.equal("and ran no effect while doing it", 2, ran.size());
        }
    }

    /// An undurable actor keeps no log at all.
    private static void forgets() throws Exception {
        var root = root();
        var address = Address.of("counter", "1");
        try (var system = ActorSystem.named("test").rooted(root)
                .define(new Counting(1), Spawn.ephemeral())) {
            system.tell(address, Message.of("add").with("by", Json.of(3)));
            settle();
            Check.equal("an undurable actor still counts", 3.0, total(system, address));
            system.evict(address);
            settle();
            Check.equal("and forgets everything when it is evicted", 0.0, total(system, address));
        }
        Check.that("an undurable actor left no log behind",
                !Files.exists(Address.of("counter", "1").path(root)));
    }

    // ---- ask -------------------------------------------------------------

    private static void asks() throws Exception {
        try (var system = ActorSystem.named("test").define(new Counting(1), Spawn.ephemeral())) {
            var address = Address.of("counter", "1");
            system.tell(address, Message.of("add").with("by", Json.of(2)));
            var answer = system.ask(address, Message.of("total"), Duration.ofSeconds(2)).get();
            Check.equal("an ask comes back with the answer", 2.0, number(answer.get("value")));
        }

        Definition<Long> silent = definitionOf("silent");
        try (var system = ActorSystem.named("test").define(silent, Spawn.ephemeral())) {
            var failed = false;
            try {
                system.ask(silent.at("1"), Message.of("nothing"), Duration.ofMillis(150)).get();
            } catch (Exception expected) {
                failed = true;
            }
            Check.that("an ask nobody answers runs out of time", failed);
        }
    }

    // ---- error.communication ---------------------------------------------

    private static void undeliverable() throws Exception {
        var notices = new CopyOnWriteArrayList<Message>();
        Definition<Long> sender = new Definition<>() {

            @Override
            public String type() {
                return "sender";
            }

            @Override
            public Application<Long> instantiate(Address self) {
                return Application.of(0L)
                        .on("go", (state, message) -> Step.of(state,
                                Effect.sending(ActorSystem.TELL, Message.of("hello"))
                                        .about(ActorSystem.TO, Address.parse(message.string("to", "")).json())))
                        .on(Undeliverable.TYPE, (state, message) -> {
                            notices.add(message);
                            return Step.of(state);
                        });
            }
        };

        try (var system = ActorSystem.named("test").define(sender, Spawn.ephemeral())) {
            system.tell(sender.at("1"), Message.of("go").with("to", "nobody/1"));
            settle();
            Check.equal("an address with no definition is refused at once", 1, notices.size());
            Check.equal("and the cause says why", Undeliverable.Cause.unknown,
                    Undeliverable.causeOf(notices.getFirst()));
            Check.equal("the notice carries the message that did not arrive",
                    "hello", Undeliverable.commandOf(notices.getFirst()).type());
            Check.equal("and the address it was going to",
                    Address.of("nobody", "1"), Undeliverable.toOf(notices.getFirst()));
        }

        notices.clear();
        try (var system = ActorSystem.named("test").define(sender, Spawn.ephemeral())) {
            system.tell(sender.at("1"), Message.of("go").with("to", "elsewhere:thing/1"));
            settle();
            Check.equal("a foreign address with no transport is unreachable",
                    Undeliverable.Cause.unreachable, Undeliverable.causeOf(notices.getFirst()));
        }

        // busy: a mailbox of one, an actor that never finishes, and no patience.
        notices.clear();
        var held = new CountDownLatch(1);
        Definition<Long> slow = new Definition<>() {

            @Override
            public String type() {
                return "slow";
            }

            @Override
            public Application<Long> instantiate(Address self) {
                return Application.of(0L).on("wait", (state, message) -> {
                    try {
                        held.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Step.of(state);
                });
            }
        };
        try (var system = ActorSystem.named("test")
                .define(slow, Spawn.ephemeral().mailbox(1).patience(Duration.ofMillis(50)))
                .define(sender, Spawn.ephemeral())) {
            system.tell(slow.at("1"), Message.of("wait"));
            Thread.sleep(100);
            system.tell(slow.at("1"), Message.of("wait"));
            system.tell(sender.at("1"), Message.of("go").with("to", "slow/1"));
            Thread.sleep(400);
            held.countDown();
            Check.that("a mailbox that stays full tells the sender it is busy",
                    notices.stream().anyMatch(n -> Undeliverable.causeOf(n) == Undeliverable.Cause.busy));
        }
    }

    // ---- failing open ----------------------------------------------------

    /// A command that makes an update throw is logged, skipped, and replays the
    /// same way it failed live.
    private static void poison() throws Exception {
        var root = root();
        var address = Address.of("brittle", "1");
        Definition<Long> brittle = new Definition<>() {

            @Override
            public String type() {
                return "brittle";
            }

            @Override
            public Application<Long> instantiate(Address self) {
                return Application.of(0L)
                        .on("add", (state, message) -> Step.of(state + 1))
                        .on("boom", (state, message) -> {
                            throw new IllegalStateException("no");
                        });
            }

            @Override
            public Json inspect(Long state) {
                return Json.of(state);
            }
        };

        try (var system = ActorSystem.named("test").rooted(root).define(brittle)) {
            system.tell(address, Message.of("add"));
            system.tell(address, Message.of("boom"));
            system.tell(address, Message.of("add"));
            settle();
            Check.equal("a throwing update leaves the state alone and the others still run",
                    2.0, number(system.inspect(address)));
            Check.that("the poison command is in the log",
                    system.history(address, 0, 100).anyMatch(m -> m.type().equals("boom")));
            Check.that("and so is the error it produced",
                    system.history(address, 0, 100).anyMatch(m -> m.type().equals("error")));
        }

        try (var system = ActorSystem.named("test").rooted(root).define(brittle)) {
            Check.equal("replay reproduces the live outcome exactly, poison and all",
                    2.0, number(system.inspect(address)));
        }
    }

    // ---- death -----------------------------------------------------------

    private static void restarts() throws Exception {
        var root = root();
        var lives = new AtomicInteger();
        Definition<Long> fragile = new Definition<>() {

            @Override
            public String type() {
                return "fragile";
            }

            @Override
            public Application<Long> instantiate(Address self) {
                lives.incrementAndGet();
                return Application.of(0L)
                        .on("add", (state, message) -> Step.of(state + 1))
                        .on("die", (state, message) -> {
                            throw new StackOverflowError("the thread is over");
                        });
            }

            @Override
            public Json inspect(Long state) {
                return Json.of(state);
            }
        };

        // An undurable actor: nothing to replay, so a restart is clean.
        var passing = Address.of("fragile", "1");
        try (var system = ActorSystem.named("test").define(fragile, Spawn.ephemeral())) {
            system.tell(passing, Message.of("add"));
            settle();
            Check.equal("one instance so far", 1, lives.get());
            system.tell(passing, Message.of("die"));
            settle();
            system.tell(passing, Message.of("add"));
            settle();
            Check.equal("a dead actor is summoned again by the next message", 2, lives.get());
            Check.equal("an undurable actor comes back empty, because it has nothing to replay",
                    1.0, number(system.inspect(passing)));
        }

        // A durable actor: the command that killed it is in the log, so replay
        // meets it again. Failing open catches an Exception and not an Error.
        lives.set(0);
        var poisoned = Address.of("fragile", "2");
        try (var system = ActorSystem.named("test").rooted(root)
                .define(fragile, Spawn.durable().restarts(3, Duration.ofMinutes(1)))) {
            system.tell(poisoned, Message.of("add"));
            settle();
            system.tell(poisoned, Message.of("die"));
            settle();
            Check.equal("the state up to the command before the poison is still readable",
                    1.0, number(system.inspectAt(poisoned, 1)));
            for (var attempt = 0; attempt < 4; attempt++) {
                system.tell(poisoned, Message.of("add"));
                settle();
            }
            Check.that("an Error in an update is a poison pill, and the brake stops the loop",
                    system.registry().quarantined(poisoned));
            Check.that("a quarantined actor refuses new messages rather than dying again",
                    lives.get() <= 4);
        }
    }

    private static void quarantines() throws Exception {
        var address = Address.of("doomed", "1");
        Definition<Long> doomed = new Definition<>() {

            @Override
            public String type() {
                return "doomed";
            }

            @Override
            public Application<Long> instantiate(Address self) {
                return Application.of(0L).on("die", (state, message) -> {
                    throw new StackOverflowError("again");
                });
            }
        };

        try (var system = ActorSystem.named("test")
                .define(doomed, Spawn.ephemeral().restarts(3, Duration.ofMinutes(1)))) {
            for (var attempt = 0; attempt < 5; attempt++) {
                system.tell(address, Message.of("die"));
                settle();
            }
            Check.that("an actor that keeps dying is quarantined",
                    system.registry().quarantined(address));
            system.revive(address);
            Check.that("and a person can revive it", !system.registry().quarantined(address));
        }
    }

    // ---- resumed ---------------------------------------------------------

    private static void resumes() throws Exception {
        var root = root();
        var resumed = new AtomicInteger();
        var address = Address.of("waking", "1");
        Definition<Long> waking = new Definition<>() {

            @Override
            public String type() {
                return "waking";
            }

            @Override
            public Application<Long> instantiate(Address self) {
                return Application.of(0L)
                        .on("add", (state, message) -> Step.of(state + 1))
                        .on("actors.resumed", (state, message) -> {
                            resumed.incrementAndGet();
                            return Step.of(state);
                        });
            }

            @Override
            public Json inspect(Long state) {
                return Json.of(state);
            }
        };

        try (var system = ActorSystem.named("test").rooted(root).define(waking)) {
            system.tell(address, Message.of("add"));
            settle();
            Check.equal("actors.resumed arrives once when the actor loads", 1, resumed.get());
            Check.that("and it is not written to the log",
                    system.history(address, 0, 100).noneMatch(m -> m.type().startsWith("actors.")));
            system.evict(address);
            settle();
            system.tell(address, Message.of("add"));
            settle();
            Check.equal("and again after it is summoned a second time", 2, resumed.get());
            Check.equal("the state came from the log, not from the resume", 2.0,
                    number(system.inspect(address)));
        }
    }

    // ---- time travel -----------------------------------------------------

    private static void travels() throws Exception {
        var root = root();
        var address = Address.of("counter", "1");
        try (var system = ActorSystem.named("test").rooted(root).define(new Counting(1))) {
            for (var step = 1; step <= 5; step++) system.tell(address, Message.of("add").with("by", Json.of(1)));
            settle();
            Check.equal("the state now", 5.0, total(system, address));
            Check.equal("the state after two commands", 2.0,
                    number(field(system.inspectAt(address, 2), "total")));
            Check.equal("the state before any command", 0.0,
                    number(field(system.inspectAt(address, 0), "total")));
            Check.equal("and the running actor was not disturbed", 5.0, total(system, address));
        }
    }

    // ---- fleets ----------------------------------------------------------

    private static void fans() throws Exception {
        try (var system = ActorSystem.named("test").define(new Counting(1), Spawn.ephemeral())) {
            var ids = IntStream.rangeClosed(1, 100).mapToObj(String::valueOf).toList();
            var answers = system.fleet("counter")
                    .over(ids)
                    .concurrency(32)
                    .tell(address -> Message.of("add").with("by", Json.of(2)))
                    .ask(address -> Message.of("total"))
                    .toList();

            Check.equal("a fleet answers for every actor it named", 100, answers.size());
            Check.that("every one of them answered",
                    answers.stream().allMatch(Fleet.Reply::answered));
            Check.equal("and the totals add up", 200.0,
                    answers.stream().mapToDouble(reply -> reply.number("value")).sum());
            Check.equal("answers come back in the order the addresses were named",
                    ids, answers.stream().map(reply -> reply.address().id()).toList());
        }
    }

    // ---- two systems -----------------------------------------------------

    private static void crossesSystems() throws Exception {
        try (var orders = ActorSystem.named("orders").define(new Counting(1), Spawn.ephemeral());
                var billing = ActorSystem.named("billing").define(new Counting(1), Spawn.ephemeral())) {
            Loopback.of(orders, billing);

            var there = Address.at("billing", "counter", "7");
            orders.tell(there, Message.of("add").with("by", Json.of(4)));
            settle();
            Check.equal("a message addressed to another system arrives there",
                    4.0, total(billing, Address.of("counter", "7")));

            var answer = orders.ask(there, Message.of("total"), Duration.ofSeconds(2)).get();
            Check.equal("and an ask across systems comes back", 4.0, number(answer.get("value")));
        }
    }

    // ---- bounded steps ---------------------------------------------------

    /// An effect that never finishes must not wedge the loop, and it must not
    /// be able to emit into it afterwards.
    private static void boundedSteps() throws Exception {
        // The handler ignores interruption, which is how a call into native
        // code behaves. An interruptible handler would throw the moment
        // shutdownNow ran and emit before the fence closed, so it would never
        // test the fence at all.
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        var app = Application.<Integer>of(0)
                .patience(Duration.ofMillis(100))
                .on("start", (state, message) -> Step.of(state, Effect.of("hang")))
                .on("late", (state, message) -> Step.of(state + 1))
                .effect("hang", (effect, emit) -> {
                    while (!released.get()) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {
                            // an uninterruptible call keeps going
                        }
                    }
                    emit.emit(Message.of("late"));
                });

        var began = System.currentTimeMillis();
        app.dispatch(Message.of("start"));
        var took = System.currentTimeMillis() - began;

        Check.that("the step stops waiting for an effect that hangs", took < 2_000);
        Check.equal("and the effect it gave up on is counted", 1L, app.abandoned());
        Check.equal("the state did not move, because nothing was emitted in time", 0, app.state());

        released.set(true);
        Thread.sleep(300);
        Check.equal("a message from the abandoned effect is fenced out", 1L, app.fenced());
        Check.equal("and never reaches the state", 0, app.state());
    }

    // ---- helpers ---------------------------------------------------------

    private static Definition<Long> definitionOf(String type) {
        return new Definition<>() {

            @Override
            public String type() {
                return type;
            }

            @Override
            public Application<Long> instantiate(Address self) {
                return Application.of(0L);
            }
        };
    }

    private static double total(ActorSystem system, Address address) {
        return number(field(system.inspect(address), "total"));
    }

    private static Json field(Json value, String name) {
        return value instanceof Json.Object object ? object.get(name) : value;
    }

    private static String string(Json value) {
        return value instanceof Json.Str(var text) ? text : "";
    }

    private static double number(Json value) {
        return value instanceof Json.Num(var number) ? number : Double.NaN;
    }

    /// Waits for the actors to go quiet.
    ///
    /// A mailbox is drained by another thread, so a test that reads a state
    /// immediately after sending a message reads it too early. There is no
    /// barrier to wait on, because an actor never says it is idle, so this
    /// waits a fixed and generous moment.
    private static void settle() throws InterruptedException {
        Thread.sleep(250);
    }

    private static List<String> names(List<Message> messages) {
        var names = new ArrayList<String>();
        messages.forEach(message -> names.add(message.type()));
        return names;
    }
}
