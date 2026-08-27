package application;

import json.Json;

/// Something that happened. Applications react to messages and to nothing
/// else.
public record Message(Json.Object body) implements Envelope {

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
}
