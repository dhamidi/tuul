package jsonrpc2;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import json.Json;
import json.JsonWriter;
import java.io.Writer;

/// A client sends calls over a [Transport] and reads the answers.
///
/// ```
/// var client = Client.of(transport);
/// var answer = client.call("subtract", Json.Array.of(List.of(Json.of(42), Json.of(23))));
/// ```
///
/// [#call(String,Json)] returns the result. It throws a [Rejection] for an
/// error, because a caller that wanted the result has no use for a value that
/// is not one. [#batch(List)] returns the responses instead, since one member
/// of a batch can fail while the rest succeed.
///
/// A client numbers its own ids, starting at one. Nothing else in this package
/// depends on that. A caller that wants ids of its own builds a [Call.Request]
/// and passes it to [#batch(List)].
public final class Client implements Closeable {

    private final Transport transport;
    private final AtomicLong ids = new AtomicLong();

    private Client(Transport transport) {
        this.transport = transport;
    }

    public static Client of(Transport transport) {
        return new Client(transport);
    }

    /// An id no other call from this client uses.
    public Id next() {
        return Id.of(ids.incrementAndGet());
    }

    public Json call(String method) throws IOException {
        return call(method, Json.NULL);
    }

    /// Calls a method and waits for the answer.
    ///
    /// Throws a [Rejection] when the server reports an error, and also when the
    /// answer carries the wrong id. A misaddressed answer is not the answer to
    /// this call, and returning it would be worse than failing.
    public Json call(String method, Json params) throws IOException {
        var id = next();
        var answer = one(Call.of(id, method, params));
        if (!answer.id().equals(id)) {
            throw Rejection.of(Failure.internalError("the answer carries another id: " + answer.id().json().text()));
        }
        return switch (answer) {
            case Response.Result(var _, var value) -> value;
            case Response.Failed(var _, var failure) -> throw Rejection.of(failure);
        };
    }

    public void notify(String method) throws IOException {
        notify(method, Json.NULL);
    }

    /// Sends a call that has no answer, and does not wait for one.
    public void notify(String method, Json params) throws IOException {
        post(List.of(Call.of(method, params)), false);
    }

    /// Sends several calls as one document.
    ///
    /// This method returns the answers in the order of the requests in
    /// `calls`, whatever order the server put them in. It matches them by id. A
    /// [Call.Notification] in `calls` produces no entry at all.
    ///
    /// A batch of nothing but notifications reads no answer, because the
    /// protocol says the server sends none.
    ///
    /// A request the server left unanswered becomes an internal error against
    /// its own id. The list therefore matches the requests that were sent, and
    /// a caller never has to guess which answer is missing.
    public List<Response> batch(List<Call> calls) throws IOException {
        post(calls, true);
        var asked = calls.stream().<Id>mapMulti((call, ids) -> {
            if (call instanceof Call.Request(var id, var _, var _)) ids.accept(id);
        }).toList();
        if (asked.isEmpty()) return List.of();
        var answers = new LinkedHashMap<Id, Response>();
        for (var member : receive().members()) {
            var answer = Response.read(member);
            answers.put(answer.id(), answer);
        }
        return asked.stream().map(id -> answers.getOrDefault(id, unanswered(id))).toList();
    }

    @Override
    public void close() throws IOException {
        transport.close();
    }

    private Response one(Call call) throws IOException {
        post(List.of(call), false);
        var members = receive().members();
        if (members.size() != 1) {
            throw Rejection.of(Failure.internalError("one call must produce one answer, not " + members.size()));
        }
        return Response.read(members.getFirst());
    }

    /// Writes one document. This method closes the writer, and on most
    /// transports that close is what sends the document.
    private void post(List<Call> calls, boolean batched) throws IOException {
        try (Writer out = transport.send()) {
            var writer = new JsonWriter(out);
            if (batched) writer.beginArray();
            for (var call : calls) call.write(writer);
            if (batched) writer.endArray();
            writer.flush();
        }
    }

    private Jsonrpc2.Document receive() throws IOException {
        var incoming = transport.receive();
        if (incoming.isEmpty()) throw Rejection.of(Failure.internalError("the transport carried no answer"));
        return Jsonrpc2.read(incoming.get());
    }

    private static Response unanswered(Id id) {
        return new Response.Failed(id, Failure.internalError("the server left this call unanswered"));
    }
}
