package web.serve;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import web.Handler;
import web.Headers;
import web.Parameters;
import web.Request;
import web.Response;
import web.Status;

/// `jdk.httpserver`, wearing the interfaces.
///
/// This is the only file in `web` that imports `com.sun.net.httpserver`, and
/// that is the whole design: everything above it was written against [Handler],
/// [Request] and [Response], so moving to another server means writing another
/// one of these and changing nothing else.
///
/// Requests are handled on virtual threads. Thread-per-request was the reason
/// people stopped writing blocking servers, and Loom took the reason away.
public final class Http implements AutoCloseable {

    /// How long a client may take to send a request. The JDK's default is
    /// forever, which means an unconfigured server is held open by anybody who
    /// opens a socket and types slowly.
    public static final String MAX_REQUEST_TIME = "sun.net.httpserver.maxReqTime";

    /// How long the *response* may take, which is deliberately left alone: an
    /// event stream is a response that lasts as long as somebody is reading,
    /// and a limit here would cut every one of them off. Slow readers are the
    /// proxy's problem, and there is a proxy.
    public static final String MAX_RESPONSE_TIME = "sun.net.httpserver.maxRspTime";

    /// How long an idle connection is kept.
    public static final String IDLE_INTERVAL = "sun.net.httpserver.idleInterval";

    private static final String DEFAULT_MAX_REQUEST_TIME = "30";
    private static final String DEFAULT_IDLE_INTERVAL = "60";

    private final HttpServer server;

    private Http(HttpServer server) {
        this.server = server;
    }

    public static Http start(Handler handler, int port) throws IOException {
        return start(handler, new InetSocketAddress(port), 0, Http::complain);
    }

    /// A server on a port, saying where a handler's failures should go.
    public static Http start(Handler handler, int port, Consumer<Exception> failures) throws IOException {
        return start(handler, new InetSocketAddress(port), 0, failures);
    }

    /// Starts a server for a raw JDK HTTP handler. This overload is the
    /// boundary for an external named module that uses only
    /// `com.sun.net.httpserver`; the listener still belongs to the host.
    public static Http start(com.sun.net.httpserver.HttpHandler handler, int port) throws IOException {
        return start(handler, new InetSocketAddress(port), 0, Http::complain);
    }

    /// Starts a server for a raw JDK HTTP handler and reports handler failures.
    public static Http start(com.sun.net.httpserver.HttpHandler handler, int port,
            Consumer<Exception> failures) throws IOException {
        return start(handler, new InetSocketAddress(port), 0, failures);
    }

