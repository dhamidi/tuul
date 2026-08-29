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

    /// The envelope field naming the type of the message an effect sends.
    ///
    /// It is an envelope field for the same reason a message's type is one. An
    /// effect that sends a message *is* that message plus a destination, so the
    /// effect's body is the message's payload — and a payload may have a field
    /// called anything, including `type`, `to` or `after`.
    ///
    /// This replaced nesting the whole message under a payload key. That shape
    /// made the sender pack a message by hand, and `with("message",
    /// message.body())` compiled: it handed over a payload with no envelope, so
    /// the message arrived typeless, was dispatched by nobody, and said nothing
    /// about why. Nothing is packed now, so there is nothing to pack wrongly.
    public static final String SENDING = "sending";

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
        return sending(SEND, message);
    }

    /// An effect of this type that sends this message.
    ///
    /// The message is not nested inside the effect: its payload becomes the
    /// effect's body and its type becomes [#SENDING] in the effect's envelope.
    /// So a handler does not unpack a message, it stamps one — and there is no
    /// way to build an effect that says it sends something and does not.
    ///
    /// Where the message goes is [#about(String, Json)], the envelope again,
    /// because the body belongs to the message and a payload may have a field
    /// called `to`.
    ///
    /// ```
    /// Effect.sending("actor.reply", Message.of("total").with("value", Json.of(7)));
    /// ```
    public static Effect sending(String type, Message message) {
        return new Effect(message.body(), Json.Object.of().with(TYPE, type).with(SENDING, message.type()));
    }

    /// The message this effect sends.
    ///
    /// The envelope is built here rather than carried through: a message's
    /// envelope says what the message is, and an effect's says what the effect
    /// is and where it goes. Only the type crosses over, so a routing field
    /// added to an effect later can never end up looking like something the
    /// message said about itself.
    ///
    /// An effect that does not say what it sends is a mistake in the update
    /// that built it, not a message with no type, so this refuses instead of
    /// inventing one. [Application] turns the throw into an `error` message, so
    /// it is loud and it does not take the loop down.
    public static Message sent(Effect effect) {
        var type = effect.envelope().string(SENDING, "");
        if (type.isEmpty()) {
            throw new IllegalStateException("the effect " + effect.type()
                    + " does not say what it sends — build it with Effect.sending(type, message)");
        }
        return Message.of(type, effect.body());
    }

    /// The same effect with one more envelope field: something about the doing
    /// of it rather than something said.
    ///
    /// Where a message goes, and how long to wait before sending it, live here
    /// and not in the body, because the body is the message's own payload.
    public Effect about(String name, Json value) {
        return new Effect(body, envelope.with(name, value));
    }

    public Effect about(String name, String value) {
        return new Effect(body, envelope.with(name, value));
    }

    public Effect about(String name, double value) {
        return new Effect(body, envelope.with(name, value));
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
