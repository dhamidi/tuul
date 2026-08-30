package project;

import actors.ActorSystem;
import actors.Spawn;
import harness.Check;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class AddTest {
    private AddTest() {}

    public static void run() throws Exception {
        var coordinates = List.of(
                "com.acme.one:library:1.0",
                "com.acme.two:library:1.0",
                "com.acme.three:library:1.0",
                "com.acme.four:library:1.0",
                "com.acme.five:library:1.0",
                "com.acme.six:library:1.0",
                "com.acme.seven:library:1.0");
        var calls = new CopyOnWriteArrayList<String>();
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var allFirstBatchStarted = new CountDownLatch(20);
        var releaseFirstBatch = new CountDownLatch(1);
        var services = new Add.Services() {
            @Override
            public Add.Resolved resolve(List<String> coordinate) {
                return new Add.Resolved(coordinates, List.of());
            }

            @Override
            public Add.Download download(String coordinate, String kind, Consumer<Add.Event> events) throws Exception {
                var running = active.incrementAndGet();
                maximum.accumulateAndGet(running, Math::max);
                allFirstBatchStarted.countDown();
                if (allFirstBatchStarted.getCount() == 0) releaseFirstBatch.countDown();
                releaseFirstBatch.await();
                try {
                    calls.add(coordinate + ":" + kind);
                    var label = kind.equals("binary") ? coordinate : coordinate + ":" + kind;
                    events.accept(new Add.Event("done", label, 1, 1, "memory/" + kind, ""));
                    return Add.Download.downloaded("memory/" + kind);
                } finally {
                    active.decrementAndGet();
                }
            }
        };

        var output = new StringWriter();
        var result = Add.into(List.of("com.acme.root:root:1.0"), output, Add.Mode.EVENTS, services);
        Check.that("in-memory add downloads every artifact", result.ok() && result.downloaded().size() == 21);
        Check.equal("in-memory downloader sees every scheduled artifact", 21, calls.size());
        Check.equal("download queue never exceeds twenty active transfers", 20, maximum.get());
        Check.that("download queue is capped at twenty", Add.batches(21).equals(List.of(20, 1)));
        Check.that("download queue keeps exact twenty-sized batches", Add.batches(40).equals(List.of(20, 20)));

        var cachedCalls = new CopyOnWriteArrayList<String>();
        var cachedServices = new Add.Services() {
            @Override
            public Add.Resolved resolve(List<String> coordinate) {
                return new Add.Resolved(List.of("com.acme.cached:library:1.0"), List.of());
            }

            @Override
            public Add.Download download(String coordinate, String kind, Consumer<Add.Event> events) {
                cachedCalls.add(coordinate + ":" + kind);
                var label = kind.equals("binary") ? coordinate : coordinate + ":" + kind;
                events.accept(new Add.Event("cached", label, 0, 0, "memory/" + kind, ""));
                return Add.Download.cached("memory/" + kind);
            }
        };
        var cachedOutput = new StringWriter();
        var cached = Add.into(List.of("com.acme.root:root:1.0"), cachedOutput, Add.Mode.EVENTS, cachedServices);
        Check.that("in-memory cache path returns cache hits",
                cached.ok() && cached.cached().size() == 3 && cachedCalls.size() == 3);
        Check.that("cache path remains observable", cachedOutput.toString().contains("add.cached"));

        var ttyOutput = new StringWriter();
        var progress = new Progress(ttyOutput, Add.Mode.TTY);
        try (var system = ActorSystem.named("test-progress")
                .define(progress, Spawn.ephemeral().mailbox(32))) {
            progress.attach(system, progress.at("run"));
            system.effect(Progress.RENDER, progress::render);
            system.effect(Progress.CLOSE, progress::closeOutput);
            progress.publish(new Add.Event("start", "com.acme:library:1.0", 0, 100, "", ""));
            progress.publish(new Add.Event("done", "com.acme:library:1.0", 100, 100,
                    "memory/library.jar", ""));
            progress.close();
        }
        Check.that("TTY progress owns terminal rendering", ttyOutput.toString().contains("\033[")
                && ttyOutput.toString().contains("done") && !ttyOutput.toString().contains("add.done"));
    }
}
