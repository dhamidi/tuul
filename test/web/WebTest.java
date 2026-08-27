package web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static web.serve.Memory.get;
import static web.serve.Memory.post;

import application.Step;
import harness.Check;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import web.assets.Assets;
import web.dispatch.Router;
import web.serve.Http;
import web.serve.Memory;
import web.ui.Tags;

public final class WebTest {

    private WebTest() {}

    public static void run() throws Exception {
        headers();
        parameters();
        requests();
        boundary();
        recording();
        routing();
        middleware();
        responses();
        pages();
        sockets();
        hangups();
        streaming();
    }

    private static void headers() {
        var headers = Headers.of("Content-Type", "text/html").add("Set-Cookie", "a=1").add("Set-Cookie", "b=2");
        Check.equal("a header is found however it is spelled",
                "text/html", headers.first("CONTENT-TYPE").orElse(""));
        Check.equal("a header that repeats keeps every value", List.of("a=1", "b=2"), headers.all("set-cookie"));
        Check.equal("and the spelling it was given survives", "Content-Type", headers.names().iterator().next());
        Check.equal("replacing finds the existing spelling",
                List.of("text/plain"), headers.with("content-type", "text/plain").all("Content-Type"));
        Check.that("removing removes it", !headers.without("SET-COOKIE").has("Set-Cookie"));
        Check.equal("a header nobody set is nothing", List.of(), headers.all("X-Missing"));
    }

    private static void parameters() {
        var params = Parameters.parse("q=a+b&tag=x&tag=y&debug&encoded=%2Fslash");
        Check.equal("a space arrives as a plus", "a b", params.first("q", ""));
        Check.equal("a name may repeat", List.of("x", "y"), params.all("tag"));
        Check.that("a name with no value is still there", params.has("debug"));
        Check.equal("and is empty rather than missing", "", params.first("debug", "missing"));
        Check.equal("percent escapes are decoded", "/slash", params.first("encoded", ""));
        Check.equal("as JSON, one value is a string and several are an array",
                "{\"q\":\"a b\",\"tag\":[\"x\",\"y\"],\"debug\":\"\",\"encoded\":\"/slash\"}",
                params.json().text());
        Check.equal("merging lets the later one win",
                "b", Parameters.parse("x=a").and(Parameters.parse("x=b")).first("x", ""));
        Check.equal("and it survives being written back out",
                "a b", Parameters.parse(Parameters.parse("q=a+b").encoded()).first("q", ""));
    }

    private static void requests() throws IOException {
        var request = Request.of("get", "/symbols/json.Json?q=x");
        Check.equal("the method is the method whatever case it arrived in", "GET", request.method());
        Check.equal("the query comes off the path", "/symbols/json.Json", request.path());
        Check.equal("and is parsed", "x", request.query().first("q", ""));

        var carried = request.with("user", "ada");
        Check.equal("a handler can leave something for the next one",
                "ada", carried.attribute("user", String.class).orElse(""));
        Check.that("and asking for the wrong type is nothing, not a crash",
                carried.attribute("user", Integer.class).isEmpty());
        Check.that("the request it was left on is unchanged", request.attribute("user", String.class).isEmpty());

        var form = post("/notes", "body=hello&_method=DELETE");
        Check.equal("a content type is read without its parameters",
                "application/x-www-form-urlencoded",
                Request.of("POST", "/", Headers.of("Content-Type", "application/X-WWW-Form-Urlencoded; charset=utf-8"),
                        Request.body("")).type());
        Check.equal("a form body is read as parameters", "hello", form.form().first("body", ""));
        Check.equal("a body that is not a form is not parsed as one",
                Parameters.NONE.values(),
                Request.of("POST", "/", Headers.of("Content-Type", "application/json"), Request.body("body=hello"))
                        .form().values());
        Check.equal("a request says what it is", "GET /symbols/json.Json?q=x", request.toString());
        Check.equal("a method can be rewritten", "DELETE", request.method("DELETE").method());
        Check.equal("and a path", "/mounted", request.path("/mounted").path());
    }

