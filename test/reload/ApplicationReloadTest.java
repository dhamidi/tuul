package reload;

import application.Application;
import application.Effect;
import application.Message;
import application.Step;
import harness.Check;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import json.Json;

/// Fast in-memory checks for named application generations.
public final class ApplicationReloadTest {

    private ApplicationReloadTest() {}

    public static void run() throws Exception {
        restartSwapsDefinitionAndEffects();
        transferCarriesVersionedJsonState();
        refuseLeavesTheOldApplicationActive();
        emptyGenerationRemovesApplications();
        turnsAreSerializedPerName();
        transferWaitsForAnInFlightTurn();
    }

    private static void restartSwapsDefinitionAndEffects() throws Exception {
        var oldEffects = new AtomicInteger();
        var newEffects = new AtomicInteger();
        var reload = new Reload();
        reload.submit(Revision.of("one", () -> Generation.empty().application(
                definition("counter", "one", 0, oldEffects))));
        reload.applications().dispatch("counter", Message.of("add"));
        reload.submit(Revision.of("two", () -> Generation.empty().application(
                definition("counter", "two", 100, newEffects), StatePolicy.RESTART)));
        reload.applications().dispatch("counter", Message.of("add"));
        Check.equal("restart uses the replacement initial state", 101.0,
                reload.applications().state("counter").value().number("count", -1));
        Check.equal("the old effect handler ran once", 1, oldEffects.get());
        Check.equal("the new effect handler ran once", 1, newEffects.get());
        reload.close();
    }

    private static void transferCarriesVersionedJsonState() throws Exception {
        var reload = new Reload();
        var first = definition("counter", "one", 3, new AtomicInteger())
                .withTransfer(state -> Json.Object.of("count", state),
                        (version, state) -> app((int) state.number("count", -1)));
        reload.submit(Revision.of("one", () -> Generation.empty().application(first)));
        reload.applications().dispatch("counter", Message.of("add"));

        var seenVersion = new ArrayList<String>();
        var replacement = ApplicationDefinition.of("counter", "two", () -> app(0))
                .withTransfer((Integer state) -> Json.Object.of("count", state),
                        (version, state) -> {
                            seenVersion.add(version);
                            return app((int) state.number("count", -1));
                        });
        var status = reload.submit(Revision.of("two", () -> Generation.empty()
                .application(replacement, StatePolicy.TRANSFER)));
        Check.equal("transfer activates", "two", status.activeRevision());
        Check.equal("transfer receives the old schema version", List.of("one"), seenVersion);
        reload.applications().dispatch("counter", Message.of("add"));
        Check.equal("transfer preserves state", 5.0,
                reload.applications().state("counter").value().number("count", -1));
        reload.close();
    }

    private static void refuseLeavesTheOldApplicationActive() throws Exception {
        var reload = new Reload();
        reload.submit(Revision.of("one", () -> Generation.empty().application(
                definition("counter", "one", 7, new AtomicInteger()))));
        var status = reload.submit(Revision.of("two", () -> Generation.empty().application(
                definition("counter", "two", 99, new AtomicInteger()), StatePolicy.REFUSE)));
        Check.equal("refused application keeps the active revision", "one", status.activeRevision());
        Check.equal("refused application keeps its state", 7.0,
                reload.applications().state("counter").value().number("count", -1));
        reload.close();
    }

    private static void turnsAreSerializedPerName() throws Exception {
        var reload = new Reload();
        var definition = ApplicationDefinition.of("counter", "one", () -> app(0))
                .withTransfer(state -> Json.Object.of("count", state),
                        (version, state) -> app((int) state.number("count", -1)));
        reload.submit(Revision.of("one", () -> Generation.empty().application(definition)));
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (var i = 0; i < 100; i++) futures.add(tasks.submit(
                    () -> { reload.applications().dispatch("counter", Message.of("add")); return 0; }));
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        Check.equal("each named application serializes its turns", 100.0,
                reload.applications().state("counter").value().number("count", -1));
        reload.close();
    }

    private static void emptyGenerationRemovesApplications() throws Exception {
        var reload = new Reload();
        reload.submit(Revision.of("one", () -> Generation.empty().application(
                definition("counter", "one", 0, new AtomicInteger()))));
        reload.submit(Revision.of("two", Generation::empty));
        Check.equal("an empty generation removes old applications", List.of(),
                reload.applications().names());
        reload.close();
    }

    private static void transferWaitsForAnInFlightTurn() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var seen = new AtomicInteger(-1);
        var old = ApplicationDefinition.of("counter", "one", () -> app(0)
                .on("block", (state, message) -> {
                    started.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return Step.of(state + 1);
                }))
                .withTransfer(state -> Json.Object.of("count", state),
                        (version, state) -> {
                            seen.set((int) state.number("count", -1));
                            return app(seen.get());
                        });
        var reload = new Reload();
        reload.submit(Revision.of("one", () -> Generation.empty().application(old)));
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var turn = tasks.submit(() -> reload.applications().dispatch("counter", Message.of("block")));
            Check.that("the old turn started", started.await(5, TimeUnit.SECONDS));
            var queued = tasks.submit(() -> reload.applications().dispatch("counter", Message.of("add")));
            var replacement = ApplicationDefinition.of("counter", "two", () -> app(0))
                    .withTransfer(state -> Json.Object.of("count", state),
                            (version, state) -> {
                                seen.set((int) state.number("count", -1));
                                return app(seen.get());
                            });
            var update = tasks.submit(() -> reload.submit(Revision.of("two", () -> Generation.empty()
                    .application(replacement, StatePolicy.TRANSFER))));
            Thread.sleep(20);
            Check.that("activation waits for the old turn", !update.isDone());
            release.countDown();
            turn.get(5, TimeUnit.SECONDS);
            queued.get(5, TimeUnit.SECONDS);
            var activated = update.get(5, TimeUnit.SECONDS);
            Check.that("the transfer snapshots only a completed old turn", seen.get() == 1 || seen.get() == 2);
            Check.equal("the transfer activates after the turn", "two",
                    activated.activeRevision());
            Check.equal("the queued turn is applied exactly once", 2.0,
                    reload.applications().state("counter").value().number("count", -1));
        }
        reload.close();
    }

    private static ApplicationDefinition<Integer> definition(String name, String version,
            int initial, AtomicInteger effects) {
        return ApplicationDefinition.of(name, version, () -> app(initial)
                .effect("mark", (effect, emit) -> effects.incrementAndGet()))
                .withTransfer(state -> Json.Object.of("count", state),
                        (versionValue, state) -> app((int) state.number("count", -1)));
    }

    private static Application<Integer> app(int initial) {
        return Application.of(initial)
                .on("add", (state, message) -> Step.of(state + 1, Effect.of("mark")));
    }
}
