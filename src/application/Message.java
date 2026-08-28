package application;

import json.Json;

/// Something that happened. Applications react to messages and to nothing
/// else.
public record Message(Json.Object body) implements Envelope {

    /// The field that says when this message was delivered, in epoch
    /// milliseconds.
    ///
    /// An actor stamps it on every message before the update sees it, so an
    /// update has a "now" it may read without reading a clock. Nothing else
    /// stamps it: a message dispatched to a plain [Application] carries only
    /// what its sender put in it.
    public static final String AT = "at";

    public static Message of(String type) {
        return new Message(Json.Object.of().with("type", type));
    }

    public static Message of(String type, Json.Object payload) {
        return new Message(payload.with("type", type));
    }

    /// Failures travel as ordinary messages; nothing about them is special
    /// except that the application will not report an error about an error.
    public static Message error(String reason) {
        return of("error").with("reason", reason);
    }

    public Message with(String name, Json value) {
        return new Message(body.with(name, value));
    }

    public Message with(String name, String value) {
        return new Message(body.with(name, value));
    }

    public Message with(String name, boolean value) {
        return new Message(body.with(name, value));
    }

    public Message with(String name, double value) {
        return new Message(body.with(name, value));
    }

    /// When this message was delivered, in epoch milliseconds, or zero when
    /// nobody stamped it. See [#AT].
    public long at() {
        return (long) number(AT, 0);
    }

    /// The same message, stamped with when it was delivered.
    ///
    /// A message that already carries an [#AT] keeps the one it has, because
    /// the sender meant something by it and this must not overwrite it.
    public Message at(long at) {
        return get(AT) instanceof Json.Num ? this : with(AT, (double) at);
    }
}
