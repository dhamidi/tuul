package web.serve;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import web.Handler;
import web.Headers;
import web.Request;
import web.Response;
import web.Status;

/// A server that is not one.
///
/// A request goes in and a response comes out: no socket, no port, no thread,
/// nothing to start or stop. This exists because it is the proof that the
/// interfaces in `web` are interfaces — anything a handler needs from a server
/// is here, in a hundred lines, and what is not here is not needed.
///
/// It is also the harness. `web.hyperspec` drives whole applications through
/// this, and every test of a handler anywhere should: a test that binds a port
/// is a test that can fail because a port was busy.
public final class Memory {

    private Memory() {}

    public static Request get(String path) {
        return Request.of("GET", path);
    }

    public static Request head(String path) {
        return Request.of("HEAD", path);
    }

    public static Request post(String path, String form) {
        return Request.of("POST", path,
                Headers.of("Content-Type", "application/x-www-form-urlencoded"), Request.body(form));
    }

    public static Request request(String method, String path, Headers headers, String body) {
        return Request.of(method, path, headers, Request.body(body));
    }

    /// Runs a handler to completion and answers with everything it said.
    ///
    /// A handler that throws is a 500 here exactly as it is on a socket — the
    /// two bindings agree about failure, or a test proves nothing — and the
    /// exception is kept rather than swallowed, because a test that has to read
    /// a log to find out what broke is a bad test.
    public static Recorded handle(Handler handler, Request request) {
        var recording = new Recording(request.method().equals("HEAD"), null);
        Exception failure = null;
        try {
            handler.handle(request, recording);
        } catch (Exception e) {
            failure = e;
            recover(recording);
        }
        try {
            recording.close();
        } catch (IOException e) {
            failure = failure == null ? e : failure;
        }
        return new Recorded(recording.status(), recording.headers(), recording.written.toByteArray(),
                recording.flushes, Optional.ofNullable(failure));
    }

    /// Runs a handler that is not going to finish — an event stream — and hands
    /// back what it is saying while it says it.
    ///
    /// The handler runs on a virtual thread of its own; the caller reads. This
    /// is the shape `web.cable` needs, and being able to write it here in
    /// twenty lines is what "a response that stays open" means as a property of
    /// the interface rather than of a server.
    public static Open open(Handler handler, Request request) throws IOException {
        var sink = new PipedOutputStream();
        var source = new PipedInputStream(sink, 64 * 1024);
        var sent = new CountDownLatch(1);
        var streaming = new Recording(false, sink, sent);
        var thread = Thread.ofVirtual().name("memory-handler").start(() -> {
            try (streaming) {
                handler.handle(request, streaming);
            } catch (Exception ignored) {
                // the reader sees the stream end, which is what a client sees
            }
        });
        return new Open(streaming, source, sent, thread);
    }

    /// A response still being written, and the thread writing it.
    public record Open(Response response, InputStream body, CountDownLatch sent, Thread handler)
            implements AutoCloseable {

        /// Waits for the headers, because until they are sent there is no status
        /// to ask about.
        public int status() throws InterruptedException {
            await();
            return response.status();
        }

        public Headers headers() throws InterruptedException {
            await();
            return response.headers();
        }

        public Reader reader() {
            return new InputStreamReader(body, StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            handler.interrupt();
            try {
                body.close();
            } catch (IOException ignored) {
                // closing a pipe the writer has already left
            }
        }

        private void await() throws InterruptedException {
            if (!sent.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("the headers never went out");
        }
    }

    private static void recover(Recording recording) {
        if (recording.sent()) return;
        try {
            recording.status(Status.ERROR).close();
        } catch (IOException ignored) {
            // a recording cannot fail to be written to
        }
    }

    /// The whole of what a server has to be.
    private static final class Recording implements Response {

        private final boolean headless;
        private final OutputStream sink;
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final List<Integer> flushes = new ArrayList<>();
        private final CountDownLatch sent;
        private Headers headers = Headers.NONE;
        private int status = Status.OK;
        private boolean committed;
        private boolean closed;
        private Writer writer;

        private Recording(boolean headless, OutputStream sink) {
            this(headless, sink, new CountDownLatch(1));
        }

        /// `sink` is where somebody is reading as this is written, and null when
        /// nobody is — the difference between [Memory#open] and [Memory#handle].
        private Recording(boolean headless, OutputStream sink, CountDownLatch sent) {
            this.headless = headless;
            this.sink = sink;
            this.sent = sent;
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

        @Override
        public OutputStream body() {
            commit();
            return headless || Status.bodiless(status) ? OutputStream.nullOutputStream() : new Tee();
        }

        /// A writer whose close is the response's close, exactly as in
        /// [Http]. The two bindings have to agree about this, or a handler
        /// that is correct on a socket fails in a test.
        private final class Closing extends java.io.FilterWriter {

            private Closing(Writer out) {
                super(out);
            }

            @Override
            public void close() throws IOException {
                if (closed) return;
                out.flush();
                Recording.this.close();
            }
        }

        @Override
        public Writer writer() {
            if (writer == null) writer = new Closing(new OutputStreamWriter(body(), StandardCharsets.UTF_8));
            return writer;
        }

        @Override
        public void flush() throws IOException {
            commit();
            if (writer != null) writer.flush();
            if (sink != null) sink.flush();
            flushes.add(written.size());
        }

        /// Closing twice does nothing the second time.
        ///
        /// [web.Responses#html] and every other writer-based answer write inside a
        /// try-with-resources, so the handler has already closed the writer
        /// when [Memory#handle] closes the response. Flushing a closed writer
        /// throws, and this harness reported that as [Recorded#failure()] — a
        /// failure the plumbing invented, on a handler that did nothing wrong.
        /// The first test anybody writes is "nothing threw", so every such test
        /// failed. The writer therefore never closes the stream below it, and
        /// the flush below is always safe.
        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            commit();
            if (writer != null) writer.flush();
            if (sink != null) {
                sink.flush();
                sink.close();
            }
        }

        private void commit() {
            if (committed) return;
            committed = true;
            sent.countDown();
        }

        private void settled() {
            if (committed) throw new IllegalStateException("the headers have gone out; " + status + " is what was sent");
        }

        /// Writes to both the record and whoever is reading — the second is only
        /// there for [Memory#open], where somebody is.
        private final class Tee extends OutputStream {

            @Override
            public void write(int one) throws IOException {
                written.write(one);
                if (sink != null) sink.write(one);
            }

            @Override
            public void write(byte[] bytes, int from, int length) throws IOException {
                written.write(bytes, from, length);
                if (sink != null) sink.write(bytes, from, length);
            }

            @Override
            public void flush() throws IOException {
                if (sink != null) sink.flush();
            }
        }
    }
}
