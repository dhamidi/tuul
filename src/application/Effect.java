package application;

import json.Json;

/// Something to do to the outside world — as data, not as a lambda:
/// `{"type":"docs.print","symbol":{...}}`.
///
/// An update names the effect and packs everything the effect needs into it;
/// the handler registered for that type on the [Application] decides how to
/// carry it out. Updates stay pure and testable, handlers stay swappable, and
/// an effect can be logged or replayed like any other JSON object.
public record Effect(Json.Object body) implements Envelope {

    /// The one effect every application already knows how to carry out: hand a
    /// message back to itself.
    public static final String SEND = "application.send";

    public static Effect of(String type) {
        return new Effect(Json.Object.of().with("type", type));
    }

    public static Effect of(String type, Json.Object payload) {
        return new Effect(payload.with("type", type));
    }

    public static Effect send(Message message) {
        return of(SEND).with("message", message.body());
    }

    public Effect with(String name, Json value) {
        return new Effect(body.with(name, value));
    }

    public Effect with(String name, String value) {
        return new Effect(body.with(name, value));
    }

    public Effect with(String name, boolean value) {
        return new Effect(body.with(name, value));
    }

    public Effect with(String name, double value) {
        return new Effect(body.with(name, value));
    }

    /// Carries out one kind of effect. A throw is caught by the application and
    /// reported as an `error` message, so no handler can take the loop down.
    @FunctionalInterface
    public interface Handler {
        void run(Effect effect, Emitter emit) throws Exception;
    }

    /// How a handler reports back. Emitting is safe from any thread.
    @FunctionalInterface
    public interface Emitter {
        void emit(Message message);
    }
}
