package jsonrpc2;

import java.io.IOException;
import json.Json;
import json.JsonWriter;

/// Something a client asks a server to do.
///
/// The protocol has one word for this and two behaviours, so this has two
/// cases. A [Request] carries an id and gets an answer. A [Notification]
/// carries no id and gets none, whatever happens while it runs. The difference
/// is a type here rather than a null field, so no code has to remember to check
/// it.
///
/// `params` is an array for positional arguments and an object for named ones.
/// [json.Json#NULL] means the call has no arguments, and this interface then
/// omits the field from the document.
public sealed interface Call {

    String method();

    Json params();

    /// A call that expects an answer.
    record Request(Id id, String method, Json params) implements Call {}

    /// A call that expects nothing. The server runs it and stays silent, even
    /// when it fails. A client that needs to know the outcome must send a
    /// [Request].
    record Notification(String method, Json params) implements Call {}

    static Request of(Id id, String method, Json params) {
        return new Request(id, method, params);
    }

    static Notification of(String method, Json params) {
        return new Notification(method, params);
    }

    default void write(JsonWriter out) throws IOException {
        out.beginObject().name("jsonrpc").value(Jsonrpc2.VERSION).name("method").value(method());
        if (!(params() instanceof Json.Null)) out.name("params").value(params());
        if (this instanceof Request(var id, var _, var _)) out.name("id").value(id.json());
        out.endObject();
    }
}
