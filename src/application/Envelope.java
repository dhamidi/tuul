package application;

import java.util.List;
import json.Json;

/// What messages and effects have in common: a payload, and an envelope that
/// says what the payload is.
///
/// The two are separate objects rather than two halves of one. They used to be
/// one — the type was a field beside the payload's own fields — and that made
/// the type's name a word no payload could use. `Message.of(type, payload)` was
/// `payload.with("type", type)`, so a payload that carried its own `type` lost
/// it silently: not refused, not renamed, dropped. Three places in this
/// repository had to write `body().without("type")` to get their payload back,
/// and the reserved namespace was growing — `at` had become a second such word,
/// with its own guard against clobbering.
///
/// Two objects cannot share a key namespace, so the collision is not unlikely
/// now, it is unspeakable. A payload may have a field called `type`, or `at`,
/// or anything a future envelope needs, and none of them mean anything to the
/// envelope.
///
/// ## One document
///
/// Both are still JSON, so either can be written to a log, sent down a pipe and
/// read back. [#json()] is that single document and [Message#from(Json.Object)]
/// reads it:
///
/// ```
/// {"type": "invoice.paid", "at": 1756400000000, "body": {"type": "invoice", "id": 7}}
/// ```
///
/// The envelope's fields sit at the top and the payload sits under `body`,
/// because the type is what a reader looks for first and what `jq .type` and
/// `select type` already expect. `body` is the only word the document reserves,
/// and it reserves it in a place no payload reaches.
public interface Envelope {

    /// Where the envelope keeps what this is.
    String TYPE = "type";

    /// The one key [#json()] reserves, and the one a payload never sees.
    String BODY = "body";

    /// The payload: everything the sender is saying, and nothing about the
    /// saying of it.
    Json.Object body();

    /// What is known about the message rather than said by it — its type, and
    /// when it was delivered.
    Json.Object envelope();

    default String type() {
        return envelope().string(TYPE, "");
    }

    default Json get(String name) {
        return body().get(name);
    }

    default String string(String name, String fallback) {
        return body().string(name, fallback);
    }

    default boolean flag(String name) {
        return body().flag(name);
    }

    /// A numeric field, or the fallback when the field is missing or is not a
    /// number.
    ///
    /// JSON has one number type, so a timestamp, a count and an amount all
    /// come back as a `double`. A caller that wants a `long` casts, and the
    /// cast is exact up to 2^53 — far past any epoch millisecond this century.
    default double number(String name, double fallback) {
        return body().number(name, fallback);
    }

    default List<Json> list(String name) {
        return body().list(name);
    }

    /// The whole of this as one JSON document, for a log, a pipe or a field in
    /// another message.
    ///
    /// The payload goes under [#BODY] whether or not it is empty, so that what
    /// is written says what shape it is rather than leaving a reader to infer
    /// it from what happens to be missing.
    default Json.Object json() {
        return envelope().with(BODY, body());
    }
}
