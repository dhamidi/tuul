package web.cable;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import eventstream.Event;
import eventstream.EventStream;
import eventstream.Signal;
import harness.Check;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import web.Handler;
import web.Headers;
import web.Request;
import web.serve.Memory;
import web.ui.Tags;
import web.ui.Turbo;

public final class CableTest {

    /// Long enough that a virtual thread has certainly run, short enough that a
    /// broken test fails rather than hangs.
    private static final Duration PATIENCE = Duration.ofSeconds(2);

    private CableTest() {}

    public static void run() throws Exception {
        delivers();
        separates();
        heartbeats();
        departures();
        overruns();
        replays();
        gaps();
        shutdown();
        topics();
        markup();
    }

    private static void delivers() throws Exception {
        try (var cable = Cable.of(quick())) {
            var handler = cable.stream(Topics.fixed("symbols"));
            try (var page = listen(handler, "/updates")) {
                Check.equal("a stream answers 200", 200, page.status());
                Check.equal("and says what it is",
                        "text/event-stream; charset=utf-8", page.header("Content-Type"));
                await(() -> cable.subscribers() == 1);
                Check.equal("a page that connected is a subscriber", 1, cable.subscribers("symbols"));

                cable.broadcast("symbols", Turbo.append("results", Tags.div(Tags.text("json.Json"))));
                var event = page.event();
                Check.equal("what arrives is the Turbo Stream element, ready to apply",
                        "<turbo-stream action=\"append\" target=\"results\">"
                                + "<template><div>json.Json</div></template></turbo-stream>",
                        event.data());
                Check.equal("as a message, which is the event Turbo applies", Event.MESSAGE, event.type());
                Check.that("carrying an id, so a client can say where it got to", !event.id().isEmpty());

                cable.broadcast("symbols", Event.of("count", "1"));
                Check.equal("an event of another type is delivered as itself", "count", page.event().type());
            }
        }
    }

    private static void separates() throws Exception {
        try (var cable = Cable.of(quick())) {
            var handler = cable.stream(Topics.query("topic"));
            try (var symbols = listen(handler, "/updates?topic=symbols");
                    var builds = listen(handler, "/updates?topic=builds");
                    var both = listen(handler, "/updates?topic=symbols&topic=builds")) {
                await(() -> cable.subscribers() == 3);

                cable.broadcast("symbols", Event.of("a symbol"));
                Check.equal("a broadcast reaches the topic's subscribers", "a symbol", symbols.event().data());
                Check.equal("and one listening to several topics hears it too", "a symbol", both.event().data());
                Check.that("a subscriber to another topic hears nothing", builds.silent());

                cable.broadcast("builds", Event.of("a build"));
                Check.equal("and the other way round", "a build", builds.event().data());
                Check.equal("one connection carries both topics, which is why it is one",
                        "a build", both.event().data());
            }
        }
    }

    /// A heartbeat is a comment, and a comment is not a signal — the parser
    /// drops it by design, so this is the one thing that has to be read as
    /// text.
    private static void heartbeats() throws Exception {
        try (var cable = Cable.of(quick().heartbeat(Duration.ofMillis(50)))) {
            try (var page = listen(cable.stream(Topics.fixed("symbols")), "/updates")) {
                Check.that("silence is filled with a comment, or a proxy closes the connection",
                        page.await(line -> line.startsWith(":")));
            }
        }
    }

    private static void departures() throws Exception {
        try (var cable = Cable.of(quick())) {
            var handler = cable.stream(Topics.fixed("symbols"));
            var staying = listen(handler, "/updates");
            var leaving = listen(handler, "/updates");
            await(() -> cable.subscribers() == 2);

            leaving.close();
            cable.broadcast("symbols", Event.of("after the client left"));
            await(() -> cable.subscribers() == 1);
            Check.equal("a client that goes away stops being a subscriber", 1, cable.subscribers());
            Check.equal("and the ones still there are unaffected",
                    "after the client left", staying.event().data());
            staying.close();
        }
    }

