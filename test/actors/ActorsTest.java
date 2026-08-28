package actors;

import application.Application;
import application.Effect;
import application.Message;
import application.Step;
import actors.transport.Loopback;
import harness.Check;
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
                            Effect.of(System.REPLY).with("message",
                                    Message.of("total").with("value", Json.of(state.total())).body())));
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
        try (var system = System.named("test").rooted(root).define(new Counting(1))) {
            system.tell(address, Message.of("add").with("by", Json.of(3)));
            system.tell(address, Message.of("add").with("by", Json.of(4)));
            settle();
            Check.equal("the state is the fold of the commands", 7.0, total(system, address));
            system.evict(address);
            settle();
            Check.equal("an evicted actor comes back with the same state", 7.0, total(system, address));
        }

        try (var system = System.named("test").rooted(root).define(new Counting(1))) {
            Check.equal("and comes back in a new system too, from the log alone",
                    7.0, total(system, address));
        }
    }

    /// The upgrade story: the same log, a definition that counts differently,
    /// and a state recomputed by the new rule rather than migrated.
    private static void upgrades() throws Exception {
        var root = root();
        var address = Address.of("counter", "1");
        try (var system = System.named("test").rooted(root).define(new Counting(1))) {
            system.tell(address, Message.of("add").with("by", Json.of(5)));
            settle();
            Check.equal("the first definition counts once", 5.0, total(system, address));
        }
        try (var system = System.named("test").rooted(root).define(new Counting(10))) {
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

        try (var system = System.named("test").rooted(root).define(noisy)
                .effect("record", (effect, emit) -> ran.add("ran"))) {
            system.tell(address, Message.of("shout"));
            system.tell(address, Message.of("shout"));
            settle();
            Check.equal("the effect ran once per message while live", 2, ran.size());
        }

        try (var system = System.named("test").rooted(root).define(noisy)
                .effect("record", (effect, emit) -> ran.add("ran"))) {
            Check.equal("replay rebuilt the state", 2.0, number(system.inspect(address)));
            Check.equal("and ran no effect while doing it", 2, ran.size());
        }
    }

    /// An undurable actor keeps no log at all.
    private static void forgets() throws Exception {
        var root = root();
        var address = Address.of("counter", "1");
        try (var system = System.named("test").rooted(root)
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
        try (var system = System.named("test").define(new Counting(1), Spawn.ephemeral())) {
            var address = Address.of("counter", "1");
            system.tell(address, Message.of("add").with("by", Json.of(2)));
            var answer = system.ask(address, Message.of("total"), Duration.ofSeconds(2)).get();
            Check.equal("an ask comes back with the answer", 2.0, number(answer.get("value")));
        }

        Definition<Long> silent = definitionOf("silent");
        try (var system = System.named("test").define(silent, Spawn.ephemeral())) {
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
                        .on("go", (state, message) -> Step.of(state, Effect.of(System.TELL)
                                .with("to", Address.parse(message.string("to", "")).json())
                                .with("message", Message.of("hello").body())))
                        .on(Undeliverable.TYPE, (state, message) -> {
                            notices.add(message);
                            return Step.of(state);
                        });
            }
        };

        try (var system = System.named("test").define(sender, Spawn.ephemeral())) {
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
        try (var system = System.named("test").define(sender, Spawn.ephemeral())) {
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
        try (var system = System.named("test")
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

        try (var system = System.named("test").rooted(root).define(brittle)) {
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

        try (var system = System.named("test").rooted(root).define(brittle)) {
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
        try (var system = System.named("test").define(fragile, Spawn.ephemeral())) {
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
        try (var system = System.named("test").rooted(root)
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

        try (var system = System.named("test")
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

        try (var system = System.named("test").rooted(root).define(waking)) {
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
        try (var system = System.named("test").rooted(root).define(new Counting(1))) {
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
        try (var system = System.named("test").define(new Counting(1), Spawn.ephemeral())) {
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
        try (var orders = System.named("orders").define(new Counting(1), Spawn.ephemeral());
                var billing = System.named("billing").define(new Counting(1), Spawn.ephemeral())) {
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

        var began = java.lang.System.currentTimeMillis();
        app.dispatch(Message.of("start"));
        var took = java.lang.System.currentTimeMillis() - began;

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

    private static double total(System system, Address address) {
        return number(field(system.inspect(address), "total"));
    }

    private static Json field(Json value, String name) {
        return value instanceof Json.Object object ? object.get(name) : value;
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
