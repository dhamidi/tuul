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

    default List<Json> list(String name) {
        return body().list(name);
    }
}
