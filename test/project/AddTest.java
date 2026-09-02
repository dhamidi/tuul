package project;

import actors.ActorSystem;
import actors.Spawn;
import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
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
        var allFirstBatchStarted = new CountDownLatch(MavenTransport.GLOBAL_LIMIT);
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
        Check.equal("download queue obeys the visible global limit", MavenTransport.GLOBAL_LIMIT, maximum.get());
        Check.that("download queue is capped at the global limit", Add.batches(9).equals(List.of(8, 1)));
        Check.that("download queue keeps exact limit-sized batches", Add.batches(16).equals(List.of(8, 8)));

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

        var ttyOutput = new FlushedWriter();
        var progress = new Progress(ttyOutput, Add.Mode.TTY);
        progress.schedule(3);
        progress.publish(new Add.Event("selected", "com.acme:selected:1.0", 0, 0, "", "metadata"));
        progress.publish(new Add.Event("omitted", "com.acme:omitted:1.0", 0, 0, "", "metadata"));
        progress.publish(new Add.Event("start", "com.acme:library:1.0", 0, 100, "", ""));
        for (var bytes = 1; bytes < 100; bytes++) {
            progress.publish(new Add.Event("progress", "com.acme:library:1.0", bytes, 100, "", ""));
        }
        progress.render(null, null);
        var flushesDuringRender = ttyOutput.flushes;
        progress.publish(new Add.Event("done", "com.acme:library:1.0", 100, 100,
                "memory/library.jar", ""));
        progress.publish(new Add.Event("done", "com.acme:library:1.0", 100, 100,
                "memory/library.jar", ""));
        progress.publish(new Add.Event("done", "com.acme:second:1.0", 100, 100,
                "memory/second.jar", ""));
        progress.publish(new Add.Event("done", "com.acme:third:1.0", 100, 100,
                "memory/third.jar", ""));
        progress.publish(new Add.Event("done", "com.acme:third:1.0", 100, 100,
                "memory/third.jar", ""));
        progress.close();
        var ttyText = ttyOutput.toString();
        Check.that("TTY progress flushes a live frame", flushesDuringRender > 0);
        Check.that("TTY progress stays on one bounded line", ttyText.contains("\n")
                && ttyText.chars().filter(character -> character == '\n').count() == 1);
        Check.that("TTY metadata does not create bars", !ttyText.contains("selected") && !ttyText.contains("omitted"));
        Check.that("TTY completion counts each artifact once", ttyText.contains("3/3")
                && ttyText.contains("[####################] 3/3 complete")
                && !ttyText.contains("4/3"));

        var eventsOutput = new FlushedWriter();
        var events = new Progress(eventsOutput, Add.Mode.EVENTS);
        events.publish(new Add.Event("resolve", "com.acme:root:1.0", 0, 0, "", ""));
        events.publish(new Add.Event("selected", "com.acme:library:1.0", 0, 0, "", "metadata"));
        events.publish(new Add.Event("start", "com.acme:library:1.0", 0, 100, "", ""));
        events.publish(new Add.Event("progress", "com.acme:library:1.0", 50, 100, "", ""));
        events.publish(new Add.Event("done", "com.acme:library:1.0", 100, 100,
                "memory/library.jar", ""));
        events.publish(Add.Event.complete(1, 0, 0));
        events.render(null, null);
        events.close();
        var eventText = eventsOutput.toString();
        Check.that("event output drops byte progress without ANSI", !eventText.contains("add.progress")
                && !eventText.contains("\033[") && eventText.contains("add.resolve")
                && eventText.contains("add.start") && eventText.contains("add.done")
                && eventText.contains("add.complete"));
        Check.that("TTY progress restores terminal autowrap", ttyText.contains("\033[?7l")
                && ttyText.contains("\033[?7h"));

        var optionalOutput = new FlushedWriter();
        var optional = new Progress(optionalOutput, Add.Mode.TTY);
        optional.schedule(1);
        optional.publish(new Add.Event("optional-missing", "com.acme:library:1.0:sources", 0, 0, "", "404"));
        optional.close();
        Check.that("expected optional supplements stay out of TTY diagnostics",
                !optionalOutput.toString().contains("optional-missing"));

        var diagnosticOutput = new FlushedWriter();
        var diagnostic = new Progress(diagnosticOutput, Add.Mode.TTY);
        diagnostic.schedule(1);
        for (var warning = 0; warning < 100; warning++) {
            diagnostic.publish(new Add.Event("warning", "com.acme:library:" + warning, 0, 0, "",
                    "checksum unavailable"));
        }
        diagnostic.publish(new Add.Event("failed", "com.acme:broken:1.0", 0, 0, "", "download failed"));
        diagnostic.close();
        Check.that("TTY keeps warnings and real failures visible",
                diagnosticOutput.toString().contains("checksum unavailable")
                        && diagnosticOutput.toString().contains("download failed"));
        Check.that("TTY aggregates repeated warnings", diagnosticOutput.toString().contains("add.warning 100x")
                && diagnosticOutput.toString().indexOf("add.warning 100x")
                        == diagnosticOutput.toString().lastIndexOf("add.warning 100x"));

        var actorOutput = new FlushedWriter();
        var actorProgress = new Progress(actorOutput, Add.Mode.TTY);
        try (var system = ActorSystem.named("test-progress")
                .define(actorProgress, Spawn.ephemeral().mailbox(32))) {
            actorProgress.attach(system, actorProgress.at("run"));
            system.effect(Progress.RENDER, actorProgress::render);
            system.effect(Progress.CLOSE, actorProgress::closeOutput);
            actorProgress.schedule(1);
            actorProgress.publish(new Add.Event("done", "com.acme:library:1.0", 100, 100,
                    "memory/library.jar", ""));
            actorProgress.close();
        }
        Check.that("actor path renders and closes TTY progress",
                actorOutput.toString().contains("1/1") && actorOutput.toString().contains("\033[?7h"));

        var barOutput = new FlushedWriter();
        var bar = new ProgressBar(barOutput);
        bar.close();
        bar.close();
        Check.equal("an unused progress bar writes nothing", "", barOutput.toString());
        bar = new ProgressBar(barOutput);
        bar.render(1, 2, "status\n", "detail\r");
        bar.close();
        bar.close();
        Check.that("progress bar sanitizes text and closes once", barOutput.toString().contains("[##########----------] status  — detail ")
                && barOutput.toString().contains("\033[?7h\r\n")
                && barOutput.toString().indexOf("\033[?7h\r\n")
                        == barOutput.toString().lastIndexOf("\033[?7h\r\n"));
    }

    private static final class FlushedWriter extends Writer {
        private final StringWriter text = new StringWriter();
        private int flushes;

        @Override
        public void write(char[] characters, int offset, int length) {
            text.write(characters, offset, length);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() throws IOException {
            text.close();
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
