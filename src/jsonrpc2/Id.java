package jsonrpc2;

import java.util.Optional;
import json.Json;

/// What a call is called, so that its answer can be recognised.
///
/// The protocol allows three kinds of id: a string, a number, and null. All
/// three are kept apart here. The id `"1"` and the id `1` are different calls,
/// and a client that compares ids as text would confuse them.
///
/// Equality is the record equality of the case, so an id works as a map key.
/// That is how a batch answer is matched to the call that asked for it.
///
/// A call with no id at all is not an [Id]. It is a [Call.Notification].
public sealed interface Id {

    /// The id `null`. The protocol permits it, and a call that uses it is still
    /// a call that expects an answer. A server also sends this id back when it
    /// cannot read the id of a broken request.
    Id NOTHING = new Nothing();

    record Text(String value) implements Id {}

    record Number(double value) implements Id {}

    record Nothing() implements Id {}

    static Id of(String value) {
        return new Text(value);
    }

    static Id of(double value) {
        return new Number(value);
    }

    /// Reads the `id` field of a message.
    ///
    /// Answers with nothing in two cases: the field is absent, and the field
    /// holds a value that cannot be an id. The caller knows which case it is
    /// looking at, because only the caller knows whether the field was there.
    static Optional<Id> read(Json value) {
        return switch (value) {
            case Json.Str(var text) -> Optional.of(new Text(text));
            case Json.Num(var number) -> Optional.of(new Number(number));
            case Json.Null _ -> Optional.of(NOTHING);
            case null, default -> Optional.empty();
        };
    }

    /// This id as the value that goes on the wire.
    ///
    /// A whole number goes out without a fractional part, because [json.JsonWriter]
    /// writes an integral double as an integer. The id `1` arrives as `1` and
    /// leaves as `1`.
    default Json json() {
        return switch (this) {
            case Text(var text) -> Json.of(text);
            case Number(var number) -> Json.of(number);
            case Nothing _ -> Json.NULL;
        };
    }
}
