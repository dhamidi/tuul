package jsonrpc2;

import java.util.Optional;
import json.Json;

/// One member of a JSON-RPC document, classified by shape.
///
/// A member is a [Call], a [Response], or neither. [Conn] uses that difference
/// to demux a live stream: a call runs a method, a response completes a
/// waiting [Conn#call(String, json.Json)], and anything else is an error. A
/// [Server#handle(java.io.Reader, java.io.Writer)] sees only calls. It maps a
/// response-shaped member to an invalid request.
///
/// [#read(Json)] does not look up methods. An unknown method is still a
/// well-formed call. The server that holds the method table decides that it
/// was not found.
public sealed interface Incoming {

    /// A well-formed call. A [Call.Request] expects an answer. A
    /// [Call.Notification] does not.
    record Invoke(Call call) implements Incoming {}

    /// A well-formed response to a call this side made.
    record Reply(Response response) implements Incoming {}

    /// A member that is not a call and not a response.
    ///
    /// `silent` is true when the protocol forbids an answer: a notification
    /// with bad parameters, or a broken response. `silent` is false when the
    /// caller must hear about the failure, even if the member carried no id.
    /// In that case `id` is [Id#NOTHING] when the member named no caller.
    record Invalid(Failure failure, Id id, boolean silent) implements Incoming {}

    /// Classifies one member.
    ///
    /// The checks run in the order the protocol reads the member, and the order
    /// decides which error a caller sees. A member that is not an object is
    /// never a notification. A well-formed call with no id is a notification.
    /// An object that carries `result` or `error` and no `method` is a
    /// response, even when [Server] will refuse it.
    static Incoming read(Json member) {
        if (!(member instanceof Json.Object object)) {
            return new Invalid(Failure.invalidRequest("a call must be an object"), Id.NOTHING, false);
        }
        if (!object.fields().containsKey("method")
                && (object.fields().containsKey("result") || object.fields().containsKey("error"))) {
            try {
                return new Reply(Response.read(object));
            } catch (Rejection rejection) {
                return new Invalid(rejection.failure(), Id.read(object.get("id")).orElse(Id.NOTHING), true);
            }
        }
        var declared = object.fields().containsKey("id");
        var id = Id.read(object.get("id"));
        if (declared && id.isEmpty()) {
            return new Invalid(Failure.invalidRequest("an id must be a string, a number or null"), Id.NOTHING, false);
        }
        var caller = id.orElse(Id.NOTHING);
        if (!Jsonrpc2.VERSION.equals(object.string("jsonrpc", ""))) {
            return new Invalid(Failure.invalidRequest("a call must declare jsonrpc " + Jsonrpc2.VERSION), caller, false);
        }
        if (!(object.get("method") instanceof Json.Str(var name))) {
            return new Invalid(Failure.invalidRequest("a method name must be a string"), caller, false);
        }
        var params = params(object);
        if (params.isEmpty()) {
            return new Invalid(Failure.invalidParams("params must be an array or an object"), caller, !declared);
        }
        if (declared) return new Invoke(Call.of(caller, name, params.get()));
        return new Invoke(Call.of(name, params.get()));
    }

    /// The arguments of a call, or nothing when the `params` field holds
    /// something that cannot be arguments.
    ///
    /// A wrong `params` field is -32602 and not -32600. The protocol does not
    /// say which, and both readings are defensible. This one is more useful to
    /// a caller: the member is a call, the method is named, and the one thing
    /// wrong with it is its arguments. This package keeps -32600 for a member
    /// it cannot read as a call at all.
    private static Optional<Json> params(Json.Object object) {
        if (!object.fields().containsKey("params")) return Optional.of(Json.NULL);
        var params = object.get("params");
        var usable = params instanceof Json.Array || params instanceof Json.Object || params instanceof Json.Null;
        return usable ? Optional.of(params) : Optional.empty();
    }
}
