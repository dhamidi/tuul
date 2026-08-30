package jsonrpc2;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import json.Json;
import json.JsonWriter;

/// A bidirectional JSON-RPC session on one [Transport].
///
/// There is no client end and no server end. This type sends calls, answers
/// calls, and matches each response to the call that asked for it. [Server]
/// is the method table and the one-document fold.
///
/// ```
/// var pipe = jsonrpc2.transport.Pipe.open();
/// try (var server = Conn.of(pipe.right()).answering(Server.of().method("ping", _ -> Json.of("pong")));
///         var client = Conn.of(pipe.left())) {
///     Thread.startVirtualThread(() -> {
///         try { server.listen(); } catch (IOException ignored) {}
///     });
///     var pong = client.call("ping");
/// }
/// ```
///
/// [#of(Transport)] does not read. The first [#call(String, json.Json)],
/// [#notify(String, json.Json)], [#request(String, json.Json)], [#batch(List)],
/// or [#listen] starts a receive loop on a virtual thread. Register methods
/// before that. A call that arrives before the method is registered is not
/// found.
///
/// The receive loop never runs a method. It classifies each member with
/// [Incoming#read(json.Json)] and forks a virtual thread for every call. A
/// method may therefore [#call(String, json.Json)] back without deadlocking.
/// Handlers for inbound calls run at the same time. A method that needs order
/// takes its own lock.
///
/// Writes take a lock, open [Transport#send], write one document, and close
/// the writer. [Transport#receive] may run at the same time as a send. Two
/// sends never overlap. A tight [#notify(String, json.Json)] loop delays every
/// other document in that direction, including a result.
///
/// [#call(String, json.Json, Duration)] that expires throws
/// [TimeoutException]. That error does not go on the wire. A response that
/// arrives after the timeout increments [#fenced] and is dropped.
public final class Conn implements Closeable {

    private final Transport transport;
    private final Server local = Server.of();
    private volatile Server answering = Server.of();
    private final AtomicLong ids = new AtomicLong();
    private final Object writes = new Object();
    private final ConcurrentHashMap<Id, CompletableFuture<Json>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Id, Thread> inbound = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<Id, Boolean> cancelled = ConcurrentHashMap.newKeySet();
    private final AtomicLong fenced = new AtomicLong();
    private final CompletableFuture<Void> fencedSignal = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private final ExecutorService methods = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Thread receiver;
    private volatile IOException failure;

    private Conn(Transport transport) {
        this.transport = transport;
    }

    public static Conn of(Transport transport) {
        return new Conn(transport);
    }

    /// Uses this server to answer inbound calls.
    ///
    /// [Conn#method(String, Method)] on this connection wins when both define
    /// the same name. Call this before the receive loop starts.
    public Conn answering(Server server) {
        this.answering = server;
        return this;
    }

    /// Adds a method that does not use this connection.
    public Conn method(String name, Method method) {
        local.method(name, method);
        return this;
    }

    /// Adds a method that may notify or call on this connection before it
    /// returns.
    public Conn method(String name, Handler handler) {
        local.method(name, handler);
        return this;
    }

    /// An id no other outbound call from this connection uses.
    public Id next() {
        return Id.of(ids.incrementAndGet());
    }

    public Json call(String method) throws IOException {
        return call(method, Json.NULL);
    }