    /// A server on a given address, and what to do about a handler that throws.
    /// Failures go somewhere by default rather than nowhere, because a server
    /// that silently answers 500 is a server nobody can debug.
    public static Http start(Handler handler, InetSocketAddress address, int backlog, Consumer<Exception> failures)
            throws IOException {
        timeouts();
        var server = HttpServer.create(address, backlog);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> answer(handler, exchange, failures));
        server.start();
        return new Http(server);
    }

    /// Starts a server for a raw JDK HTTP handler on `address`.
    public static Http start(com.sun.net.httpserver.HttpHandler handler, InetSocketAddress address,
            int backlog, Consumer<Exception> failures) throws IOException {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(failures, "failures");
        timeouts();
        var server = HttpServer.create(address, backlog);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> answer(handler, exchange, failures));
        server.start();
        return new Http(server);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /// Set before the first server exists, because the JDK reads them once, in
    /// a static initialiser, and an operator who set them already meant it.
    private static void timeouts() {
        if (System.getProperty(MAX_REQUEST_TIME) == null) System.setProperty(MAX_REQUEST_TIME, DEFAULT_MAX_REQUEST_TIME);
        if (System.getProperty(IDLE_INTERVAL) == null) System.setProperty(IDLE_INTERVAL, DEFAULT_IDLE_INTERVAL);
    }

    /// The failure has to be answered before the exchange closes, which is why
    /// the catch is inside the try-with-resources and not beside it: a 500
    /// written to a closed exchange is an EOF at the client, which is the least
    /// useful thing a server can say.
    private static void answer(Handler handler, HttpExchange exchange, Consumer<Exception> failures) {
        try (exchange) {
            var response = new Exchange(exchange);
            try {
                handler.handle(request(exchange), response);
                response.close();
            } catch (Exception e) {
                if (!left(e)) failures.accept(e);
                fail(response);
            }
        }
    }

    private static void answer(com.sun.net.httpserver.HttpHandler handler, HttpExchange exchange,
            Consumer<Exception> failures) {
        try (exchange) {
            try {
                handler.handle(exchange);
            } catch (Exception failure) {
                if (!left(failure)) failures.accept(failure);
                if (exchange.getResponseCode() < 0) {
                    try { exchange.sendResponseHeaders(Status.ERROR, -1); }
                    catch (IOException ignored) {}
                }
            }
        }
    }

    /// Whether the client hung up rather than the handler breaking.
    ///
    /// A page that navigates away, a search box that cancels the request it
    /// made two keystrokes ago, an `EventSource` reconnecting — all of them end
    /// as a write to a socket nobody is reading, and none of them is a failure
    /// of this server. Reporting them teaches whoever reads the log to stop
    /// reading it, which costs far more than the one real failure it hides.
    ///
    /// Blocking IO has no exception of its own for this, so the connection is
    /// taken at its word: the operating system says `Broken pipe` when the peer
    /// is gone and `Connection reset` when it went abruptly, and the JDK says
    /// `Stream closed` for a stream that ended underneath a write. A disconnect
    /// never reaches a failure consumer at all, because it is not one — a
    /// caller counting failures wants handlers that threw, not readers who
    /// left.
    private static boolean left(Exception failure) {
        if (!(failure instanceof IOException)) return false;
        var reason = failure.getMessage();
        if (reason == null) return false;
        var said = reason.toLowerCase(Locale.ROOT);
        return said.contains("broken pipe")
                || said.contains("connection reset")
                || said.contains("stream closed")
                || said.contains("socket closed")
                || said.contains("connection aborted")
                || said.contains("closed by the remote host");
    }

    private static Request request(HttpExchange exchange) {
        var uri = exchange.getRequestURI();
        var headers = new LinkedHashMap<String, List<String>>(exchange.getRequestHeaders());
        return new Request(
                exchange.getRequestMethod(),
                uri.getRawPath(),
                Parameters.parse(uri.getRawQuery()),
                new Headers(headers),
                exchange.getRequestBody(),
                exchange.getRemoteAddress() == null ? "" : exchange.getRemoteAddress().getAddress().getHostAddress(),
                Map.of());
    }

    /// A handler that threw before saying anything still owes the client an
    /// answer; one that threw halfway through a page has already spent it.
    private static void fail(Exchange response) {
        try {
            if (!response.sent()) response.status(Status.ERROR);
            response.close();
        } catch (IOException ignored) {
            // the connection is gone, which is why we are here
        }
    }

    private static void complain(Exception failure) {
        System.err.println("web: " + failure);
    }

    /// The response half of the binding: everything [Response] promises, in
    /// terms of what an exchange offers.
    private static final class Exchange implements Response {

        private final HttpExchange exchange;
        private final boolean headless;
        private Headers headers = Headers.NONE;
        private int status = Status.OK;
        private boolean committed;

        private boolean closed;
        private OutputStream body;
        private Writer writer;

        private Exchange(HttpExchange exchange) {
            this.exchange = exchange;
            this.headless = exchange.getRequestMethod().equalsIgnoreCase("HEAD");
        }

        @Override
        public Response status(int status) {
            settled();
            this.status = status;
            return this;
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public Response header(String name, String value) {
            settled();
            headers = headers.with(name, value);
            return this;
        }

        @Override
        public Response add(String name, String value) {
            settled();
            headers = headers.add(name, value);
            return this;
        }

        @Override
        public Headers headers() {
            return headers;
        }

        @Override
        public boolean sent() {
            return committed;
        }

        /// A writer whose close is the response's close.
        private final class Closing extends java.io.FilterWriter {

            private Closing(Writer out) {
                super(out);
            }

            @Override
            public void close() throws IOException {
                if (closed) return;
                out.flush();
                Exchange.this.close();
            }
        }

        @Override
        public OutputStream body() throws IOException {
            commit();
            return body;
        }

        /// The writer and the response are one resource, so closing the writer
        /// closes the response — a handler using a try-with-resources is not
        /// doing something the server then has to undo.
        @Override
        public Writer writer() throws IOException {
            if (writer == null) writer = new Closing(new OutputStreamWriter(body(), StandardCharsets.UTF_8));
            return writer;
        }

        @Override
        public void flush() throws IOException {
            commit();
            if (writer != null) writer.flush();
            body.flush();
        }

        /// Closing twice does nothing the second time, which is what
        /// [java.io.Closeable] asks of every implementation and what this one
        /// actually needs: a handler that writes inside a try-with-resources
        /// has already closed the writer by the time a server closes the
        /// response, and flushing a closed writer throws. The failure was
        /// invented by the plumbing rather than met by it, and a server that
        /// logs one per successful response teaches everybody to ignore its
        /// log.
        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            commit();
            if (writer != null) writer.flush();
            body.close();
        }

        /// The exchange wants the length up front and reads it three ways: a
        /// number for a body of known size, zero for one that is streamed, and
        /// -1 for none at all. `Content-Length` is a header everywhere else in
        /// this framework, so it is translated here and not sent twice.
        private void commit() throws IOException {
            if (committed) return;
            committed = true;
            var declared = headers.first("Content-Length").map(Long::valueOf);
            var length = headless || Status.bodiless(status) ? -1L : declared.orElse(0L);
            var out = exchange.getResponseHeaders();
            headers.values().forEach(out::put);
            if (length >= 0) out.remove("Content-Length");
            exchange.sendResponseHeaders(status, length);
            // With no body to send, the exchange hands back a stream that is already
            // closed, and a handler that writes one anyway — which is exactly what a
            // handler answering HEAD does — would look like a failure. It is not one.
            body = length < 0 ? OutputStream.nullOutputStream() : exchange.getResponseBody();
        }

        private void settled() {
            if (committed) throw new IllegalStateException("the headers have gone out; " + status + " is what was sent");
        }
    }
}
