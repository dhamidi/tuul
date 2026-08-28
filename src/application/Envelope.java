package application;

import java.util.List;
import json.Json;

/// What messages and effects have in common: a JSON object whose `type` field
/// says what it is, and whose other fields are the payload.
///
/// Because both are plain JSON, either can be written to a log, sent down a
/// pipe and read back without translation — `tuul message` is exactly that.
public interface Envelope {

    Json.Object body();

    default String type() {
        return body().string("type", "");
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
}
