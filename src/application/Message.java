package application;

import json.Json;

/// One instruction for an application. Applications react to messages and to
/// nothing else.
///
/// A message is its payload and its envelope, kept apart — see [Envelope] for
/// why. `with` adds to the payload, so everything a sender says lands where a
/// handler reads it and nowhere near the type.
public record Message(Json.Object body, Json.Object envelope) implements Envelope {

    /// The imperative type used to ask an application to handle a failure.
    public static final String HANDLE_ERROR = "handle-error";

    /// The imperative type used to ask an application to handle a timeout.
    public static final String HANDLE_TIMEOUT = "handle-timeout";

    /// The envelope field that says when this message was delivered, in epoch
    /// milliseconds.
    ///
    /// An actor stamps it before the update sees it, so an update has a "now"
    /// it may read without reading a clock. Nothing else stamps it: a message
    /// dispatched to a plain [Application] carries only what its sender put in
    /// it.
    ///
    /// This is an *envelope* field. A payload field called `at` is the sender's
    /// own and is never read here, never written here, and never stamped over.
    public static final String AT = "at";

    public static Message of(String type) {
        return of(type, Json.Object.of());
    }

    public static Message of(String type, Json.Object payload) {
        return new Message(payload, Json.Object.of().with(TYPE, type));
    }

    /// Reads back a message written by [Envelope#json()].
    ///
    /// Everything that is not [Envelope#BODY] is envelope, so a document
    /// carrying an envelope field this version has never heard of keeps it
    /// rather than losing it. That is deliberate: the envelope is where a
    /// message says things about itself, and a reader that discarded the
    /// unfamiliar would make every addition to it a breaking change.
    public static Message from(Json.Object document) {
        var payload = document.get(BODY) instanceof Json.Object body ? body : Json.Object.of();
        return new Message(payload, document.without(BODY));
    }

    /// Asks an application to handle a failure. The application will not report
    /// a failure about this message if its handler also fails.
    public static Message error(String reason) {
        return of(HANDLE_ERROR).with("reason", reason);
    }

    public Message with(String name, Json value) {
        return new Message(body.with(name, value), envelope);
    }

    public Message with(String name, String value) {
        return new Message(body.with(name, value), envelope);
    }

    public Message with(String name, boolean value) {
        return new Message(body.with(name, value), envelope);
    }

    public Message with(String name, double value) {
        return new Message(body.with(name, value), envelope);
    }

    /// When this message was delivered, in epoch milliseconds, or zero when
    /// nobody stamped it. See [#AT].
    public long at() {
        return (long) envelope.number(AT, 0);
    }

    /// The same message, stamped with when it was delivered.
    ///
    /// This used to refuse to overwrite an `at` the message already carried,
    /// because the payload and the envelope shared one namespace and a payload
    /// field called `at` would otherwise have been destroyed. They no longer
    /// do, so the guard is gone with the hazard it guarded against: a delivery
    /// stamps the envelope, and what the sender wrote in the payload is not
    /// something a delivery can reach.
    public Message at(long at) {
        return new Message(body, envelope.with(AT, (double) at));
    }
}
