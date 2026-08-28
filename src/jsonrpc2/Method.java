package jsonrpc2;

import json.Json;

/// A method is one operation that a [Server] can perform.
///
/// Arguments arrive as the caller wrote them: an array for positional
/// arguments, an object for named ones, and [json.Json#NULL] when the call
/// sent none. Each method reads what it needs. This package does not bind
/// arguments to parameters, because it cannot know the shape a method wants.
///
/// Match on the shape of `params`. Do not cast to it. A cast that fails throws
/// a `ClassCastException`, the server reports that as -32603, and -32603 tells
/// the caller that the server broke. Arguments of the wrong shape are the
/// caller's mistake, and the code for that is -32602.
///
/// Return a value and the caller sees a result. Throw a [Rejection] to choose
/// the error the caller sees. Throw anything else and the server answers with
/// -32603, without copying the exception message into the response.
@FunctionalInterface
public interface Method {

    Json call(Json params) throws Exception;
}