    /// The queue is what keeps a broadcast from waiting on a socket, and its
    /// bound is what keeps a client that never drains it from being remembered
    /// for ever.
    private static void overruns() {
        var subscription = new Subscription(List.of("symbols"), new StringWriter(), "run", 1);
        Check.that("the first event is taken", subscription.offer(delivery(1)));
        Check.that("the one that will not fit is refused rather than waited on", !subscription.offer(delivery(2)));
        Check.that("and the client that could not keep up is dropped", !subscription.open());
        Check.that("which is remembered, because it is not the same as leaving", subscription.overrun());
        Check.that("nothing more is offered to it", !subscription.offer(delivery(3)));
    }

    private static void replays() throws Exception {
        try (var cable = Cable.of(quick())) {
            var handler = cable.stream(Topics.fixed("symbols"));
            String first;
            try (var page = listen(handler, "/updates")) {
                await(() -> cable.subscribers() == 1);
                cable.broadcast("symbols", Event.of("one"));
                first = page.event().id();
                cable.broadcast("symbols", Event.of("two"));
                page.event();
            }
            await(() -> cable.subscribers() == 0);
            cable.broadcast("symbols", Event.of("three"));

            try (var back = listen(handler, "/updates", first)) {
                Check.equal("a client that reconnects is told what it missed", "two", back.event().data());
                Check.equal("all of it, in the order it happened", "three", back.event().data());
                cable.broadcast("symbols", Event.of("four"));
                Check.equal("and then carries on live", "four", back.event().data());
            }
            try (var fresh = listen(handler, "/updates")) {
                await(() -> cable.subscribers() == 1);
                cable.broadcast("symbols", Event.of("five"));
                Check.equal("a client with no id is told nothing it did not ask for",
                        "five", fresh.event().data());
            }
        }
    }

    private static void gaps() throws Exception {
        var refresh = Turbo.refresh().markup();
        try (var cable = Cable.of(quick().backlog(1))) {
            var handler = cable.stream(Topics.fixed("symbols"));
            String first;
            try (var page = listen(handler, "/updates")) {
                await(() -> cable.subscribers() == 1);
                cable.broadcast("symbols", Event.of("one"));
                first = page.event().id();
            }
            cable.broadcast("symbols", Event.of("two"));
            cable.broadcast("symbols", Event.of("three"));

            try (var back = listen(handler, "/updates", first)) {
                Check.equal("a client too far behind to catch up is asked to refresh itself",
                        refresh, back.event().data());
            }
            try (var other = listen(handler, "/updates", "beefcafe-1")) {
                Check.equal("and so is one holding an id from a server that has restarted",
                        refresh, other.event().data());
            }
        }
        try (var cable = Cable.of(quick().backlog(1).refreshOnGap(false))) {
            var handler = cable.stream(Topics.fixed("symbols"));
            cable.broadcast("symbols", Event.of("one"));
            cable.broadcast("symbols", Event.of("two"));
            try (var back = listen(handler, "/updates", "beefcafe-1")) {
                await(() -> cable.subscribers() == 1);
                cable.broadcast("symbols", Event.of("three"));
                Check.equal("unless the application would rather it did not", "three", back.event().data());
            }
        }
    }

    private static void shutdown() throws Exception {
        var cable = Cable.of(quick());
        try (var page = listen(cable.stream(Topics.fixed("symbols")), "/updates")) {
            await(() -> cable.subscribers() == 1);
            cable.close();
            Check.equal("closing ends every subscription", 0, cable.subscribers());
            Check.that("and the client's stream ends with it", page.ended());
            Check.throwing("a cable that is closed says so rather than dropping a broadcast",
                    () -> cable.broadcast("symbols", Event.of("too late")));
        }
    }

    private static void topics() throws Exception {
        try (var cable = Cable.of(quick())) {
            var answered = Memory.handle(cable.stream(Topics.query("topic")), Memory.get("/updates"));
            Check.equal("a request that asks for no topic is not a stream", 404, answered.status());
            Check.equal("and nothing is subscribed to nothing", 0, cable.subscribers());

            Check.equal("fixed topics ignore what the client asked for",
                    List.of("symbols"), Topics.fixed("symbols").of(Memory.get("/updates?topic=secrets")));
            Check.equal("query topics are exactly what it asked for",
                    List.of("a", "b"), Topics.query("topic").of(Memory.get("/updates?topic=a&topic=b")));
        }
    }