    /// The one thing a response interface has to get right.
    private static void boundary() {
        var recorded = Memory.handle((request, response) -> {
            Check.that("nothing has gone out before the body is asked for", !response.sent());
            response.status(201).header("X-Early", "yes");
            response.writer().write("body");
            Check.that("and everything has once it is", response.sent());
            Check.throwing("a status set afterwards is refused", () -> response.status(500));
            Check.throwing("and so is a header", () -> response.header("X-Late", "no"));
        }, get("/"));
        Check.equal("what was set before the boundary is what was sent", 201, recorded.status());
        Check.equal("including the headers", "yes", recorded.header("X-Early").orElse(""));
        Check.equal("and the body", "body", recorded.text());
    }

    private static void recording() {
        var thrown = Memory.handle((request, response) -> {
            throw new IllegalStateException("no");
        }, get("/"));
        Check.equal("a handler that throws is a 500", 500, thrown.status());
        Check.that("and the failure is kept rather than swallowed", thrown.failure().isPresent());

        var late = Memory.handle((request, response) -> {
            response.writer().write("half a page");
            response.flush();
            throw new IllegalStateException("too late");
        }, get("/"));
        Check.equal("a handler that throws after answering keeps what it said", 200, late.status());
        Check.equal("and what it wrote", "half a page", late.text());

        var head = Memory.handle((request, response) -> Responses.text("a body", response), Memory.head("/"));
        Check.equal("a HEAD gets the headers a GET would have",
                "text/plain; charset=utf-8", head.header("Content-Type").orElse(""));
        Check.equal("and the length", "6", head.header("Content-Length").orElse(""));
        Check.equal("and no body at all", "", head.text());

        var empty = Memory.handle((request, response) -> Responses.empty(Status.NO_CONTENT, response), get("/"));
        Check.equal("a 204 is a 204", 204, empty.status());
        Check.equal("with nothing in it", 0, empty.body().length);
    }

    private static void routing() throws Exception {
        var routes = Router.of()
                .get("symbol", "/symbols/{name}")
                .delete("symbol", "/symbols/{name}")
                .post("index", "/index")
                .get("orphan", "/orphan");
        var routing = Routing.of(routes)
                .on("symbol", (request, response) -> Responses.text(
                        Routing.route(request).orElse("?") + ":" + Routing.variables(request).first("name", ""),
                        response));

        Check.equal("a recognised route reaches its handler with what the path carried",
                "symbol:json.Json", Memory.handle(routing, get("/symbols/json.Json")).text());
        Check.equal("a path that is a route with another method is a 405",
                405, Memory.handle(routing, Request.of("PUT", "/symbols/x")).status());
        Check.equal("and says which methods it would take",
                "DELETE, GET, HEAD", Memory.handle(routing, Request.of("PUT", "/symbols/x")).header("Allow").orElse(""));
        Check.equal("a path in no route is a 404", 404, Memory.handle(routing, get("/nothing")).status());
        Check.equal("a route with no handler is a 404 too", 404, Memory.handle(routing, get("/orphan")).status());
        Check.equal("and a custom one answers instead",
                "gone",
                Memory.handle(routing.otherwise((request, response) -> Responses.text("gone", response)),
                        get("/nothing")).text());
        Check.throwing("handling a route that does not exist is refused where it is written",
                () -> Routing.of(routes).on("nope", (request, response) -> {}));
        Check.that("a request that never met a router carries no variables",
                Routing.variables(get("/")).isEmpty());
    }

    private static void middleware() {
        Handler method = (request, response) -> Responses.text(request.method(), response);
        var overriding = method.wrappedBy(Middlewares.methodOverride());
        Check.equal("a form may say it meant DELETE",
                "DELETE", Memory.handle(overriding, post("/notes/1", "_method=delete")).text());
        Check.equal("a GET may not, since that would make a link destructive",
                "GET", Memory.handle(overriding, get("/notes/1?_method=DELETE")).text());
        Check.equal("and neither may a POST claiming to be a GET",
                "POST", Memory.handle(overriding, post("/notes", "_method=GET")).text());

        Handler path = (request, response) -> Responses.text(request.path(), response);
        var mounted = path.wrappedBy(Middlewares.mountedAt("/app"));
        Check.equal("a mounted handler sees the path it was mounted at",
                "/notes", Memory.handle(mounted, get("/app/notes")).text());
        Check.equal("the mount point itself is the root", "/", Memory.handle(mounted, get("/app")).text());
        Check.equal("and anything outside it never arrives", 404, Memory.handle(mounted, get("/other")).status());

        var order = new StringBuilder();
        Middleware first = next -> (request, response) -> {
            order.append("first ");
            next.handle(request, response);
        };
        Middleware second = next -> (request, response) -> {
            order.append("second ");
            next.handle(request, response);
        };
        Memory.handle(((Handler) (request, response) -> order.append("handler"))
                .wrappedBy(Middleware.of(List.of(first, second))), get("/"));
        Check.equal("a stack runs outermost first", "first second handler", order.toString());
    }

