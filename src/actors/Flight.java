package actors;

import java.util.List;
import java.util.concurrent.Flow;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;
import json.Json;

/// Writes a system's events into JDK Flight Recorder.
///
/// ## How to use it
///
/// ```
/// try (var system = ActorSystem.named("orders").rooted(root).define(new Baskets());
///         var flight = Flight.recording(system)) {
///     // jcmd <pid> JFR.start name=actors settings=profile
/// }
/// ```
///
/// Nothing else in the package refers to `jdk.jfr`. That is deliberate: this is
/// one subscriber on [ActorSystem#traces()], and a system that never calls
/// [#recording(ActorSystem)] never loads this class. On a `jlink` image built without
/// the `jdk.jfr` module, a program that does not ask for flight recording keeps
/// working, and one that does gets a `NoClassDefFoundError` naming the missing
/// module rather than a system that silently records nothing.
///
/// ## Why a subscriber and not a call at every site
///
/// Writing these events straight from the actor code would make a JFR recording
/// the only way to watch a system. A recording is a file that flushes about once
/// a second and is read by parsing it, which is right for profiling and wrong
/// for anything live. [ActorSystem#traces()] is the live feed, this is one consumer
/// of it, and an inspector is another.
///
/// ## What it costs when nothing is recording
///
/// Each event checks [Event#shouldCommit()] before any field is filled in, so an
/// event nobody enabled costs a call and a branch. The trace bus itself does no
/// work while it has no subscriber, but this class *is* a subscriber, so a
/// system with flight recording attached does build a [Trace] for every event it
/// produces. That is why the per-message trace stays off unless
/// [ActorSystem#tracingMessages(boolean)] turns it on.
public final class Flight implements AutoCloseable {

    private final ActorSystem system;
    private final Feed feed;
    private final Runnable gauges;

    private Flight(ActorSystem system) {
        this.system = system;
        this.feed = new Feed();
        this.gauges = this::sample;
        system.traces().subscribe(feed);
        FlightRecorder.addPeriodicEvent(Loaded.class, gauges);
    }

    /// Starts writing this system's events as flight recorder events, until the
    /// returned object is closed.
    public static Flight recording(ActorSystem system) {
        return new Flight(system);
    }

    @Override
    public void close() {
        FlightRecorder.removePeriodicEvent(gauges);
        feed.cancel();
    }

    /// Fills in the periodic gauge from the registry.
    ///
    /// Gauges poll rather than subscribe, because a count of what exists right
    /// now is a question about the whole system and not about any one event.
    private void sample() {
        var event = new Loaded();
        if (!event.shouldCommit()) return;
        var known = system.known().toList();
        event.system = system.name();
        event.loaded = known.stream().filter(Registry.Entry::loaded).count();
        event.known = known.size();
        event.mailbox = known.stream().mapToInt(Registry.Entry::mailbox).max().orElse(0);
        event.quarantined = known.stream().filter(entry -> entry.health().quarantined()).count();
        event.tracesDropped = system.tracesDropped();
        event.commit();
    }

    /// Turns one trace into the event that matches its kind.
    private static void write(Trace trace) {
        switch (trace.kind()) {
            case summoned -> {
                var event = new Summoned();
                if (!event.shouldCommit()) return;
                event.address = trace.address().toString();
                event.type = trace.address().type();
                event.commands = (long) number(trace, "commands");
                event.millis = number(trace, "millis");
                event.commit();
            }
            case evicted -> {
                var event = new Evicted();
                if (!event.shouldCommit()) return;
                event.address = trace.address().toString();
                event.handled = (long) number(trace, "handled");
                event.settled = flag(trace, "settled");
                event.commit();
            }
            case quarantined -> {
                var event = new Quarantined();
                if (!event.shouldCommit()) return;
                event.address = trace.address().toString();
                event.reason = text(trace, "reason");
                event.restarts = (int) number(trace, "restarts");
                event.commit();
            }
            case undeliverable -> {
                var event = new Undelivered();
                if (!event.shouldCommit()) return;
                event.address = trace.address().toString();
                event.cause = text(trace, "cause");
                event.message = text(trace, "type");
                event.commit();
            }
            case abandoned -> {
                var event = new Abandoned();
                if (!event.shouldCommit()) return;
                event.address = trace.address().toString();
                event.total = (long) number(trace, "total");
                event.commit();
            }
            case fenced -> {
                var event = new Fenced();
                if (!event.shouldCommit()) return;
                event.address = trace.address().toString();
                event.total = (long) number(trace, "total");
                event.commit();
            }
            case handled -> {
                var event = new Handled();
                if (!event.shouldCommit()) return;
                event.address = trace.address().toString();
                event.message = text(trace, "type");
                event.seq = (long) number(trace, "seq");
                event.commit();
            }
        }
    }

