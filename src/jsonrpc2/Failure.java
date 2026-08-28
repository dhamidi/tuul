package jsonrpc2;

import java.io.IOException;
import json.Json;
import json.JsonWriter;

/// A failure says why a call produced no result.
///
/// The `code` field says what went wrong and `message` says it in one short
/// line. The `data` field carries anything else that helps. This record writes
/// `data` only when it holds something, because a `"data":null` field tells a
/// reader nothing and still costs bytes.
///
/// The factory methods below use the message text that the protocol prints in
/// its own tables, and they put the detail in `data`. A client that switches
/// on the message text keeps working, and a person reading the message still
/// learns which call broke.
public record Failure(int code, String message, Json data) {

    /// The text was not JSON.
    public static final int PARSE_ERROR = -32700;

    /// The JSON was not a call.
    public static final int INVALID_REQUEST = -32600;

    /// The call named a method that this server does not have.
    public static final int METHOD_NOT_FOUND = -32601;

    /// The server has the method, but not for these arguments.
    public static final int INVALID_PARAMS = -32602;

    /// The method threw an exception that the server did not plan for.
    public static final int INTERNAL_ERROR = -32603;

    /// The lowest code an application may define for itself.
    public static final int SERVER_LOWEST = -32099;

    /// The highest code an application may define for itself.
    public static final int SERVER_HIGHEST = -32000;

    public Failure {
        if (message == null) message = "";
        if (data == null) data = Json.NULL;
    }

    public static Failure of(int code, String message) {
        return new Failure(code, message, Json.NULL);
    }

    /// Returns this failure with `data` attached.
    public Failure with(Json data) {
        return new Failure(code, message, data);
    }

    public static Failure parseError(String detail) {
        return new Failure(PARSE_ERROR, "Parse error", Json.of(detail));
    }

    public static Failure invalidRequest(String detail) {
        return new Failure(INVALID_REQUEST, "Invalid Request", Json.of(detail));
    }

    public static Failure methodNotFound(String method) {
        return new Failure(METHOD_NOT_FOUND, "Method not found", Json.of(method));
    }

    public static Failure invalidParams(String detail) {
        return new Failure(INVALID_PARAMS, "Invalid params", Json.of(detail));
    }

    public static Failure internalError(String detail) {
        return new Failure(INTERNAL_ERROR, "Internal error", Json.of(detail));
    }

    /// A failure an application defines for itself.
    ///
    /// The protocol reserves -32768 to -32000 and leaves -32099 to -32000 of
    /// that block to the server. This method refuses any other code with an
    /// [IllegalArgumentException]. A code outside the block is a bug that a
    /// client can only see as a wrong answer, so it stops here instead.
    public static Failure server(int code, String message) {
        if (code < SERVER_LOWEST || code > SERVER_HIGHEST) {
            throw new IllegalArgumentException(
                    "a server error code must be between " + SERVER_LOWEST + " and " + SERVER_HIGHEST + ": " + code);
        }
        return new Failure(code, message, Json.NULL);
    }

    /// Whether the protocol owns this code. Codes -32768 to -32000 are
    /// reserved, and an application must not invent one of its own there.
    public boolean reserved() {
        return code >= -32768 && code <= SERVER_HIGHEST;
    }

    /// Reads an `error` field. Anything unreadable becomes an internal error,
    /// because a client that cannot parse a failure still has to report one.
    public static Failure read(Json value) {
        if (!(value instanceof Json.Object object)) return internalError("an error must be an object");
        var code = object.get("code") instanceof Json.Num(var number) ? (int) number : INTERNAL_ERROR;
        var data = object.fields().containsKey("data") ? object.get("data") : Json.NULL;
        return new Failure(code, object.string("message", ""), data);
    }

    public void write(JsonWriter out) throws IOException {
        out.beginObject().name("code").value(code).name("message").value(message);
        if (!(data instanceof Json.Null)) out.name("data").value(data);
        out.endObject();
    }
}