    private static void responses() throws Exception {
        var page = Memory.handle((request, response) ->
                Responses.html(Tags.div(Tags.text("hello")), response), get("/"));
        Check.equal("html says it is html", "text/html; charset=utf-8", page.header("Content-Type").orElse(""));
        Check.equal("and is", "<div>hello</div>", page.text());

        var stream = Memory.handle((request, response) ->
                Responses.turbo(web.ui.Turbo.replace("x", Tags.div()), response), get("/"));
        Check.that("a turbo stream is told apart by its content type",
                stream.header("Content-Type").orElse("").startsWith(Responses.TURBO_STREAM));

        var away = Memory.handle((request, response) -> Responses.redirect("/elsewhere", response), post("/", ""));
        Check.equal("a redirect after a form is a 303, or Turbo repeats the POST", 303, away.status());
        Check.equal("and says where", "/elsewhere", away.header("Location").orElse(""));

        var directory = Files.createTempDirectory("web-assets");
        directory.toFile().deleteOnExit();
        Files.writeString(directory.resolve("app.css"), "body{color:red}");
        var assets = Assets.standard(List.of(directory));
        var served = Memory.handle((request, response) ->
                Responses.send(assets.serve(assets.url("app.css"), null), response), get("/"));
        Check.equal("an asset goes out with what the asset package decided", 200, served.status());
        Check.equal("its body", "body{color:red}", served.text());
        Check.that("and its caching",
                served.header("Cache-Control").orElse("").contains("immutable"));
    }

    /// A request becomes a message, an update returns a state, the state is
    /// rendered — the whole architecture, in one handler.
    private static void pages() throws Exception {
        var routes = Router.of().get("symbol", "/symbols/{name}");
        var page = Page.of(() -> "nothing")
                .on("symbol", (state, message) -> Step.of(
                        "looking", application.Effect.of("look")
                                .with("name", message.body().get("params").toString())))
                .on("found", (state, message) -> Step.of(message.string("name", "")))
                .effect("look", (effect, emit) -> emit.emit(application.Message.of("found")
                        .with("name", "found " + effect.string("name", ""))))
                .render((state, request, response) -> Responses.text(state, response));

        var answered = Memory.handle(Routing.of(routes).on("symbol", page), get("/symbols/json.Json?q=1"));
        Check.that("the route named the message, and the path and query reached the update: " + answered.text(),
                answered.text().contains("json.Json") && answered.text().contains("q"));

        var broken = Page.of(() -> "before")
                .on("symbol", (state, message) -> {
                    throw new IllegalStateException("no");
                })
                .render((state, request, response) -> Responses.text(state, response));
        Check.equal("an update that throws renders the state it had, rather than a stack trace",
                "before", Memory.handle(Routing.of(routes).on("symbol", broken), get("/symbols/x")).text());
    }

