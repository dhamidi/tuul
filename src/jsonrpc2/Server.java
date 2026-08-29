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
/// A server is not a session. It reads one document from a [Reader] and writes
/// the answer to a [Writer]. That is one HTTP body, or one test. A live
/// stream, where this side also sends calls, is a [Conn] that
/// [Conn#answering(Server)] this table.
///
/// ```
/// var server = Server.of()
///         .method("subtract", params -> Json.of(minuend(params) - subtrahend(params)));
/// server.handle(in, out);
/// ```
///
/// A method that throws becomes an error response. A document that is not JSON
/// becomes -32700. A member of a batch that is not a call becomes -32600.
/// Nothing a caller sends can stop the server from answering the rest of what
/// it sent.
public final class Server {

    private final Map<String, Handler> methods = new LinkedHashMap<>();

    private Server() {}

    public static Server of() {
        return new Server();
    }

    /// Adds a method. A second method under the same name replaces the first.
    /// A name has one meaning, and a test has to be able to change it.
    public Server method(String name, Method method) {
        return method(name, (params, _) -> method.call(params));
    }

    /// Adds a method that may use the [Conn] it arrived on.
    ///
    /// [Server#handle(Reader, Writer)] passes a null connection. Register a
    /// handler that calls [Conn#notify(String, json.Json)] on a [Conn], not
    /// here.
    public Server method(String name, Handler handler) {
        methods.put(name, handler);
        return this;
    }

    Optional<Handler> handler(String name) {
        return Optional.ofNullable(methods.get(name));
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
    /// [Incoming#read(json.Json)] classifies the member. A well-formed call
    /// runs the method. A notification stays silent, including when the method
    /// is missing or throws. A response-shaped member is not a call, and this
    /// method reports it as an invalid request.
    private Optional<Response> answer(Json member) {
        return switch (Incoming.read(member)) {
            case Incoming.Invoke(var call) -> invoke(call);
            case Incoming.Reply(var response) -> report(response.id(),
                    Failure.invalidRequest("a method name must be a string"));
            case Incoming.Invalid(var failure, var id, var silent) -> silent
                    ? Optional.empty()
                    : Optional.of(new Response.Failed(id, failure));
        };
    }

    private Optional<Response> invoke(Call call) {
        Optional<Id> id = call instanceof Call.Request(var caller, var _, var _)
                ? Optional.of(caller)
                : Optional.empty();
        var handler = methods.get(call.method());
        if (handler == null) return reply(id, Failure.methodNotFound(call.method()));
        try {
            var value = handler.call(call.params(), null);
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

    private static String reason(Exception e) {
        var cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
