package application;

import json.Json;

/// Something to do to the outside world — as data, not as a lambda:
/// `{"type":"docs.print","body":{"symbol":{...}}}`.
///
/// An update names the effect and packs everything the effect needs into it;
/// the handler registered for that type on the [Application] decides how to
/// carry it out. Updates stay pure and testable, handlers stay swappable, and
/// an effect can be logged or replayed like any other JSON object.
///
/// Like a [Message], an effect is its payload and its envelope kept apart. See
/// [Envelope].
public record Effect(Json.Object body, Json.Object envelope) implements Envelope {

    /// The one effect every application already knows how to carry out: hand a
    /// message back to itself.
    public static final String SEND = "application.send";

    /// The field [#send(Message)] carries the message in.
    public static final String MESSAGE = "message";

    public static Effect of(String type) {
        return of(type, Json.Object.of());
    }

    public static Effect of(String type, Json.Object payload) {
        return new Effect(payload, Json.Object.of().with(TYPE, type));
    }

    /// Reads back an effect written by [Envelope#json()].
    public static Effect from(Json.Object document) {
        var payload = document.get(BODY) instanceof Json.Object body ? body : Json.Object.of();
        return new Effect(payload, document.without(BODY));
    }

    /// An effect that hands a whole message back.
    public static Effect send(Message message) {
        return of(SEND).carrying(message);
    }

    /// This effect, carrying a whole message for whoever runs it.
    ///
    /// Packing one by hand is the way this goes wrong. A message travels as
    /// [Envelope#json()], and `with(MESSAGE, message.body())` also compiles —
    /// it hands over the payload with no envelope, so the message arrives with
    /// no type, is dispatched by nobody, and says nothing about why. That was
    /// the documented idiom while the type lived in the payload and survived by
    /// accident. This is the same statement with the accident removed.
    public Effect carrying(Message message) {
        return with(MESSAGE, message.json());
    }

    /// The message an [#SEND] effect carries, or a typeless one when it carries
    /// nothing.
    public static Message carried(Effect effect) {
        return effect.get(MESSAGE) instanceof Json.Object document
                ? Message.from(document)
                : Message.of("");
    }

    public Effect with(String name, Json value) {
        return new Effect(body.with(name, value), envelope);
    }

    public Effect with(String name, String value) {
        return new Effect(body.with(name, value), envelope);
    }

    public Effect with(String name, boolean value) {
        return new Effect(body.with(name, value), envelope);
    }

    public Effect with(String name, double value) {
        return new Effect(body.with(name, value), envelope);
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
