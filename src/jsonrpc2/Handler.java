package jsonrpc2;

import json.Json;

/// A method that can talk on the [Conn] it arrived on.
///
/// Use this when the method must [Conn#notify(String, json.Json)] or
/// [Conn#call(String, json.Json)] before it returns. A method that only reads
/// its arguments and returns a value stays a [Method].
///
/// `conn` is the live session. A [Server#handle(java.io.Reader, java.io.Writer)]
/// has no session and passes `conn` as null. Register a handler that uses
/// `conn` on a [Conn], not on a server that only handles one document.
@FunctionalInterface
public interface Handler {

    Json call(Json params, Conn conn) throws Exception;
}
