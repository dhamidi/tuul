package web.cable;

import eventstream.Event;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import web.Feature;
import web.Handler;
import web.Request;
import web.Responses;
import web.RouteRef;
import web.Status;
import web.assets.Bundled;
import web.ui.Attributes;
import web.ui.Html;
import web.ui.Stimulus;
import web.ui.Turbo;

/// Live updates, pushed to pages that are already open.
///
/// An application broadcasts on a topic and every page listening to it updates
/// itself, with no JavaScript written by the application:
///
/// ```
/// var cable = Cable.of();
/// var routes = Router.of().get(Cable.UPDATES, cable.stream(Topics.fixed("symbols")));
/// cable.broadcast("symbols", Turbo.append("results", row(symbol)));
/// ```
///
/// The transport is an event stream rather than a WebSocket, because
/// `jdk.httpserver` has none and Turbo will connect to an `EventSource` — so
/// the thing being pushed is a Turbo Stream element, and the browser applies it
/// the same way it applies one that arrived in a response.
///
/// A page listens by rendering [#source] once. That element is
/// `data-turbo-permanent`, so a Turbo navigation leaves the connection alone
/// rather than tearing it down and building another — which matters more than
/// it sounds, because a browser allows about six connections to an origin and
/// this design spends one of them for as long as the tab is open. One stream
/// carrying every topic a page cares about is the point; one per widget would
/// exhaust the budget by the fourth widget.
///
/// A cable owns no threads. Delivery happens on the thread the server already
/// gave the handler, which for both bindings is a virtual thread — so the
/// lifetime of every task here is bounded by a block of code somebody can see,
/// and [#close] does not return until every one of them has ended.
public final class Cable implements AutoCloseable {

    /// The directory holding [#FILE], beside this package's code.
    public static final String ASSETS = "assets";

    /// The specifier an application pins for the client half of this, and the
    /// file behind it. Both are here rather than in a document because a
    /// mismatch between them is silent.
    public static final String MODULE = "@tuul/cable-stream";

    public static final String FILE = "cable-stream.js";

    /// The route a page connects to. An application refers to it by this name
    /// and never writes the path, so [#feature(Topics)] can be mounted
    /// anywhere and every link still builds itself.
    public static final RouteRef UPDATES = RouteRef.of("web.cable.updates", "/updates");

    /// The Stimulus identifier the controller is registered under. [#source]
    /// writes it, the application registers it, and neither should have to
    /// guess what the other said.
    public static final String CONTROLLER = "cable-stream";

    /// What a client sends back when it reconnects, and where it says it.
    private static final String LAST_EVENT = "Last-Event-ID";

    /// How long [#close] waits for the handlers to notice. Long enough for a
    /// write in progress to finish, short enough that a server shutting down
    /// still shuts down.
    private static final long SHUTDOWN = TimeUnit.SECONDS.toNanos(5);

    private final Settings settings;
    private final String run = Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
    private final Map<String, Backlog> backlogs = new HashMap<>();
    private final Set<Subscription> subscriptions = new LinkedHashSet<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ended = lock.newCondition();

    private long sequence;
    private boolean closed;

    private Cable(Settings settings) {
        this.settings = settings;
    }

    public static Cable of() {
        return new Cable(Settings.standard());
    }

    public static Cable of(Settings settings) {
        return new Cable(settings);
    }

    public Settings settings() {
        return settings;
    }

    /// The handler a page connects to. It answers with an event stream and does
    /// not return until the client goes away or the cable closes, which is what
    /// an open connection is.
    public Handler stream(Topics topics) {
        return (request, response) -> {
            var wanted = List.copyOf(topics.of(request));
            if (wanted.isEmpty()) {
                Responses.empty(Status.NOT_FOUND, response);
                return;
            }
            var subscription = new Subscription(wanted, Responses.events(response), run, settings.queue());
            var from = join(subscription);
            try {
                catchUp(request, subscription, from);
                subscription.deliver(settings.heartbeat());
            } finally {
                leave(subscription);
            }
        };
    }

    /// Sends a Turbo Stream element to everyone listening to this topic. The
    /// markup is rendered here, once, rather than once per subscriber.
    public void broadcast(String topic, Html stream) {
        broadcast(topic, Event.of(stream.markup()));
    }

    /// Sends any event at all, for the pages that listen with Stimulus rather
    /// than letting Turbo apply it.
    public void broadcast(String topic, Event event) {
        Delivery delivery;
        List<Subscription> listening;
        lock.lock();
        try {
            if (closed) throw new IllegalStateException("this cable is closed");
            delivery = new Delivery(++sequence, topic, event);
            backlogs.computeIfAbsent(topic, name -> new Backlog(settings.backlog())).add(delivery);
            listening = subscriptions.stream().filter(subscription -> subscription.listensTo(topic)).toList();
        } finally {
            lock.unlock();
        }
        // Outside the lock: offering never blocks, but a client that has fallen
        // behind is closed as it fails, and closing interrupts a thread.
        listening.forEach(subscription -> subscription.offer(delivery));
    }

    public int subscribers() {
        lock.lock();
        try {
            return subscriptions.size();
        } finally {
            lock.unlock();
        }
    }

    public int subscribers(String topic) {
        lock.lock();
        try {
            return (int) subscriptions.stream().filter(subscription -> subscription.listensTo(topic)).count();
        } finally {
            lock.unlock();
        }
    }

