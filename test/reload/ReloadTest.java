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

/// Fast, in-process checks for generation activation and draining.
public final class ReloadTest {

    private ReloadTest() {}

    public static void run() throws Exception {
        leasesLatestGeneration();
        rejectsWithoutChangingTheActiveGeneration();
        rejectsAndClosesCandidateResources();
        drainsBeforeClosingResources();
        reloadsAnEphemeralActorAtATypeBoundary();
        preflightDoesNotMutateLoadedActors();
        reportsCandidateWithoutCallingItActive();
        removesOmittedActorDefinitions();
        closesStableEntrypoints();
    }

    private static void leasesLatestGeneration() {
        var value = Capability.<String>create();
        var reload = new Reload();
        Check.that("before the first activation no generation can be leased", reload.lease().isEmpty());
        reload.submit(Revision.of("one", () -> Generation.empty().with(value, "one")));
        Check.equal("the first generation is leased", "one", capability(reload, value));
        reload.submit(Revision.of("two", () -> Generation.empty().with(value, "two")));
        Check.equal("the next lease uses the new generation", "two", capability(reload, value));
        Check.equal("two revisions were activated", 2L, reload.status().activated());
        reload.close();
    }

    private static void rejectsWithoutChangingTheActiveGeneration() {
        var value = Capability.<String>create();
        var reload = new Reload();
        reload.submit(Revision.of("good", () -> Generation.empty().with(value, "good")));
        reload.validate(candidate -> List.of(new Problem("validate", "the candidate is intentionally bad")));
        var status = reload.submit(Revision.of("bad", () -> Generation.empty().with(value, "bad")));
        Check.equal("the active revision remains after rejection", "good", status.activeRevision());
        Check.equal("the rejected revision is recorded", "bad", status.rejectedRevision());
        Check.equal("a rejected revision is no longer a candidate", "", status.candidateRevision());
        Check.equal("the last good capability remains active", "good", capability(reload, value));
        Check.equal("one candidate was rejected", 1L, status.rejected());
        reload.close();
    }

    private static void drainsBeforeClosingResources() throws Exception {
        var closed = new AtomicInteger();
        var reload = new Reload();
        var first = Generation.empty().closing(closed::incrementAndGet);
        reload.submit(Revision.of("stream", () -> first));
        var open = reload.lease().orElseThrow();
        reload.submit(Revision.of("new", Generation::empty));
        Check.equal("the leased generation remains open", 0, closed.get());
        open.close();
        open.close();
        for (var until = System.nanoTime() + 1_000_000_000L; closed.get() == 0 && System.nanoTime() < until;) {
            Thread.yield();
        }
        Check.equal("the generation closes after its lease drains", 1, closed.get());
        reload.close();
    }

    private static void rejectsAndClosesCandidateResources() {
        var closed = new AtomicInteger();
        var reload = new Reload();
        reload.submit(Revision.of("good", Generation::empty));
        reload.validate(candidate -> List.of(new Problem("validate", "reject")));
        var status = reload.submit(Revision.of("bad", () ->
                Generation.empty().closing(closed::incrementAndGet)));
        Check.equal("a rejected candidate closes its resources", 1, closed.get());
        Check.equal("resource rejection keeps the last good revision", "good", status.activeRevision());
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
        Check.that("a closed coordinator refuses a lease", reload.lease().isEmpty());
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

    private static <T> T capability(Reload reload, Capability<T> capability) {
        try (var lease = reload.lease().orElseThrow()) {
            return lease.generation().capability(capability).orElseThrow();
        }
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
