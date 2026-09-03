package reload;

import harness.Check;
import actors.ActorSystem;
import actors.Address;
import actors.Behavior;
import actors.Definition;
import actors.MessageType;
import actors.Spawn;
import application.Message;
import application.Step;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import web.Responses;
import web.serve.Memory;

/// Fast, in-process checks for generation activation and draining.
public final class ReloadTest {

    private ReloadTest() {}

    public static void run() throws Exception {
        servesLatestGeneration();
        rejectsWithoutChangingTheActiveGeneration();
        drainsBeforeClosingResources();
        reloadsAnEphemeralActorAtATypeBoundary();
        preflightDoesNotMutateLoadedActors();
        reportsCandidateWithoutCallingItActive();
        removesOmittedActorDefinitions();
        closesStableEntrypoints();
    }

    private static void servesLatestGeneration() {
        var reload = new Reload();
        Check.equal("before the first activation the host is unavailable", 503,
                Memory.handle(reload.handler(), Memory.get("/")).status());
        reload.submit(Revision.of("one", () -> Generation.of((request, response) -> Responses.text("one", response))));
        Check.equal("the first generation answers", "one",
                Memory.handle(reload.handler(), Memory.get("/")).text());
        reload.submit(Revision.of("two", () -> Generation.of((request, response) -> Responses.text("two", response))));
        Check.equal("the next request leases the new generation", "two",
                Memory.handle(reload.handler(), Memory.get("/")).text());
        Check.equal("two revisions were activated", 2L, reload.status().activated());
        reload.close();
    }

    private static void rejectsWithoutChangingTheActiveGeneration() {
        var reload = new Reload();
        reload.submit(Revision.of("good", () -> Generation.of((request, response) -> Responses.text("good", response))));
        reload.validate(candidate -> List.of(new Problem("validate", "the candidate is intentionally bad")));
        var status = reload.submit(Revision.of("bad", () -> Generation.of((request, response) -> Responses.text("bad", response))));
        Check.equal("the active revision remains after rejection", "good", status.activeRevision());
        Check.equal("the rejected revision is recorded", "bad", status.rejectedRevision());
        Check.equal("a rejected revision is no longer a candidate", "", status.candidateRevision());
        Check.equal("the last good handler remains active", "good",
                Memory.handle(reload.handler(), Memory.get("/")).text());
        Check.equal("one candidate was rejected", 1L, status.rejected());
        reload.close();
    }

    private static void drainsBeforeClosingResources() throws Exception {
        var closed = new AtomicInteger();
        var reload = new Reload();
        var first = Generation.of((request, response) -> {
                    var stream = Responses.events(response);
                    try {
                        Thread.sleep(java.time.Duration.ofDays(1));
                    } finally {
                        stream.close();
                    }
                })
                .closing(closed::incrementAndGet);
        reload.submit(Revision.of("stream", () -> first));
        var open = Memory.open(reload.handler(), Memory.get("/"));
        open.status();
        reload.submit(Revision.of("new", Generation::empty));
        Check.equal("the leased generation remains open", 0, closed.get());
        open.close();
        for (var until = System.nanoTime() + 1_000_000_000L; closed.get() == 0 && System.nanoTime() < until;) {
            Thread.yield();
        }
        Check.equal("the generation closes after its lease drains", 1, closed.get());
        reload.close();
    }

    private static void reloadsAnEphemeralActorAtATypeBoundary() throws Exception {
        var system = ActorSystem.named("reload-actors");
        var address = Address.of("counter", "one");
        var increment = MessageType.command("increment");
        var value = MessageType.query("value");
        Definition<Integer> first = new Counter(0, increment, value);
        Definition<Integer> second = new Counter(100, increment, value);
        system.define(first, Spawn.ephemeral());
        system.tell(address, increment.message());
        Check.equal("the initial actor handles its message", 1.0,
                system.ask(address, value.message()).get().number("value", -1));
        var result = system.reload(java.util.Map.of("counter", second),
                java.util.Map.of("counter", Spawn.ephemeral()), java.util.Map.of(),
                java.util.Map.of("counter", StatePolicy.RESTART));
        Check.that("actor reload is awaited and active", result.activated());
        Check.equal("restart policy gives the new actor initial state", 100.0,
                system.ask(address, value.message()).get().number("value", -1));
        system.close();
    }

