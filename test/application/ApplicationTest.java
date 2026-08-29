package application;

import harness.Check;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import json.Json;

public final class ApplicationTest {

    private ApplicationTest() {}

    public static void run() {
        wiring();
        loops();
        failsOpen();
        concurrent();
        composes();
        envelopes();
    }

    /// The envelope and the payload do not share a namespace.
    ///
    /// A message is two objects, so a payload may use `type`, `at`, or any word
    /// the envelope needs later. The check that matters is the round trip:
    /// [Effect#send(Message)] writes a whole message into another message's
    /// payload as one JSON document, and that document is where an envelope
    /// would be lost if it were flattened.
    private static void envelopes() {
        var message = Message.of("billing.received",
                Json.Object.of().with("type", "invoice").with("at", Json.of(1)).with("id", Json.of(7)));

        Check.equal("the envelope says what the message is", "billing.received", message.type());
        Check.equal("and the payload keeps its own type", "invoice", message.string("type", ""));
        Check.equal("and its own at", 1.0, message.number("at", 0));
        Check.equal("which is not the delivery stamp", 0L, message.at());

        var written = message.json().text();
        var read = Message.from(json.Json.parse(written) instanceof Json.Object o ? o : Json.Object.of());
        Check.equal("a message written as one document reads back the same type",
                "billing.received", read.type());
        Check.equal("with its payload's type intact", "invoice", read.string("type", ""));

        var delivered = new ArrayList<Message>();
        Application.<Integer>of(0)
                .on("billing.received", (state, incoming) -> Step.of(state, Effect.send(
                        Message.of("billing.filed", incoming.body()))))
                .on("billing.filed", (state, filed) -> {
                    delivered.add(filed);
                    return Step.of(state);
                })
                .dispatch(message);
        Check.equal("an effect that carries a message keeps its type", "billing.filed",
                delivered.isEmpty() ? "" : delivered.getFirst().type());
        Check.equal("and hands on a payload that still has its own",
                "invoice", delivered.isEmpty() ? "" : delivered.getFirst().string("type", ""));
    }

    /// Define the messages, define the effects, dispatch.
    private static void wiring() {
        var written = new ArrayList<String>();
        var app = Application.<Integer>of(0)
                .on("add", (state, message) -> Step.of(state + 1, Effect.of("log").with("line", "now " + (state + 1))))
                .effect("log", (effect, _) -> written.add(effect.string("line", "")));

        Check.equal("dispatch returns the new state", 2, app.dispatch(Message.of("add"), Message.of("add")));
        Check.equal("the state stays between dispatches", 2, app.state());
        Check.equal("effects reached their handler", List.of("now 1", "now 2"), written);
        Check.equal("unknown messages are ignored", 2, app.dispatch(Message.of("nothing anyone handles")));
    }

    /// An effect emits a message, which updates the state, which asks for
    /// another effect. The loop is over when nothing is pending.
    private static void loops() {
        var app = Application.<Integer>of(3)
                .on("tick", (state, message) -> state == 0 ? Step.of(state) : Step.of(state - 1, Effect.send(Message.of("tick"))));
        Check.equal("the loop runs to a standstill", 0, app.dispatch(Message.of("tick")));
    }

    private static void failsOpen() {
        var app = Application.<List<String>>of(List.of())
                .on("boom", (state, message) -> {
                    throw new IllegalStateException("no");
                })
                .on("ask", (state, message) -> Step.of(state, Effect.of("broken")))
                .on("astray", (state, message) -> Step.of(state, Effect.of("nobody.handles.this")))
                .on("error", (state, message) -> Step.of(concat(state, "error:" + message.string("reason", ""))))
                .effect("broken", (effect, _) -> {
                    throw new IllegalStateException("effect failed");
                });

        Check.equal("a throwing update is reported as a message",
                List.of("error:no"), app.dispatch(Message.of("boom")));
        Check.equal("a throwing handler is reported too",
                List.of("error:no", "error:effect failed"), app.dispatch(Message.of("ask")));
        Check.equal("an effect nobody handles is reported, not swallowed",
                List.of("error:no", "error:effect failed", "error:no handler for effect: nobody.handles.this"),
                app.dispatch(Message.of("astray")));

        var broken = Application.<Integer>of(0).on("error", (state, message) -> {
            throw new IllegalStateException("still no");
        });
        Check.equal("an error about an error does not loop", 0, broken.dispatch(Message.of("error")));
    }

    /// The effects of one step run together, and the step is over only when all
    /// of them are.
    private static void concurrent() {
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        var app = Application.<Integer>of(0)
                .on("start", (state, message) -> new Step<>(state, List.of(Effect.of("slow"), Effect.of("slow"), Effect.of("slow"))))
                .on("done", (state, message) -> Step.of(state + 1))
                .effect("slow", (effect, emit) -> {
                    peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                    Thread.sleep(50);
                    running.decrementAndGet();
                    emit.emit(Message.of("done"));
                });
        Check.equal("every effect reported back", 3, app.dispatch(Message.of("start")));
        Check.that("effects of a step run at the same time", peak.get() > 1);

        // Running together means running in no order. An actor that replies to
        // its caller and tells another actor in the same step cannot make the
        // telling land first, so read-your-writes across two actors has to be
        // orchestrated by the caller.
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var gate = new java.util.concurrent.CountDownLatch(1);
        Application.<Integer>of(0)
                .patience(java.time.Duration.ofSeconds(5))
                .on("start", (state, message) ->
                        new Step<>(state, List.of(Effect.of("first"), Effect.of("second"))))
                .effect("first", (effect, emit) -> {
                    gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    order.add("first");
                })
                .effect("second", (effect, emit) -> {
                    order.add("second");
                    gate.countDown();
                })
                .dispatch(Message.of("start"));
        Check.equal("and the effect listed first may finish last",
                List.of("second", "first"), List.copyOf(order));
    }

    private static void composes() {
        Check.equal("two handlers for one message both run", 2,
                Application.<Integer>of(0)
                        .on("add", (state, message) -> Step.of(state + 1))
                        .on("add", (state, message) -> Step.of(state + 1))
                        .dispatch(Message.of("add")));

        var counter = Application.<Integer>of(0)
                .on("add", (state, message) -> Step.of(state + 1, Effect.of("count.log")))
                .effect("count.log", (effect, _) -> {});

        var host = Application.of(Json.Object.of().with("count", 0))
                .include(counter,
                        whole -> (int) ((Json.Num) whole.get("count")).value(),
                        (whole, count) -> whole.with("count", count));

        Check.equal("an included application updates its part of the state",
                "{\"count\":2}",
                host.dispatch(Message.of("add"), Message.of("add")).text());
    }

    private static List<String> concat(List<String> state, String line) {
        var next = new ArrayList<>(state);
        next.add(line);
        return next;
    }
}
