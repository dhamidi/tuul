package jsonrpc2;

import java.io.IOException;
import json.Json;
import json.JsonWriter;

/// A client sends a call to ask a server to run a method.
///
/// The protocol uses one name for this and gives it two behaviours, so this
/// interface has two cases. A [Request] carries an id and the server must
/// answer it. A [Notification] carries no id and the server must not answer
/// it, whatever happens while the method runs. Making that difference a type
/// rather than a nullable field means no code has to remember to check for
/// null.
///
/// The `params` value is an array when the caller passed arguments by
/// position and an object when the caller named them. [json.Json#NULL] means
/// the call has no arguments, and `write` then leaves the field out of the
/// document.
public sealed interface Call {

    String method();

    Json params();

    /// A call the server must answer. The response carries this id back.
    record Request(Id id, String method, Json params) implements Call {}

    /// A call the server must not answer. The server runs the method and stays
    /// silent, even when the method fails. A client that needs to know the
    /// outcome has to send a [Request] instead.
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
