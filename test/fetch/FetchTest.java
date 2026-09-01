package fetch;

import com.sun.net.httpserver.HttpServer;
import eventstream.Event;
import eventstream.Retry;
import harness.Check;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

public final class FetchTest {
    private FetchTest() {}

    public static void run() throws Exception {
        modelsHeadersAndForms();
        writesEventStreamBodies();
        readsEventStreamResponses();
    }

    public static void integration() throws Exception {
        sendsStreamsCookiesAndRedirects();
    }

    private static void modelsHeadersAndForms() throws Exception {
        var headers = Headers.of("Content-Type", "text/plain").add("X-Value", "one").add("x-value", "two");
        Check.equal("header names ignore case", java.util.List.of("one", "two"), headers.all("X-VALUE"));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("q", "a b");
        fields.put("tag", new String[] {"x", "y"});
        Check.equal("form keeps repeated fields", "q=a+b&tag=x&tag=y", Body.form(Form.of(fields)).text(StandardCharsets.UTF_8));
    }

    private static void writesEventStreamBodies() throws Exception {
        var closed = new AtomicBoolean();
        var body = Body.eventStream(Stream.of(new Retry(1500), Event.of("tick", "one"), Event.of("two")).onClose(() -> closed.set(true)));
        Check.that("an event stream request body is one-shot", !body.repeatable());
        Check.that("an event stream request body has no fixed length", body.length().isEmpty());
        Check.equal("an event stream request body writes signals in order",
                "retry: 1500\nevent: tick\ndata: one\n\ndata: two\n\n",
                body.text(StandardCharsets.UTF_8));
        Check.that("consuming an event stream request body closes its source", closed.get());

        var failed = false;
        try {
            Body.eventStream(Stream.of(Event.of("one\rtwo"))).bytes();
        } catch (IOException e) {
            failed = e.getCause() instanceof IllegalArgumentException;
        }
        Check.that("an event stream serialization failure reaches the body consumer", failed);

        try (var fetch = Fetch.sequential(); var session = fetch.session()) {
            var request = session.post(URI.create("https://example.com/events"), Body.empty())
                    .eventStream(Stream.of(Event.of("hello")));
            Check.equal("the request helper sets the event stream content type",
                    "text/event-stream", request.headers().first("Content-Type", ""));
            Check.that("the request helper creates a one-shot body", !request.body().repeatable());
        }
    }

    private static void readsEventStreamResponses() throws Exception {
        var bytes = "retry: 2000\n\nevent: tick\ndata: one\n\ndata: two\ndata: three\n\n"
                .getBytes(StandardCharsets.UTF_8);
        try (var fetch = Fetch.sequential(); var session = fetch.session()) {
            var request = session.get(URI.create("https://example.com/events"));
            try (var response = Response.create(request, 200, request.uri(),
                    Headers.of("Content-Type", "text/event-stream; charset=ISO-8859-1"),
                    new ByteArrayInputStream(bytes), List.of());
                 var signals = response.eventStream()) {
                Check.equal("an event stream response returns all signals in wire order",
                        List.of(new Retry(2000), Event.of("tick", "one"), Event.of("two\nthree")),
                        signals.toList());
            }
        }
    }

    private static void sendsStreamsCookiesAndRedirects() throws Exception {
        var eventRequest = new AtomicReference<String>();
        var eventContentType = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "token=yes; Path=/");
            exchange.getResponseHeaders().add("Location", "/result");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/result", exchange -> {
            var value = exchange.getRequestHeaders().getFirst("Cookie") + ":hello";
            var encoded = new java.io.ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(encoded)) { gzip.write(value.getBytes(StandardCharsets.UTF_8)); }
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, encoded.size());
            exchange.getResponseBody().write(encoded.toByteArray());
            exchange.close();
        });
        server.createContext("/events", exchange -> {
            eventContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            eventRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var body = "retry: 1000\n\ndata: received\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var fetch = Fetch.virtualThreads(); var session = fetch.session().redirects(Redirects.BROWSER)) {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/start");
            try (var response = session.get(uri).send()) {
                Check.equal("redirect returns the final status", 200, response.status());
                Check.equal("redirect records one hop", 1, response.history().size());
                Check.equal("cookie reaches redirected request and gzip is decoded", "token=yes:hello", response.text());
            }
            var events = uri.resolve("/events");
            try (var response = session.post(events, Body.empty())
                    .eventStream(Stream.of(Event.of("sent")))
                    .send();
                 var signals = response.eventStream()) {
                Check.equal("an event stream response parses through the HTTP transport",
                        List.of(new Retry(1000), Event.of("received")), signals.toList());
            }
            Check.equal("an event stream request sends the event stream content type",
                    "text/event-stream", eventContentType.get());
            Check.equal("an event stream request sends serialized signals",
                    "data: sent\n\n", eventRequest.get());
        } finally { server.stop(0); }
    }
}