    /// The same handler, on a socket. If the two bindings disagree the
    /// interfaces are a lie.
    private static void sockets() throws Exception {
        Handler handler = (request, response) -> Responses.text(
                request.method() + " " + request.path() + " " + request.query().first("q", "-"), response);
        try (var server = Http.start(handler, 0)) {
            var client = HttpClient.newHttpClient();
            var uri = URI.create("http://localhost:" + server.port() + "/over/a/socket?q=1");
            var over = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
            Check.equal("a socket answers what memory answered",
                    Memory.handle(handler, get("/over/a/socket?q=1")).text(), over.body());
            Check.equal("with the status", 200, over.statusCode());
            Check.equal("and the content type",
                    "text/plain; charset=utf-8", over.headers().firstValue("content-type").orElse(""));

            var failing = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/boom")).build();
            try (var broken = Http.start((request, response) -> {
                throw new IllegalStateException("no");
            }, 0, ignored -> {})) {
                // A server that fails to answer at all drops the connection, and the client
                // throws rather than returning a status — which would abort the whole suite
                // instead of failing this one thing, so it is caught and named.
                var answer = -1;
                try {
                    answer = client.send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + broken.port() + "/boom")).build(),
                            HttpResponse.BodyHandlers.ofString()).statusCode();
                } catch (IOException dropped) {
                    Check.that("a handler that throws answers rather than dropping the connection: " + dropped, false);
                }
                Check.equal("a handler that throws is a 500 on a socket too", 500, answer);
            }
            Check.that("the request timeout is set, since the JDK's default is forever",
                    System.getProperty(Http.MAX_REQUEST_TIME) != null);
            Check.that("and the response timeout is not, because an event stream is a long response",
                    System.getProperty(Http.MAX_RESPONSE_TIME) == null);
            Check.that("a request the server never answers is still a request", failing.uri().getPath().equals("/boom"));
        }
    }

    /// A client that goes away is not a failure of this server.
    ///
    /// It is normal traffic — a page navigating away, a search box cancelling
    /// the request it made two keystrokes ago, an `EventSource` reconnecting —
    /// and reporting it filled the console with `Broken pipe` on every click.
    /// The test hangs up mid-response with a raw socket, because an HttpClient
    /// is too polite to reproduce it: it reads the whole body before it lets go.
    private static void hangups() throws Exception {
        var reported = new CopyOnWriteArrayList<Exception>();
        Handler large = (request, response) -> {
            response.header("Content-Type", "text/plain; charset=utf-8");
            var out = response.writer();
            for (var written = 0; written < 4096; written++) {
                out.write("a line long enough that the socket buffer fills before the client is done\n");
                out.flush();
            }
        };
        try (var server = Http.start(large, 0, reported::add)) {
            try (var socket = new Socket("localhost", server.port())) {
                socket.getOutputStream().write("GET /big HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(UTF_8));
                socket.getOutputStream().flush();
                Check.that("the server started answering", socket.getInputStream().read() >= 0);
                socket.setSoLinger(true, 0);
            }
            Thread.sleep(400);
            Check.equal("a client that hangs up is not reported as a failure: " + reported, List.of(), reported);
        }

        var broken = new CopyOnWriteArrayList<Exception>();
        try (var server = Http.start((request, response) -> {
            throw new IllegalStateException("no");
        }, 0, broken::add)) {
            try {
                HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/boom")).build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException ignored) {
                // the answer is not the point here; what reached the consumer is
            }
            Check.equal("while a handler that throws still is, or the filter has swallowed everything",
                    1, broken.size());
        }
    }

    /// A response that stays open. Without this the interface cannot carry
    /// `web.cable`, and every other property here is beside the point.
    private static void streaming() throws Exception {
        Handler ticking = (request, response) -> {
            var out = Responses.events(response);
            for (var tick = 1; tick <= 3; tick++) {
                eventstream.EventStream.write(new eventstream.Event("tick", String.valueOf(tick), ""), out);
                response.flush();
                Thread.sleep(10);
            }
        };

        try (var open = Memory.open(ticking, get("/events"))) {
            Check.equal("an event stream says what it is",
                    "text/event-stream; charset=utf-8", open.headers().first("Content-Type").orElse(""));
            Check.equal("and tells a proxy not to buffer it", "no", open.headers().first("X-Accel-Buffering").orElse(""));
            var reader = new BufferedReader(open.reader());
            var events = eventstream.EventStream.parse(reader).limit(2).toList();
            Check.equal("two events arrive before the handler has finished", 2, events.size());
            Check.equal("and are the events that were written",
                    "1", ((eventstream.Event) events.getFirst()).data());
        }

        var recorded = Memory.handle(ticking, get("/events"));
        Check.equal("the headers go out before the first event, and every event after that",
                4, recorded.pushes());
        Check.that("and each push carried something new rather than repeating the last",
                recorded.flushes().equals(recorded.flushes().stream().sorted().distinct().toList()));

        try (var server = Http.start(ticking, 0)) {
            var client = HttpClient.newHttpClient();
            var uri = URI.create("http://localhost:" + server.port() + "/events");
            try (var signals = client.send(HttpRequest.newBuilder(uri).build(),
                    eventstream.EventStream.body()).body()) {
                Check.equal("and the same stream arrives over a socket", 3L, signals.count());
            }
        }
    }
}
