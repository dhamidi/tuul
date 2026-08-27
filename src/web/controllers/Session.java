package web.controllers;

import java.time.Instant;
import java.util.Optional;
import json.Json;

/// Who is asking, and whatever else survives between requests.
///
/// A session is a JSON object and an expiry, and nothing else — no user model,
/// no store, no identifier pointing at a row somewhere. What is in it travels
/// in the cookie, which means it is small by construction and a server holds
/// nothing on behalf of a client that may never come back.
///
/// The expiry is part of what is signed, so it is the server's opinion rather
/// than the client's. See [Sessions].
public record Session(Json.Object values, Instant expires) {

    /// Nobody. What a request without a valid cookie carries, so a handler asks
    /// [#present] rather than handling a null.
    public static final Session NONE = new Session(Json.Object.of(), Instant.EPOCH);

    public static Session of(Json.Object values, Instant expires) {
        return new Session(values, expires);
    }

    public boolean present() {
        return !values.fields().isEmpty();
    }

    public boolean expired(Instant now) {
        return !expires.isAfter(now);
    }

    public Optional<String> text(String name) {
        return values.get(name) instanceof Json.Str(var value) ? Optional.of(value) : Optional.empty();
    }

    public String text(String name, String fallback) {
        return text(name).orElse(fallback);
    }

    public Session with(String name, String value) {
        return new Session(values.with(name, value), expires);
    }

    public Session without(String name) {
        return new Session(values.without(name), expires);
    }

    public Session until(Instant expires) {
        return new Session(values, expires);
    }
}
