package jsonrpc2;

import harness.Check;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import json.Json;
import jsonrpc2.transport.Memory;
import jsonrpc2.transport.Pipe;

public final class Jsonrpc2Test {

    private Jsonrpc2Test() {}

    public static void run() throws Exception {
        calls();
        identity();
        reserved();
        notifications();
        batches();
        concurrency();
        correlation();
        transport();
        session();
    }

    private static void calls() throws IOException {
        Check.equal("a positional call answers with its result",
                "{\"jsonrpc\":\"2.0\",\"result\":19,\"id\":1}",
                answer("{\"jsonrpc\": \"2.0\", \"method\": \"subtract\", \"params\": [42, 23], \"id\": 1}"));
        Check.equal("named parameters reach the same method",
                "{\"jsonrpc\":\"2.0\",\"result\":19,\"id\":3}",
                answer("{\"jsonrpc\":\"2.0\",\"method\":\"subtract\","
                        + "\"params\":{\"subtrahend\":23,\"minuend\":42},\"id\":3}"));
        Check.equal("a call without parameters is a call",
                "{\"jsonrpc\":\"2.0\",\"result\":\"pong\",\"id\":7}",
                answer("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":7}"));
        Check.equal("a result that is null is still a result",
                "{\"jsonrpc\":\"2.0\",\"result\":null,\"id\":7}",
                answer("{\"jsonrpc\":\"2.0\",\"method\":\"void\",\"id\":7}"));
    }

    private static void identity() throws IOException {
        Check.equal("a string id comes back as a string",
                "{\"jsonrpc\":\"2.0\",\"result\":\"pong\",\"id\":\"abc\"}",
                answer("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":\"abc\"}"));
        Check.equal("a whole number id gains no fractional part",
                "{\"jsonrpc\":\"2.0\",\"result\":\"pong\",\"id\":42}",
                answer("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":42}"));
        Check.equal("a call with a null id is a call and gets an answer",
                "{\"jsonrpc\":\"2.0\",\"result\":\"pong\",\"id\":null}",
                answer("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":null}"));
        Check.that("the id 1 and the id \"1\" are different calls", !Id.of(1).equals(Id.of("1")));
        Check.equal("an id is compared by its JSON value", Id.of(1), Id.of(1.0));
        Check.equal("a text id keeps its text", Json.of("abc"), Id.of("abc").json());
        Check.equal("a null id is the JSON null", Json.NULL, Id.NOTHING.json());
        Check.that("an id cannot be a boolean", Id.read(Json.TRUE).isEmpty());
        Check.that("an absent id is not an id", Id.read(null).isEmpty());
    }

    private static void reserved() throws IOException {
        Check.equal("text that is not JSON is a parse error", Failure.PARSE_ERROR,
                failure("{\"jsonrpc\": \"2.0\", \"method\": \"foobar, \"params\": \"bar\", \"baz]").code());
        Check.equal("a parse error names no caller", Id.NOTHING,
                response("{\"jsonrpc\": \"2.0\", \"method\": \"foobar, \"params\": \"bar\", \"baz]").id());
        Check.equal("a method name that is not a string is an invalid request", Failure.INVALID_REQUEST,
                failure("{\"jsonrpc\": \"2.0\", \"method\": 1, \"params\": \"bar\"}").code());
        Check.equal("a document that is not an object is an invalid request", Failure.INVALID_REQUEST,
                failure("\"a bare string\"").code());
        Check.equal("another protocol version is an invalid request", Failure.INVALID_REQUEST,
                failure("{\"jsonrpc\":\"1.0\",\"method\":\"ping\",\"id\":1}").code());
        Check.equal("an id that is an array is an invalid request", Failure.INVALID_REQUEST,
                failure("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":[1]}").code());
        Check.equal("an unreadable id becomes a null id", Id.NOTHING,
                response("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":[1]}").id());
        Check.equal("an unknown method is not found", Failure.METHOD_NOT_FOUND,
                failure("{\"jsonrpc\":\"2.0\",\"method\":\"foobar\",\"id\":\"1\"}").code());
        Check.equal("the failure names the method that is missing", Json.of("foobar"),
                failure("{\"jsonrpc\":\"2.0\",\"method\":\"foobar\",\"id\":\"1\"}").data());
        Check.equal("params that are neither an array nor an object are invalid params",
                Failure.INVALID_PARAMS,
                failure("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"params\":\"bar\",\"id\":1}").code());
        Check.equal("a method may reject its arguments itself", Failure.INVALID_PARAMS,
                failure("{\"jsonrpc\":\"2.0\",\"method\":\"picky\",\"params\":[1],\"id\":1}").code());
        Check.equal("a method that throws is an internal error", Failure.INTERNAL_ERROR,
                failure("{\"jsonrpc\":\"2.0\",\"method\":\"boom\",\"id\":1}").code());
        Check.equal("the internal error keeps what the method said", Json.of("the disk is on fire"),
                failure("{\"jsonrpc\":\"2.0\",\"method\":\"boom\",\"id\":1}").data());
        Check.equal("a method may answer with a server-defined code", -32001,
                failure("{\"jsonrpc\":\"2.0\",\"method\":\"busy\",\"id\":1}").code());
        Check.equal("the lowest server code is allowed", -32099, Failure.server(-32099, "low").code());
        Check.equal("the highest server code is allowed", -32000, Failure.server(-32000, "high").code());
        Check.throwing("a server code below the block is refused", () -> Failure.server(-32100, "too low"));
        Check.throwing("a server code above the block is refused", () -> Failure.server(-31999, "too high"));
        Check.that("the protocol owns the reserved block", Failure.of(-32768, "reserved").reserved());
        Check.that("an application code outside the block is its own", !Failure.of(-1, "mine").reserved());
    }

    private static void notifications() throws IOException {
        var server = server();
        Check.equal("a notification produces no document at all", "",
                answer(server, "{\"jsonrpc\":\"2.0\",\"method\":\"update\",\"params\":[1,2,3,4,5]}"));
        Check.equal("the notification still ran", 1, updates.size());
        Check.equal("a notification to an unknown method stays silent", "",
                answer(server, "{\"jsonrpc\":\"2.0\",\"method\":\"foobar\",\"params\":[1,2,3]}"));
        Check.equal("a notification that fails stays silent", "",
                answer(server, "{\"jsonrpc\":\"2.0\",\"method\":\"boom\"}"));
        Check.equal("bad parameters in a notification stay silent", "",
                answer(server, "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"params\":\"bar\"}"));
    }

    private static void batches() throws IOException {
        Check.equal("an empty batch is one invalid request, not an empty array",
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Invalid Request\","
                        + "\"data\":\"a batch must have members\"},\"id\":null}",
                answer("[]"));
        Check.equal("a batch of one thing that is not a call answers with an array of one", 1,
                members(answer("[1]")).size());
        Check.equal("every member of a batch is answered on its own", 3, members(answer("[1,2,3]")).size());
        Check.equal("a batch of notifications produces no document at all", "",
                answer("[{\"jsonrpc\":\"2.0\",\"method\":\"update\",\"params\":[1]},"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"update\",\"params\":[2]}]"));
        Check.equal("a batch answers every call and skips every notification",
                "[{\"jsonrpc\":\"2.0\",\"result\":7,\"id\":\"1\"},"
                        + "{\"jsonrpc\":\"2.0\",\"result\":19,\"id\":\"2\"},"
                        + "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Invalid Request\","
                        + "\"data\":\"a call must declare jsonrpc 2.0\"},\"id\":null},"
                        + "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,\"message\":\"Method not found\","
                        + "\"data\":\"foo.get\"},\"id\":\"5\"},"
                        + "{\"jsonrpc\":\"2.0\",\"result\":[\"hello\",5],\"id\":\"9\"}]",
                answer("""
                        [{"jsonrpc": "2.0", "method": "sum", "params": [1,2,4], "id": "1"},
                         {"jsonrpc": "2.0", "method": "update", "params": [7]},
                         {"jsonrpc": "2.0", "method": "subtract", "params": [42,23], "id": "2"},
                         {"foo": "boo"},
                         {"jsonrpc": "2.0", "method": "foo.get", "params": {"name": "myself"}, "id": "5"},
                         {"jsonrpc": "2.0", "method": "get_data", "id": "9"}]"""));
    }

    /// The members of a batch run at the same time, so this batch only finishes
    /// when all four of its calls have arrived. A server that ran them one
    /// after another would wait out the timeout and answer false.
    private static void concurrency() throws IOException {
        var together = new CountDownLatch(4);
        var server = Server.of().method("gather", params -> {
            together.countDown();
            return Json.of(together.await(5, TimeUnit.SECONDS));
        });
        var document = answer(server, """
                [{"jsonrpc":"2.0","method":"gather","id":1},
                 {"jsonrpc":"2.0","method":"gather","id":2},
                 {"jsonrpc":"2.0","method":"gather","id":3},
                 {"jsonrpc":"2.0","method":"gather","id":4}]""");
        Check.equal("the members of a batch run at the same time",
                "[{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":1},"
                        + "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":2},"
                        + "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":3},"
                        + "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":4}]",
                document);
    }