    /// Sends a request and waits for the result.
    ///
    /// Throws a [Rejection] when the peer reports an error. Throws an
    /// [IOException] when the connection is closed before an answer arrives.
    public Json call(String method, Json params) throws IOException {
        try {
            return request(method, params).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /// Sends a request and waits up to `patience` for the result.
    ///
    /// Throws [TimeoutException] when the time runs out. The peer is not told.
    /// A later answer increments [#fenced] and is dropped.
    public Json call(String method, Json params, Duration patience) throws IOException, TimeoutException {
        var id = next();
        var future = new CompletableFuture<Json>();
        pending.put(id, future);
        try {
            post(List.of(Call.of(id, method, params)), false);
        } catch (IOException e) {
            pending.remove(id);
            throw e;
        }
        listenInBackground();
        try {
            return future.get(patience.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /// Sends a request and does not wait.
    ///
    /// The future completes with the result JSON, or completes exceptionally
    /// with a [Rejection] or an [IOException].
    public CompletableFuture<Json> request(String method, Json params) throws IOException {
        var id = next();
        var future = new CompletableFuture<Json>();
        pending.put(id, future);
        try {
            post(List.of(Call.of(id, method, params)), false);
        } catch (IOException e) {
            pending.remove(id);
            throw e;
        }
        listenInBackground();
        return future;
    }

    public void notify(String method) throws IOException {
        notify(method, Json.NULL);
    }

    /// Sends a call that has no answer, and does not wait for one.
    public void notify(String method, Json params) throws IOException {
        post(List.of(Call.of(method, params)), false);
        listenInBackground();
    }

    /// Sends several calls as one document.
    ///
    /// Returns the answers in the order of the requests in `calls`, whatever
    /// order they arrived in. A [Call.Notification] produces no entry. A
    /// request the peer left unanswered becomes an internal error against its
    /// own id.
    public List<Response> batch(List<Call> calls) throws IOException {
        var futures = new LinkedHashMap<Id, CompletableFuture<Json>>();
        for (var call : calls) {
            if (call instanceof Call.Request(var id, var _, var _)) {
                var future = new CompletableFuture<Json>();
                pending.put(id, future);
                futures.put(id, future);
            }
        }
        try {
            post(calls, true);
        } catch (IOException e) {
            futures.keySet().forEach(pending::remove);
            throw e;
        }
        listenInBackground();
        if (futures.isEmpty()) return List.of();
        var answers = new ArrayList<Response>();
        for (var entry : futures.entrySet()) {
            answers.add(resultOf(entry.getKey(), entry.getValue()));
        }
        return answers;
    }

    /// Marks an inbound request as cancelled and interrupts the thread that
    /// runs it.
    ///
    /// Nothing goes on the wire. A method that cares calls [#cancelled]. A
    /// method that ignores the flag runs to the end.
    public void cancel(Id inbound) {
        cancelled.add(inbound);
        var thread = this.inbound.get(inbound);
        if (thread != null) thread.interrupt();
    }

    /// Whether the inbound request running on this thread was cancelled.
    public boolean cancelled() {
        for (var entry : inbound.entrySet()) {
            if (entry.getValue() == Thread.currentThread()) return cancelled.contains(entry.getKey());
        }
        return false;
    }

    /// Starts the receive loop if needed and waits until the transport ends.
    ///
    /// After [#receive] answers empty, this method waits until inbound methods
    /// have finished sending their answers. Then it fails every outbound call
    /// that still has no response.
    public void listen() throws IOException {
        listenInBackground();
        try {
            finished.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        }
        if (failure != null) throw failure;
    }

    /// Answers that arrived after a timeout or after the call was no longer
    /// waiting. They were dropped.
    public long fenced() {
        return fenced.get();
    }

    boolean awaitFenced(Duration patience) throws InterruptedException {
        try {
            fencedSignal.get(patience.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException timeout) {
            return false;
        } catch (ExecutionException impossible) {
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try {
            transport.close();
        } finally {
            var thread = receiver;
            if (thread != null) thread.interrupt();
            methods.shutdownNow();
            failPending();
            finished.complete(null);
        }
    }

    private void listenInBackground() {
        if (!started.compareAndSet(false, true)) return;
        receiver = Thread.startVirtualThread(this::receiveLoop);
    }

    private void receiveLoop() {
        try {
            while (!closed.get()) {
                var incoming = transport.receive();
                if (incoming.isEmpty()) break;
                try {
                    accept(incoming.get());
                } catch (Rejection rejection) {
                    sendFailed(Id.NOTHING, rejection.failure());
                }
            }
        } catch (IOException e) {
            if (!closed.get()) failure = e;
        } finally {
            failPending();
            methods.shutdown();
            try {
                methods.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.complete(null);
        }
    }

    private void accept(Reader in) throws IOException {
        var document = Jsonrpc2.read(in);
        var members = document.members();
        if (members.isEmpty()) {
            sendFailed(Id.NOTHING, Failure.invalidRequest("a batch must have members"));
            return;
        }
        if (!document.batched()) {
            switch (Incoming.read(members.getFirst())) {
                case Incoming.Reply(var response) -> deliver(response);
                case Incoming.Invoke(var call) -> methods.execute(() -> invoke(call).ifPresent(this::sendOne));
                case Incoming.Invalid(var failure, var id, var silent) -> fail(failure, id, silent);
            }
            return;
        }
        var work = new ArrayList<Integer>();
        for (var at = 0; at < members.size(); at++) {
            switch (Incoming.read(members.get(at))) {
                case Incoming.Reply(var response) -> deliver(response);
                case Incoming.Invoke _, Incoming.Invalid _ -> work.add(at);
            }
        }
        if (work.isEmpty()) return;
        var snapshot = List.copyOf(members);
        methods.execute(() -> answerBatch(snapshot, work));
    }

    private void answerBatch(List<Json> members, List<Integer> work) {
        var answers = new ConcurrentHashMap<Integer, Response>();
        try (var handling = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var at : work) {
                var member = members.get(at);
                var position = at;
                handling.execute(() -> answer(member).ifPresent(response -> answers.put(position, response)));
            }
        }
        var ordered = List.copyOf(new TreeMap<>(answers).values());
        if (ordered.isEmpty()) return;
        try {
            sendResponses(ordered, true);
        } catch (IOException ignored) {
        }
    }

    private Optional<Response> answer(Json member) {
        return switch (Incoming.read(member)) {
            case Incoming.Reply(var response) -> {
                deliver(response);
                yield Optional.empty();
            }
            case Incoming.Invoke(var call) -> invoke(call);
            case Incoming.Invalid(var failure, var id, var silent) -> {
                if (silent) {
                    fail(failure, id, true);
                    yield Optional.empty();
                }
                yield Optional.of(new Response.Failed(id, failure));
            }
        };
    }

    private void fail(Failure failure, Id id, boolean silent) {
        if (silent) {
            var future = pending.remove(id);
            if (future != null) future.completeExceptionally(Rejection.of(failure));
            else if (id != Id.NOTHING) fenced.incrementAndGet();
            return;
        }
        sendOne(new Response.Failed(id, failure));
    }

    private Optional<Response> invoke(Call call) {
        Optional<Id> id = call instanceof Call.Request(var caller, var _, var _)
                ? Optional.of(caller)
                : Optional.empty();
        id.ifPresent(caller -> inbound.put(caller, Thread.currentThread()));
        try {
            var handler = local.handler(call.method()).or(() -> answering.handler(call.method()));
            if (handler.isEmpty()) {
                return id.map(caller -> new Response.Failed(caller, Failure.methodNotFound(call.method())));
            }
            try {
                var value = handler.get().call(call.params(), this);
                return id.map(caller -> new Response.Result(caller, value == null ? Json.NULL : value));
            } catch (Rejection rejection) {
                return id.map(caller -> new Response.Failed(caller, rejection.failure()));
            } catch (Exception e) {
                return id.map(caller -> new Response.Failed(caller, Failure.internalError(reason(e))));
            }
        } finally {
            id.ifPresent(inbound::remove);
        }
    }

    private void deliver(Response response) {
        var future = pending.remove(response.id());
        if (future == null) {
            fenced.incrementAndGet();
            fencedSignal.complete(null);
            return;
        }
        switch (response) {
            case Response.Result(var _, var value) -> future.complete(value);
            case Response.Failed(var _, var failure) -> future.completeExceptionally(Rejection.of(failure));
        }
    }

    private void post(List<Call> calls, boolean batched) throws IOException {
        synchronized (writes) {
            ensureOpen();
            try (Writer out = transport.send()) {
                var writer = new JsonWriter(out);
                if (batched) writer.beginArray();
                for (var call : calls) call.write(writer);
                if (batched) writer.endArray();
                writer.flush();
            }
        }
    }

    private void sendOne(Response response) {
        try {
            sendResponses(List.of(response), false);
        } catch (IOException ignored) {
        }
    }

    private void sendFailed(Id id, Failure failure) {
        try {
            sendResponses(List.of(new Response.Failed(id, failure)), false);
        } catch (IOException ignored) {
        }
    }

    private void sendResponses(List<Response> responses, boolean batched) throws IOException {
        synchronized (writes) {
            if (closed.get()) return;
            try (Writer out = transport.send()) {
                Jsonrpc2.write(responses, batched, out);
            }
        }
    }

    private void failPending() {
        var leftover = List.copyOf(pending.keySet());
        for (var id : leftover) {
            var future = pending.remove(id);
            if (future != null) {
                future.completeExceptionally(Rejection.of(Failure.internalError("the transport carried no answer")));
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) throw new IOException("the connection is closed");
        if (finished.isDone() && started.get()) throw new IOException("the connection is closed");
    }

    private static Response resultOf(Id id, CompletableFuture<Json> future) {
        try {
            return new Response.Result(id, future.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unanswered(id);
        } catch (ExecutionException e) {
            return switch (e.getCause()) {
                case Rejection rejection -> new Response.Failed(id, rejection.failure());
                default -> unanswered(id);
            };
        }
    }

    private static Response unanswered(Id id) {
        return new Response.Failed(id, Failure.internalError("the transport carried no answer"));
    }

    private static IOException unwrap(ExecutionException e) throws IOException {
        return switch (e.getCause()) {
            case Rejection rejection -> throw rejection;
            case IOException io -> io;
            case RuntimeException runtime -> throw runtime;
            case null, default -> new IOException(e.getCause());
        };
    }

    private static String reason(Exception e) {
        var cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