    /// Waits until the cable has the requested number of subscribers.
    ///
    /// The method returns `true` when the state matches and `false` when the
    /// bounded wait expires. A subscriber joining or leaving wakes the waiter.
    public boolean awaitSubscribers(int wanted, Duration patience) throws InterruptedException {
        if (wanted < 0) throw new IllegalArgumentException("subscriber count must not be negative");
        if (patience.isNegative() || patience.isZero()) throw new IllegalArgumentException("patience must be positive");
        lock.lock();
        try {
            var left = patience.toNanos();
            while (subscriptions.size() != wanted) {
                if (left <= 0) return false;
                left = ended.awaitNanos(left);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /// The element that makes a page listen.
    ///
    /// [#feature(Topics)] puts this in the body of every page, which is why
    /// nothing outside this class renders it. An application that rendered one
    /// as well would have two elements with the same id, and the second is the
    /// one a browser ignores — so the page would listen through whichever came
    /// first and look correct either way.
    private static Html source(String url) {
        return Html.element("div",
                Attributes.id("cable-stream-source"),
                Turbo.permanent(),
                Stimulus.controller(CONTROLLER),
                Stimulus.value(CONTROLLER, "url", url));
    }

    /// Everything an application needs to use the cable: the controller, its
    /// pin, the element every page listens through, the route it connects to,
    /// and the handler behind it.
    ///
    /// The controller sits beside this package's own code and travels with it,
    /// into a jar and out of one.
    ///
    /// The element resolves its URL when the page is written rather than now,
    /// so an application that mounts the cable at `/live` gets an element
    /// pointing there without saying so twice.
    ///
    /// `topics` stays the application's, because what is worth broadcasting is
    /// the application's business and nothing here can guess it. Everything
    /// else is this package's and is now said once:
    ///
    /// ```
    /// Features.of(Router.of(), cable.feature(Topics.fixed("symbols")));
    /// ```
    ///
    /// That includes [#close()]. The application still holds this cable and
    /// still broadcasts on it — the feature does not take it away — but it no
    /// longer has to remember that a cable is among the things a shutdown has
    /// to wait for.
    public Feature feature(Topics topics) {
        return Feature.named("web.cable")
                .from(Bundled.of(Cable.class, ASSETS))
                .pin(MODULE, FILE)
                .body((assets, routes, out) -> source(routes.path(UPDATES)).write(out))
                .get(UPDATES, stream(topics))
                .closing(this);
    }

    /// Ends every subscription and waits for them. A cable that returned from
    /// this while a handler was still writing would leave a server unable to
    /// say when it had stopped.
    @Override
    public void close() {
        List<Subscription> ending;
        lock.lock();
        try {
            closed = true;
            ending = List.copyOf(subscriptions);
        } finally {
            lock.unlock();
        }
        ending.forEach(Subscription::close);
        await();
    }

    private void await() {
        lock.lock();
        try {
            var deadline = System.nanoTime() + SHUTDOWN;
            while (!subscriptions.isEmpty()) {
                var left = deadline - System.nanoTime();
                if (left <= 0 || !ended.await(left, TimeUnit.NANOSECONDS)) return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    /// Registers a subscriber and answers with where the stream had got to.
    ///
    /// Both happen under the same lock as a broadcast, which is what makes the
    /// handover exact: anything sent before this moment is in the backlog and
    /// will be replayed, anything after is in the queue and will be delivered.
    /// Neither twice, neither not at all.
    private long join(Subscription subscription) {
        lock.lock();
        try {
            if (closed) throw new IllegalStateException("this cable is closed");
            subscriptions.add(subscription);
            ended.signalAll();
            return sequence;
        } finally {
            lock.unlock();
        }
    }

    private void leave(Subscription subscription) {
        subscription.close();
        lock.lock();
        try {
            subscriptions.remove(subscription);
            ended.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /// Tells a reconnecting client what it missed, or that it missed too much.
    private void catchUp(Request request, Subscription subscription, long upTo) throws IOException {
        var last = request.headers().first(LAST_EVENT).orElse("");
        if (last.isEmpty()) return;

        var position = position(last);
        if (position.isEmpty()) {
            stale(subscription);
            return;
        }
        var missed = new ArrayList<Delivery>();
        var gap = false;
        lock.lock();
        try {
            for (var topic : subscription.topics()) {
                var backlog = backlogs.get(topic);
                if (backlog == null) continue;
                if (backlog.gapAfter(position.getAsLong())) gap = true;
                missed.addAll(backlog.since(position.getAsLong(), upTo));
            }
        } finally {
            lock.unlock();
        }
        if (gap) {
            stale(subscription);
            return;
        }
        missed.sort(Comparator.comparingLong(Delivery::sequence));
        for (var delivery : missed) subscription.write(delivery);
    }

    /// A client that cannot be caught up is asked to refresh itself. Turbo
    /// morphs the page rather than reloading it, so this costs a request and
    /// not the scroll position — and the alternative is a page that carries on
    /// showing something that stopped being true.
    private void stale(Subscription subscription) throws IOException {
        if (!settings.refreshOnGap()) return;
        subscription.write(Event.of(Turbo.refresh().markup()));
    }

    /// Where a client says it got to, if it was talking to this cable. An id
    /// from a previous run names a sequence that means nothing here, and
    /// replaying against it would send the wrong events with a straight face.
    private OptionalLong position(String id) {
        var dash = id.lastIndexOf('-');
        if (dash < 0 || !id.substring(0, dash).equals(run)) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(id.substring(dash + 1)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
