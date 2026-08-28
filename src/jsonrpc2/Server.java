package jsonrpc2;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import json.Json;

/// A server holds named methods and answers the calls that name them.
///
/// A server is not a socket and it does not listen to one. It reads a document
/// from a [Reader] and writes the answer to a [Writer]. Give it a [Transport]
/// and it repeats that until the transport is finished.
///
/// ```
/// var server = Server.of()
///         .method("subtract", params -> Json.of(minuend(params) - subtrahend(params)));
///
/// server.serve(transport);
/// ```
///
/// It fails open, in the way the protocol asks for:
///
/// - a method that throws becomes an error response,
/// - a document that is not JSON becomes -32700,
/// - a member of a batch that is not a call becomes -32600.
///
/// Nothing a caller sends can stop the server from answering the rest of what
/// it sent.
public final class Server {

    private final Map<String, Method> methods = new LinkedHashMap<>();

    private Server() {}

    public static Server of() {
        return new Server();
    }

    /// Adds a method. A second method under the same name replaces the first.
    /// A name has one meaning, and a test has to be able to change it.
    public Server method(String name, Method method) {
        methods.put(name, method);
        return this;
    }

    /// Reads one document and answers it. Answers with `true` when it wrote a
    /// document, and with `false` when it wrote nothing.
    ///
    /// It writes nothing when every call in the document is a notification.
    /// The protocol requires that silence, and a caller must not turn it into
    /// an empty document.
    public boolean handle(Reader in, Writer out) throws IOException {
        try {
            var document = Jsonrpc2.read(in);
            var answers = answer(document);
            return Jsonrpc2.write(answers, document.batched() && !document.members().isEmpty(), out);
        } catch (Rejection rejection) {
            var answer = new Response.Failed(Id.NOTHING, rejection.failure());
            return Jsonrpc2.write(List.of(answer), false, out);
        }
    }

    /// Answers everything the transport delivers, one document at a time.
    ///
    /// This method handles documents in order, on the calling thread. Two
    /// answers on one transport would otherwise race for the same writer, and a
    /// transport would have to be thread-safe to be useful. Concurrency belongs
    /// inside a batch, where the protocol allows it. Run this method on a thread
    /// per connection to serve several callers at once.
    public void serve(Transport transport) throws IOException {
        for (var incoming = transport.receive(); incoming.isPresent(); incoming = transport.receive()) {
            try (var out = transport.send()) {
                handle(incoming.get(), out);
            }
        }
    }

    private List<Response> answer(Jsonrpc2.Document document) {
        var members = document.members();
        if (members.isEmpty()) {
            return List.of(new Response.Failed(Id.NOTHING, Failure.invalidRequest("a batch must have members")));
        }
        if (members.size() == 1) return answer(members.getFirst()).map(List::of).orElse(List.of());
        return concurrently(members);
    }

    /// Structured concurrency, by the route that is not a preview API: the
    /// members of one batch are forked into an executor that is closed before
    /// the method returns, and `close()` does not return until every one of
    /// them has finished. The lifetime of the batch is this block. That is the
    /// whole point, and it is why this does not reach for `StructuredTaskScope`
    /// while that is still a preview API — tuul's class files would be pinned
    /// to one exact JDK build, and so would every project that vendors them.
    ///
    /// Nothing here propagates a failure, because there is nothing to
    /// propagate. A member that fails has already become an error response by
    /// the time [#answer(Json)] returns, which is what makes an ordinary
    /// executor enough.
    ///
    /// This method collects answers by position, so the array keeps the order
    /// the calls arrived in. The protocol permits any order. A stable one costs
    /// nothing and makes a batch readable.
    private List<Response> concurrently(List<Json> members) {
        var answers = new ConcurrentSkipListMap<Integer, Response>();
        try (var handling = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var at = 0; at < members.size(); at++) {
                var member = members.get(at);
                var position = at;
                handling.execute(() -> answer(member).ifPresent(answer -> answers.put(position, answer)));
            }
        }
        return List.copyOf(answers.values());
    }

    /// Answers one member of a document, or answers with nothing.
    ///
    /// The checks run in the order the protocol reads the member, and the order
    /// decides which error a caller sees. A member that is not a call always
    /// gets a response, even when it carries no id. The caller cannot have
    /// meant it as a notification. A call that is well formed and carries no id
    /// is a notification, and every outcome of it stays silent.
    private Optional<Response> answer(Json member) {
        if (!(member instanceof Json.Object object)) {
            return refuse(Failure.invalidRequest("a call must be an object"));
        }
        var declared = object.fields().containsKey("id");
        var id = Id.read(object.get("id"));
        if (declared && id.isEmpty()) {
            return refuse(Failure.invalidRequest("an id must be a string, a number or null"));
        }
        var caller = id.orElse(Id.NOTHING);
        if (!Jsonrpc2.VERSION.equals(object.string("jsonrpc", ""))) {
            return report(caller, Failure.invalidRequest("a call must declare jsonrpc " + Jsonrpc2.VERSION));
        }
        if (!(object.get("method") instanceof Json.Str(var name))) {
            return report(caller, Failure.invalidRequest("a method name must be a string"));
        }
        var params = params(object);
        if (params.isEmpty()) return reply(id, Failure.invalidParams("params must be an array or an object"));
        var method = methods.get(name);
        if (method == null) return reply(id, Failure.methodNotFound(name));
        return reply(id, method, params.get());
    }

    /// The arguments of a call, or nothing when the `params` field holds
    /// something that cannot be arguments.
    ///
    /// A wrong `params` field is -32602 and not -32600. The protocol does not
    /// say which, and both readings are defensible. This one is more useful to
    /// a caller: the member is a call, the method is named, and the one thing
    /// wrong with it is its arguments. This server keeps -32600 for a member it
    /// cannot read as a call at all.
    private static Optional<Json> params(Json.Object object) {
        if (!object.fields().containsKey("params")) return Optional.of(Json.NULL);
        var params = object.get("params");
        var usable = params instanceof Json.Array || params instanceof Json.Object || params instanceof Json.Null;
        return usable ? Optional.of(params) : Optional.empty();
    }

    /// Runs the method and reports what it did. A notification runs exactly the
    /// same way. This method only drops the answer.
    private Optional<Response> reply(Optional<Id> id, Method method, Json params) {
        try {
            var value = method.call(params);
            return id.map(caller -> new Response.Result(caller, value == null ? Json.NULL : value));
        } catch (Rejection rejection) {
            return reply(id, rejection.failure());
        } catch (Exception e) {
            return reply(id, Failure.internalError(reason(e)));
        }
    }

    /// A failure that a notification never hears about.
    private static Optional<Response> reply(Optional<Id> id, Failure failure) {
        return id.map(caller -> new Response.Failed(caller, failure));
    }

    /// A failure the caller hears about whether it asked or not.
    private static Optional<Response> report(Id caller, Failure failure) {
        return Optional.of(new Response.Failed(caller, failure));
    }

    private static Optional<Response> refuse(Failure failure) {
        return report(Id.NOTHING, failure);
    }

    private static String reason(Exception e) {
        var cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
