package jsonrpc2;

import java.util.Optional;
import json.Json;

/// The name of one call, and how a client recognises its answer.
///
/// The protocol allows three kinds of id: a string, a number, and null. This
/// interface keeps all three apart. The id `"1"` and the id `1` belong to
/// different calls, and a client that compares ids as text would confuse
/// them.
///
/// Each case is a record, so equality comes free and an id works as a map key.
/// That is how a client matches a batch answer to the call that asked for
/// it.
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

    /// Reads an `id` field.
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
    /// A whole number keeps no fractional part, because [json.JsonWriter]
    /// writes an integral double as an integer. The id `1` arrives as `1` and
    /// leaves as `1`. This holds while the number stays below 1e15. Above that
    /// [json.JsonWriter] writes the double form.
    default Json json() {
        return switch (this) {
            case Text(var text) -> Json.of(text);
            case Number(var number) -> Json.of(number);
            case Nothing _ -> Json.NULL;
        };
    }
}
