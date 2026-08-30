package eventstream;

import com.sun.net.httpserver.HttpServer;
import harness.Check;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class EventStreamTest {

    private static final String BOM = "﻿";

    private EventStreamTest() {}

    public static void run() throws IOException, InterruptedException {
        events();
        fields();
        ids();
        retries();
        terminators();
        incomplete();
        writes();
        roundTrip();
        lazily();
        closes();
    }

    public static void integration() throws IOException, InterruptedException {
        overHttp();
    }

    private static void events() {
        Check.equal("a blank line ends an event", List.of(Event.of("hello")), parse("data: hello\n\n"));
        Check.equal("data lines are joined with a newline, not a space",
                List.of(Event.of("one\ntwo")),
                parse("data: one\ndata: two\n\n"));
        Check.equal("an event says what type it is", List.of(Event.of("tick", "now")), parse("event: tick\ndata: now\n\n"));
        Check.equal("and several arrive in order",
                List.of(Event.of("one"), Event.of("two")),
                parse("data: one\n\ndata: two\n\n"));
        Check.equal("an event with no data dispatches nothing", List.of(), parse("event: tick\n\n"));
        Check.equal("nor does a blank line on its own", List.of(), parse("\n\n\n"));
    }

    private static void fields() {
        Check.equal("one space after the colon belongs to the format",
                List.of(Event.of("hello")),
                parse("data:hello\n\n"));
        Check.equal("and only one, the second belongs to the value",
                List.of(Event.of(" hello")),
                parse("data:  hello\n\n"));
        Check.equal("a field with no colon has an empty value", List.of(Event.of("")), parse("data\n\n"));
        Check.equal("a line beginning with a colon is a comment",
                List.of(Event.of("hello")),
                parse(": keeping the connection warm\ndata: hello\n\n"));
        Check.equal("a field nobody knows is ignored rather than refused",
                List.of(Event.of("hello")),
                parse("nonsense: 1\ndata: hello\nalso-nonsense\n\n"));
        Check.equal("a trailing space in a value is the value's", List.of(Event.of("hello ")), parse("data: hello \n\n"));
    }

    private static void ids() {
        Check.equal("an id is carried by the event that set it",
                List.of(new Event(Event.MESSAGE, "one", "7")),
                parse("id: 7\ndata: one\n\n"));
        Check.equal("and stays in force for the events after it",
                List.of(new Event(Event.MESSAGE, "one", "7"), new Event(Event.MESSAGE, "two", "7")),
                parse("id: 7\ndata: one\n\ndata: two\n\n"));
        Check.equal("an id on its own still applies to what follows",
                List.of(new Event(Event.MESSAGE, "later", "7")),
                parse("id: 7\n\ndata: later\n\n"));
        Check.equal("an id containing a NUL is ignored, and the one in force stays",
                List.of(new Event(Event.MESSAGE, "one", "7")),
                parse("id: 7\nid: bad\0id\ndata: one\n\n"));
        Check.equal("an empty id clears the one in force",
                List.of(new Event(Event.MESSAGE, "one", "")),
                parse("id: 7\nid\ndata: one\n\n"));
    }

    private static void retries() {
        Check.equal("a reconnection time arrives on its own",
                List.of(new Retry(2500), Event.of("one")),
                parse("retry: 2500\ndata: one\n\n"));
        Check.equal("one that is not a number is ignored", List.of(Event.of("one")), parse("retry: soon\ndata: one\n\n"));
        Check.equal("so is one with a sign", List.of(Event.of("one")), parse("retry: -1\ndata: one\n\n"));
        Check.equal("so is one too long to be a time",
                List.of(Event.of("one")),
                parse("retry: 99999999999999999999999\ndata: one\n\n"));
    }

    private static void terminators() throws IOException {
        Check.equal("a stream can end its lines with LF", List.of(Event.of("a")), parse("data: a\n\n"));
        Check.equal("or with CR", List.of(Event.of("a")), parse("data: a\r\r"));
        Check.equal("or with CRLF", List.of(Event.of("a")), parse("data: a\r\n\r\n"));
        Check.equal("or with a mixture", List.of(Event.of("a"), Event.of("b")), parse("data: a\r\n\rdata: b\n\r\n"));

        var reader = new BufferedReader(new OneCharacter("data: one\r\ndata: two\r\n\r\ndata: three\r\n\r\n"), 1);
        try (var signals = EventStream.parse(reader)) {
            Check.equal("a CRLF split across a read boundary is still one line ending",
                    List.of(Event.of("one\ntwo"), Event.of("three")),
                    signals.toList());
        }

        Check.equal("a byte order mark is the stream's, not the first field's",
                List.of(Event.of("a")),
                parse(BOM + "data: a\n\n"));
        Check.equal("and only the first one is a mark",
                List.of(Event.of("a"), Event.of(BOM + "b")),
                parse(BOM + "data: a\n\ndata: " + BOM + "b\n\n"));
    }

    private static void incomplete() {
        Check.equal("a stream that stops mid-event dispatches nothing",
                List.of(Event.of("one")),
                parse("data: one\n\ndata: two"));
        Check.equal("not even when its last line is whole but unterminated", List.of(), parse("data: one\n"));
    }

    private static void writes() {
        Check.equal("an event is its data and a blank line", "data: hello\n\n", written(Event.of("hello")));
        Check.equal("multi-line data becomes several data lines",
                "data: one\ndata: two\n\n",
                written(Event.of("one\ntwo")));
        Check.equal("a type is written only when it is not the default",
                "event: tick\ndata: now\n\n",
                written(Event.of("tick", "now")));
        Check.equal("an id is written only when there is one",
                "id: 7\ndata: now\n\n",
                written(new Event(Event.MESSAGE, "now", "7")));
        Check.equal("a reconnection time needs no blank line, having dispatched nothing",
                "retry: 2500\n",
                written(new Retry(2500)));
        Check.equal("empty data is still a line, or it would parse back as no event at all",
                "data: \n\n",
                written(Event.of("")));

        Check.throwing("a newline in a type is refused rather than written",
                () -> written(Event.of("a\nevent: forged", "x")));
        Check.throwing("so is one in an id", () -> written(new Event(Event.MESSAGE, "x", "1\ndata: forged")));
        Check.throwing("so is a carriage return in data, which would end the line early",
                () -> written(Event.of("one\rtwo")));

        var out = new StringWriter();
        uncheck(() -> EventStream.comment("ping", out));
        Check.equal("a comment is a colon and some text", ": ping\n", out.toString());
    }

    private static void roundTrip() {
        var sent = List.of(
                new Retry(2500),
                Event.of("hello"),
                Event.of(""),
                Event.of(" leading space"),
                Event.of("trailing space "),
                Event.of("tick", "one\ntwo\nthree"),
                new Event("done", "last", "42"));

        var out = new StringWriter();
        sent.forEach(signal -> uncheck(() -> EventStream.write(signal, out)));
        Check.equal("what was written parses back to what was sent", sent, parse(out.toString()));
    }

    /// The gatherer must not run ahead of what is asked of it: an event stream
    /// is open-ended, and reading one line more than necessary can mean
    /// blocking until the server has something else to say.
    private static void lazily() {
        var pulled = new AtomicInteger();
        var signals = List.of("data: one", "", "data: two", "", "data: three", "").stream()
                .peek(line -> pulled.incrementAndGet())
                .gather(EventStream.signals())
                .limit(2)
                .toList();
        Check.equal("two events are two events", 2, signals.size());
        Check.equal("and only the four lines those two events needed", 4, pulled.get());
    }

    private static void closes() throws IOException {
        var reader = new Closing("data: one\n\n");
        try (var signals = EventStream.parse(reader)) {
            Check.equal("the events are there", 1, signals.toList().size());
        }
        Check.that("closing the stream closes the reader it was given", reader.closed);

        try (var events = EventStream.events(new StringReader("retry: 10\ndata: one\n\n"))) {
            Check.equal("and the events view drops what is not an event", List.of(Event.of("one")), events.toList());
        }
    }

    /// The reason for the body handler: an event stream almost always arrives
    /// over HTTP, and the JDK already has a client for that.
    private static void overHttp() throws IOException, InterruptedException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/events", exchange -> {
            var body = "retry: 2000\n\nevent: tick\ndata: one\n\ndata: two\ndata: three\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var address = "http://127.0.0.1:" + server.getAddress().getPort() + "/events";
            var response = client.send(HttpRequest.newBuilder(URI.create(address)).build(), EventStream.body());
            try (var signals = response.body()) {
                Check.equal("an HttpClient reads an event stream with no adapter in between",
                        List.of(new Retry(2000), Event.of("tick", "one"), Event.of("two\nthree")),
                        signals.toList());
            }
        } finally {
            server.stop(0);
        }
    }

    private static List<Signal> parse(String text) {
        try (var signals = EventStream.parse(new StringReader(text))) {
            return signals.toList();
        }
    }

    private static String written(Signal signal) {
        var out = new StringWriter();
        uncheck(() -> EventStream.write(signal, out));
        return out.toString();
    }

    private static void uncheck(Body body) {
        try {
            body.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private interface Body {
        void run() throws IOException;
    }

    /// A reader that gives up one character at a time, so that a line ending
    /// lands on a buffer boundary.
    private static final class OneCharacter extends Reader {

        private final String text;
        private int at;

        private OneCharacter(String text) {
            this.text = text;
        }

        @Override
        public int read(char[] buffer, int offset, int length) {
            if (at >= text.length()) return -1;
            buffer[offset] = text.charAt(at++);
            return 1;
        }

        @Override
        public void close() {}
    }

    private static final class Closing extends Reader {

        private final StringReader text;
        private boolean closed;

        private Closing(String text) {
            this.text = new StringReader(text);
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            return text.read(buffer, offset, length);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
