package jsonrpc2;

import java.io.IOException;
import json.Json;
import json.JsonWriter;

/// What a server answers to one [Call.Request].
///
/// A response holds a result or a failure. It never holds both, and the
/// protocol says so. That is why this is two records, and not one record with
/// two fields that must never both be filled.
///
/// The id is the id of the call. A server returns it unchanged. Inside a batch
/// it is the only thing that ties an answer to a question.
public sealed interface Response {

    Id id();

    record Result(Id id, Json value) implements Response {}

    record Failed(Id id, Failure failure) implements Response {}

    /// Reads one response.
    ///
    /// Everything that goes wrong here is an internal error, and the error
    /// belongs to the client that reads it. The server already answered. This
    /// method only reports that the answer made no sense.
    static Response read(Json value) {
        if (!(value instanceof Json.Object object)) {
            throw Rejection.of(Failure.internalError("a response must be an object"));
        }
        if (!Jsonrpc2.VERSION.equals(object.string("jsonrpc", ""))) {
            throw Rejection.of(Failure.internalError("a response must declare jsonrpc " + Jsonrpc2.VERSION));
        }
        var id = Id.read(object.get("id"))
                .orElseThrow(() -> Rejection.of(Failure.internalError("a response must carry an id")));
        if (object.fields().containsKey("error")) return new Failed(id, Failure.read(object.get("error")));
        if (!object.fields().containsKey("result")) {
            throw Rejection.of(Failure.internalError("a response must carry a result or an error"));
        }
        return new Result(id, object.get("result"));
    }

    default void write(JsonWriter out) throws IOException {
        out.beginObject().name("jsonrpc").value(Jsonrpc2.VERSION);
        switch (this) {
            case Result(var _, var value) -> out.name("result").value(value);
            case Failed(var _, var failure) -> {
                out.name("error");
                failure.write(out);
            }
        }
        out.name("id").value(id().json());
        out.endObject();
    }
}