    private static double number(Trace trace, String name) {
        if (!(trace.detail() instanceof Json.Object detail)) return 0;
        return detail.get(name) instanceof Json.Num(var value) ? value : 0;
    }

    private static String text(Trace trace, String name) {
        return trace.detail() instanceof Json.Object detail ? detail.string(name, "") : "";
    }

    private static boolean flag(Trace trace, String name) {
        return trace.detail() instanceof Json.Object detail && detail.flag(name);
    }

    /// Requests events one batch at a time rather than without limit, so that
    /// the bus keeps its bound and reports what it dropped.
    private static final class Feed implements Flow.Subscriber<Trace> {

        private static final int BATCH = 256;
        private Flow.Subscription subscription;
        private int taken;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(BATCH);
        }

        @Override
        public void onNext(Trace trace) {
            write(trace);
            if (++taken < BATCH) return;
            taken = 0;
            subscription.request(BATCH);
        }

        @Override
        public void onError(Throwable throwable) {
            // the bus has given up on this subscriber; there is nowhere to report it
        }

        @Override
        public void onComplete() {
            // the system closed
        }

        private void cancel() {
            if (subscription != null) subscription.cancel();
        }
    }

    // ---- the events ------------------------------------------------------
    //
    // Every one of them turns off stack traces. A stack trace is the expensive
    // part of an event and it says nothing useful here, because the interesting
    // frame is always the actor's mailbox loop.

    @Name("tuul.actors.Summoned")
    @Label("Actor Summoned")
    @Category({"tuul", "actors"})
    @StackTrace(false)
    static final class Summoned extends Event {
        @Label("Address") String address;
        @Label("Type") String type;
        @Label("Commands Replayed") long commands;
        @Label("Replay Milliseconds") double millis;
    }

    @Name("tuul.actors.Evicted")
    @Label("Actor Evicted")
    @Category({"tuul", "actors"})
    @StackTrace(false)
    static final class Evicted extends Event {
        @Label("Address") String address;
        @Label("Messages Handled") long handled;
        @Label("Settled") boolean settled;
    }

    @Name("tuul.actors.Quarantined")
    @Label("Actor Quarantined")
    @Category({"tuul", "actors"})
    @StackTrace(false)
    static final class Quarantined extends Event {
        @Label("Address") String address;
        @Label("Reason") String reason;
        @Label("Restarts") int restarts;
    }

    @Name("tuul.actors.Undeliverable")
    @Label("Message Undeliverable")
    @Category({"tuul", "actors"})
    @StackTrace(false)
    static final class Undelivered extends Event {
        @Label("Address") String address;
        @Label("Cause") String cause;
        @Label("Message Type") String message;
    }

    @Name("tuul.actors.AbandonedEffect")
    @Label("Effect Abandoned")
    @Category({"tuul", "actors"})
    @StackTrace(false)
    static final class Abandoned extends Event {
        @Label("Address") String address;
        @Label("Abandoned So Far") long total;
    }

    @Name("tuul.actors.FencedEmission")
    @Label("Late Emission Dropped")
    @Category({"tuul", "actors"})
    @StackTrace(false)
    static final class Fenced extends Event {
        @Label("Address") String address;
        @Label("Fenced So Far") long total;
    }

    /// One message. Enabled only when [ActorSystem#tracingMessages(boolean)] is on,
    /// because an actor doing ten thousand messages a second would fill a
    /// recording with these and nothing else.
    @Name("tuul.actors.Handled")
    @Label("Message Handled")
    @Category({"tuul", "actors"})
    @StackTrace(false)
    static final class Handled extends Event {
        @Label("Address") String address;
        @Label("Message Type") String message;
        @Label("Sequence") long seq;
    }

    @Name("tuul.actors.Loaded")
    @Label("Actors Loaded")
    @Category({"tuul", "actors"})
    @StackTrace(false)
    @Period("10 s")
    static final class Loaded extends Event {
        @Label("System") String system;
        @Label("Loaded") long loaded;
        @Label("Known") long known;
        @Label("Deepest Mailbox") int mailbox;
        @Label("Quarantined") long quarantined;
        @Label("Traces Dropped") long tracesDropped;
    }
}