    /// What the feature puts in every page's body.
    ///
    /// Rendered through [web.Features] rather than by calling the element
    /// directly, because that is the only way an application gets one: the
    /// element is this package's and a page that rendered a second would have
    /// two with the same id. Going through the wiring also proves the URL comes
    /// from the composed route table rather than from a string somebody typed.
    private static void markup() throws Exception {
        String source;
        try (var cable = Cable.of()) {
            var wiring = web.Features.of(web.dispatch.Router.of(), cable.feature(Topics.fixed("symbols")));
            var written = new StringWriter();
            wiring.body().write(written);
            source = written.toString();
        }
        Check.equal("the element a page renders to start listening",
                "<div id=\"cable-stream-source\" data-turbo-permanent"
                        + " data-controller=\"cable-stream\" data-cable-stream-url-value=\"/updates\"></div>",
                source);
        Check.that("and it is the feature that puts it there, not the application",
                java.util.Arrays.stream(Cable.class.getDeclaredMethods())
                        .noneMatch(method -> method.getName().equals("source")
                                && java.lang.reflect.Modifier.isPublic(method.getModifiers())));
        Check.that("it is permanent, so a Turbo navigation does not reconnect it",
                source.contains("data-turbo-permanent"));
        Check.equal("and the identifier it writes is the one an application registers",
                "cable-stream", Cable.CONTROLLER);
    }

    private static Settings quick() {
        return Settings.standard().heartbeat(Duration.ofMillis(500));
    }

    private static Delivery delivery(long sequence) {
        return new Delivery(sequence, "symbols", Event.of("event " + sequence));
    }

    private static Listener listen(Handler handler, String path) throws IOException {
        return listen(handler, path, "");
    }

    private static Listener listen(Handler handler, String path, String lastEvent) throws IOException {
        var headers = lastEvent.isEmpty() ? Headers.NONE : Headers.of("Last-Event-ID", lastEvent);
        return new Listener(handler, Request.of("GET", path, headers, Request.body("")));
    }

    /// Waits for something a virtual thread is about to make true.
    private static void await(java.util.function.BooleanSupplier settled) throws InterruptedException {
        var deadline = System.nanoTime() + PATIENCE.toNanos();
        while (!settled.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    }

    /// A page with the stream open, reading it as it arrives.
    ///
    /// The reading thread takes one pass over the lines and does both things
    /// with them: keeps them, and gathers them into signals. Everything worth
    /// asserting is a signal; the raw lines are here for the heartbeat, which
    /// by definition is not one.
    private static final class Listener implements AutoCloseable {

        private final Memory.Open open;
        private final BlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        private final List<String> lines = new ArrayList<>();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final Thread reading;

        private Listener(Handler handler, Request request) throws IOException {
            open = Memory.open(handler, request);
            reading = Thread.ofVirtual().name("listener").start(() -> {
                try (var reader = new BufferedReader(open.reader())) {
                    reader.lines().peek(this::record).gather(EventStream.signals()).forEach(signals::add);
                } catch (IOException | RuntimeException ended) {
                    // the stream closed, which is one of the things being tested
                } finally {
                    finished.countDown();
                }
            });
        }

        private void record(String line) {
            synchronized (lines) {
                lines.add(line);
            }
        }

        private int status() throws InterruptedException {
            return open.status();
        }

        private String header(String name) throws InterruptedException {
            return open.headers().first(name).orElse("");
        }

        /// The next event, or a failure that names what was waited for rather
        /// than a test that hangs.
        private Event event() throws InterruptedException {
            var signal = signals.poll(PATIENCE.toMillis(), MILLISECONDS);
            if (signal instanceof Event event) return event;
            return Event.of("nothing arrived: " + Optional.ofNullable(signal));
        }

        private boolean silent() throws InterruptedException {
            return signals.poll(200, MILLISECONDS) == null;
        }

        private boolean await(java.util.function.Predicate<String> wanted) throws InterruptedException {
            var deadline = System.nanoTime() + PATIENCE.toNanos();
            while (System.nanoTime() < deadline) {
                synchronized (lines) {
                    if (lines.stream().anyMatch(wanted)) return true;
                }
                Thread.sleep(5);
            }
            return false;
        }

        private boolean ended() throws InterruptedException {
            return finished.await(PATIENCE.toMillis(), MILLISECONDS);
        }

        @Override
        public void close() {
            open.close();
            reading.interrupt();
        }
    }
}