    private static void correlation() throws IOException {
        var transport = Memory.of("[{\"jsonrpc\":\"2.0\",\"result\":\"second\",\"id\":2},"
                + "{\"jsonrpc\":\"2.0\",\"result\":\"first\",\"id\":1}]");
        try (var client = Conn.of(transport)) {
            var answers = client.batch(List.<Call>of(
                    Call.of(Id.of(1), "first", Json.NULL),
                    Call.of(Id.of(2), "second", Json.NULL),
                    Call.of("told", Json.NULL)));
            Check.equal("a batch is written as one array",
                    "[{\"jsonrpc\":\"2.0\",\"method\":\"first\",\"id\":1},"
                            + "{\"jsonrpc\":\"2.0\",\"method\":\"second\",\"id\":2},"
                            + "{\"jsonrpc\":\"2.0\",\"method\":\"told\"}]",
                    transport.sent().getFirst());
            Check.equal("a notification asks for no answer", 2, answers.size());
            Check.equal("an answer out of order still belongs to the call that asked",
                    new Response.Result(Id.of(1), Json.of("first")), answers.get(0));
            Check.equal("the second call gets the answer that carries its id",
                    new Response.Result(Id.of(2), Json.of("second")), answers.get(1));
        }

        var thin = Memory.of("[{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":1}]");
        try (var client = Conn.of(thin)) {
            var missing = client.batch(List.<Call>of(
                    Call.of(Id.of(1), "here", Json.NULL),
                    Call.of(Id.of(2), "gone", Json.NULL)));
            Check.equal("a call the server never answered is reported against its own id",
                    new Response.Failed(Id.of(2),
                            Failure.internalError("the transport carried no answer")),
                    missing.get(1));
        }
    }

    private static void transport() throws Exception {
        var notified = new CountDownLatch(1);
        var methods = server(notified);
        peers(methods, (client, _) -> {
            Check.equal("a call travels over a transport and comes back",
                    Json.of(19), client.call("subtract", Json.Array.of(List.of(Json.of(42), Json.of(23)))));
            var before = updates.size();
            client.notify("update", Json.Array.of(List.of(Json.of(1))));
            Check.that("a notification runs on the server", notified.await(5, TimeUnit.SECONDS));
            Check.equal("the notification updates the server once", before + 1, updates.size());
            Check.throwing("a failure on the server becomes a rejection here",
                    () -> quietly(() -> client.call("boom")));
        });

        var serving = Memory.of("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"update\",\"params\":[1]}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":2}");
        try (var conn = Conn.of(serving).answering(server())) {
            conn.listen();
        }
        Check.equal("listening answers every document that asks for an answer",
                List.of("{\"jsonrpc\":\"2.0\",\"result\":\"pong\",\"id\":1}",
                        "{\"jsonrpc\":\"2.0\",\"result\":\"pong\",\"id\":2}"),
                serving.sent());
    }

    private static void session() throws Exception {
        peers(Server.of().method("ping", _ -> Json.of("pong")), (client, _) -> {
            var first = client.request("ping", Json.NULL);
            var second = client.request("ping", Json.NULL);
            Check.equal("two calls in flight both return", Json.of("pong"), first.get());
            Check.equal("the second in-flight call returns too", Json.of("pong"), second.get());
        });

        var progress = new CopyOnWriteArrayList<Json>();
        var seen = new CountDownLatch(2);
        peers(Server.of().method("work", (params, conn) -> {
            conn.notify("progress", Json.Object.of().with("n", 1));
            conn.notify("progress", Json.Object.of().with("n", 2));
            return Json.of("done");
        }), (client, _) -> {
            client.method("progress", params -> {
                progress.add(params);
                seen.countDown();
                return Json.NULL;
            });
            Check.equal("a method may notify before it returns", Json.of("done"), client.call("work"));
            Check.that("both progress notifications arrived", seen.await(5, TimeUnit.SECONDS));
            Check.that("both progress values arrived",
                    progress.contains(Json.Object.of().with("n", 1))
                            && progress.contains(Json.Object.of().with("n", 2)));
        });

        peers(Server.of().method("work", (params, conn) -> conn.call("roots", Json.NULL)), (client, _) -> {
            client.method("roots", _ -> Json.of("here"));
            Check.equal("a method may call back without deadlocking", Json.of("here"), client.call("work"));
            var answers = client.batch(List.of(
                    Call.of(client.next(), "work", Json.NULL),
                    Call.of(client.next(), "work", Json.NULL)));
            Check.equal("a reverse call from a batch member does not deadlock",
                    Json.of("here"), ((Response.Result) answers.getFirst()).value());
            Check.equal("the other batch member called back too",
                    Json.of("here"), ((Response.Result) answers.get(1)).value());
        });

        var logs = new CountDownLatch(20);
        var ticks = new CountDownLatch(20);
        peers(Server.of()
                .method("log", params -> {
                    logs.countDown();
                    return Json.NULL;
                })
                .method("work", (params, conn) -> {
                    for (var i = 0; i < 20; i++) conn.notify("progress", Json.Object.of().with("n", i));
                    return conn.call("roots", Json.NULL);
                }), (client, _) -> {
            client.method("progress", params -> {
                ticks.countDown();
                return Json.NULL;
            });
            client.method("roots", _ -> Json.of("here"));
            var flooding = Thread.startVirtualThread(() -> {
                try {
                    for (var i = 0; i < 20; i++) client.notify("log", Json.Object.of().with("n", i));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            Check.equal("a notify storm both ways still returns the reverse call",
                    Json.of("here"), client.call("work"));
            flooding.join();
            Check.that("client log notifies arrived", logs.await(5, TimeUnit.SECONDS));
            Check.that("server progress notifies arrived", ticks.await(5, TimeUnit.SECONDS));
        });

        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        peers(Server.of().method("slow", params -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Json.of("late");
        }), (client, _) -> {
            try {
                client.call("slow", Json.NULL, Duration.ofMillis(20));
                Check.that("a slow call timed out", false);
            } catch (TimeoutException e) {
                Check.that("a slow call timed out", true);
            }
            Check.that("the slow method started", started.await(5, TimeUnit.SECONDS));
            release.countDown();
            Check.that("the late answer was fenced", client.awaitFenced(Duration.ofSeconds(5)));
        });
    }

    @FunctionalInterface
    private interface Peers {
        void run(Conn client, Conn server) throws Exception;
    }

    private static void peers(Server methods, Peers body) throws Exception {
        var pipe = Pipe.open();
        try (var remote = Conn.of(pipe.right()).answering(methods);
                var local = Conn.of(pipe.left())) {
            var serving = Thread.startVirtualThread(() -> {
                try {
                    remote.listen();
                } catch (IOException ignored) {
                }
            });
            try {
                body.run(local, remote);
            } finally {
                local.close();
                serving.join();
            }
        }
    }

    private static final List<Json> updates = new ArrayList<>();

    /// The server every test above talks to. The methods are the ones the
    /// specification uses in its own examples, plus the three ways a call can
    /// fail from inside a method.
    private static Server server() {
        return server(null);
    }

    private static Server server(CountDownLatch notified) {
        updates.clear();
        return Server.of()
                .method("subtract", params -> Json.of(minuend(params) - subtrahend(params)))
                .method("sum", params -> Json.of(((Json.Array) params).items().stream()
                        .mapToDouble(item -> ((Json.Num) item).value()).sum()))
                .method("ping", _ -> Json.of("pong"))
                .method("void", _ -> null)
                .method("get_data", _ -> Json.Array.of(List.of(Json.of("hello"), Json.of(5))))
                .method("update", params -> {
                    synchronized (updates) {
                        updates.add(params);
                    }
                    if (notified != null) notified.countDown();
                    return Json.NULL;
                })
                .method("picky", _ -> {
                    throw Rejection.of(Failure.invalidParams("two numbers, please"));
                })
                .method("busy", _ -> {
                    throw Rejection.of(Failure.server(-32001, "Server error"));
                })
                .method("boom", _ -> {
                    throw new IllegalStateException("the disk is on fire");
                });
    }

    private static double minuend(Json params) {
        return switch (params) {
            case Json.Array(var items) -> ((Json.Num) items.getFirst()).value();
            case Json.Object object -> ((Json.Num) object.get("minuend")).value();
            default -> throw Rejection.of(Failure.invalidParams("two numbers, please"));
        };
    }

    private static double subtrahend(Json params) {
        return switch (params) {
            case Json.Array(var items) -> ((Json.Num) items.get(1)).value();
            case Json.Object object -> ((Json.Num) object.get("subtrahend")).value();
            default -> throw Rejection.of(Failure.invalidParams("two numbers, please"));
        };
    }

    private static String answer(String document) throws IOException {
        return answer(server(), document);
    }

    /// Runs one document through a server and checks, every single time, that
    /// the server told the truth about whether it wrote anything.
    private static String answer(Server server, String document) throws IOException {
        var out = new StringWriter();
        var wrote = server.handle(new StringReader(document), out);
        Check.that("a server reports whether it answered", wrote != out.toString().isEmpty());
        return out.toString();
    }

    /// The answer a server gave to one document, read back as a response.
    private static Response response(String document) throws IOException {
        return Response.read(Json.parse(answer(document)));
    }

    private static Failure failure(String document) throws IOException {
        return switch (response(document)) {
            case Response.Failed(var _, var failure) -> failure;
            case Response.Result(var _, var value) -> throw new IllegalStateException("a result: " + value.text());
        };
    }

    private static List<Json> members(String document) {
        return ((Json.Array) Json.parse(document)).items();
    }

    private static void quietly(Body body) {
        try {
            body.run();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run() throws IOException;
    }
}
