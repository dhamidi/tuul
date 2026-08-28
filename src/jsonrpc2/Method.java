package jsonrpc2;

import json.Json;

/// One thing a [Server] can do.
///
/// Arguments arrive as they were written: an array for positional arguments, an
/// object for named ones, and [json.Json#NULL] when the call sent none. The
/// method reads what it needs. This package does not bind arguments to
/// parameters, because it cannot know the shape a method wants.
///
/// Return the result. Throw a [Rejection] to answer with a chosen error code.
/// Throw anything else and the server answers with -32603, which is the honest
/// report for an exception nobody planned.
@FunctionalInterface
public interface Method {

    Json call(Json params) throws Exception;
}