    private static void reportsCandidateWithoutCallingItActive() {
        var reload = new Reload();
        reload.validate(candidate -> {
            Check.that("a candidate is not reported as active", !reload.status().active());
            Check.equal("validation reports the candidate identity", "candidate",
                    reload.status().candidateRevision());
            return List.of();
        });
        reload.submit(Revision.of("candidate", Generation::empty));
        reload.close();
    }

    private static void removesOmittedActorDefinitions() throws Exception {
        var system = ActorSystem.named("reload-removal");
        var reload = new Reload();
        var command = MessageType.command("increment");
        var query = MessageType.query("value");
        reload.submit(Revision.of("actors", () -> Generation.empty()
                .actor(system, new Counter(0, command, query), Spawn.ephemeral(), StatePolicy.RESTART)));
        Check.that("the generation installs its actor type",
                system.messageTypes(Address.of("counter", "unused")).stream()
                        .anyMatch(type -> type.type().equals("increment")));
        reload.submit(Revision.of("empty", Generation::empty));
        Check.equal("an omitted actor type is removed", actors.DeliveryStatus.unknown,
                system.tell(Address.of("counter", "after"), command.message()));
        reload.close();
        system.close();
    }

    private static void closesStableEntrypoints() {
        var reload = new Reload(Generation::empty);
        reload.close();
        Check.equal("the stable web handler is unavailable after close", 503,
                Memory.handle(reload.handler(), Memory.get("/")).status());
        Check.equal("close is visible in status", "closed", reload.status().phase());
    }

    private static void preflightDoesNotMutateLoadedActors() throws Exception {
        var system = ActorSystem.named("reload-preflight");
        var address = Address.of("counter", "one");
        var increment = MessageType.command("increment");
        var value = MessageType.query("value");
        system.define(new Counter(0, increment, value), Spawn.ephemeral());
        system.tell(address, increment.message());
        Check.equal("preflight sees the currently loaded state", 1.0,
                system.ask(address, value.message()).get().number("value", -1));
        var candidate = new Counter(100, increment, value);
        java.util.Map<String, Definition<?>> definitions = java.util.Map.of("counter", candidate);
        var spawns = java.util.Map.of("counter", Spawn.ephemeral());
        var refused = system.preflight(definitions, spawns, java.util.Map.of());
        Check.that("default preflight refuses loaded ephemeral state", refused.stream()
                .anyMatch(problem -> problem.contains("loaded ephemeral")));
        Check.equal("preflight does not change the generation", 0L, system.generation());
        Check.equal("preflight does not change the actor", 1.0,
                system.ask(address, value.message()).get().number("value", -1));
        Check.equal("restart preflight accepts the same candidate", 0,
                system.preflight(definitions, spawns, java.util.Map.of(),
                        java.util.Map.of("counter", StatePolicy.RESTART)).size());
        system.close();
    }

    private static final class Counter implements Definition<Integer> {
        private final int initial;
        private final MessageType increment;
        private final MessageType value;

        private Counter(int initial, MessageType increment, MessageType value) {
            this.initial = initial;
            this.increment = increment;
            this.value = value;
        }

        @Override public String type() { return "counter"; }

        @Override public Behavior<Integer> instantiate(Address self) {
            return Behavior.of(initial)
                    .on(increment, (state, ignored) -> Step.of(state + 1))
                    .on(value, (state, ignored) -> Step.of(state,
                            actors.ActorEffect.reply(Message.of("value").with("value", state))));
        }
    }
}
