package fetch;

import eventstream.EventStream;
import eventstream.Signal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/// A one-shot or repeatable stream of bytes for a request or a response.
///
/// Factory methods create repeatable request bodies from values and files.
/// Publishers and event signal streams create one-shot request bodies. A
/// response body is always one-shot. The caller must consume the body or close
/// its owning response. If the caller opens a stream, reader, or publisher, it
/// must close or cancel that view.
public interface Body {
    /// Creates a repeatable body with no bytes.
    ///
    /// The body reports a length of zero and can be read any number of times.
    static Body empty() { return bytes(new byte[0]); }

    /// Creates a repeatable body from `value` encoded as UTF-8.
    static Body text(String value) { return text(value, StandardCharsets.UTF_8); }

    /// Creates a repeatable body by encoding `value` with `charset`.
    ///
    /// The returned body reports the encoded byte count.
    static Body text(String value, Charset charset) { return bytes(value.getBytes(charset)); }

    /// Creates a repeatable body from a copy of `value`.
    ///
    /// The body reports the array length and does not observe later changes to
    /// the caller's array.
    static Body bytes(byte[] value) { var copy = value.clone(); return new StreamBody(() -> new ByteArrayInputStream(copy), OptionalLong.of(copy.length), true); }

    /// Creates a repeatable body that opens `path` for each read.
    ///
    /// The body reports the file size when the file system provides it. The
    /// caller must keep the file available and unchanged until the exchange ends.
    static Body file(Path path) { return new StreamBody(() -> Files.newInputStream(path), size(path), true); }

    /// Creates a repeatable UTF-8 form body from `form`.
    ///
    /// The body contains URL-encoded field pairs. It does not add a request
    /// `Content-Type` header. [Request#form(Form)] adds that header.
    static Body form(Form form) { return text(encode(form), StandardCharsets.UTF_8); }

    /// Creates a repeatable UTF-8 form body from a map of form fields.
    ///
    /// The map follows the rules of [Form#of(java.util.Map)]. It does not add a request
    /// `Content-Type` header.
    static Body form(java.util.Map<String, ?> fields) { return form(Form.of(fields)); }

    /// Creates a one-shot body backed by `source`.
    ///
    /// `length` supplies the byte count when it is known. Use
    /// `OptionalLong.empty()` when the byte count is unknown. The caller must
    /// provide a new body for a retry.
    static Body publisher(Flow.Publisher<ByteBuffer> source, OptionalLong length) { return new PublisherBody(source, length); }

    /// Creates a one-shot UTF-8 `text/event-stream` body from `signals`.
    ///
    /// The body reads signals in encounter order when a request consumes it.
    /// The body closes `signals` after completion, failure, or cancellation.
    /// The body has no known length. This method does not add a request
    /// `Content-Type` header. [Request#eventStream(Stream)] adds that header.
    static Body eventStream(Stream<? extends Signal> signals) { java.util.Objects.requireNonNull(signals); return new StreamBody(() -> eventInput(signals), OptionalLong.empty(), false); }

    /// Returns whether this body can create the same bytes more than once.
    ///
    /// A `false` result means the caller must provide a new body before a retry.
    boolean repeatable();

    /// Returns the byte count when it is known, or an empty value otherwise.
    OptionalLong length();

    /// Returns a publisher that emits this body's bytes in read-only buffers.
    ///
    /// A one-shot body supports one subscription. A repeatable body can create
    /// another subscription. The subscriber must consume or copy each buffer
    /// before `onNext` returns.
    Flow.Publisher<ByteBuffer> publisher();

    /// Copies this body's bytes to `out` and leaves `out` open.
    ///
    /// A one-shot body cannot be read again after this call. A repeatable body
    /// can be read again.
    void writeTo(OutputStream out) throws IOException;

    /// Creates or truncates `path`, writes this body's bytes to it, and closes the file.
    default void writeTo(Path path) throws IOException { try (var out = Files.newOutputStream(path)) { writeTo(out); } }

    /// Opens a reader that consumes this body with `charset`.
    ///
    /// The caller must close the returned reader. Closing it closes the body
    /// stream. A one-shot body cannot be read again after this call.
    default Reader reader(Charset charset) throws IOException { return new InputStreamReader(stream(), charset); }

    /// Reads this body with `charset`, closes the body stream, and returns all text.
    ///
    /// A one-shot body cannot be read again after this call.
    default String text(Charset charset) throws IOException { try (var reader = reader(charset)) { var text = new java.io.StringWriter(); reader.transferTo(text); return text.toString(); } }

    /// Reads this body, closes its stream, and returns all bytes.
    ///
    /// A one-shot body cannot be read again after this call. Use [#publisher()]
    /// or [#writeTo(Path)] for a large body.
    default byte[] bytes() throws IOException { var out = new ByteArrayOutputStream(); writeTo(out); return out.toByteArray(); }

    /// Opens an input stream for this body.
    ///
    /// The caller must close the returned stream. A one-shot body allows only
    /// one successful stream operation.
    InputStream stream() throws IOException;

    private static OptionalLong size(Path path) { try { return OptionalLong.of(Files.size(path)); } catch (IOException e) { return OptionalLong.empty(); } }
    private static InputStream eventInput(Stream<? extends Signal> signals) throws IOException {
        var input = new PipedInputStream(64 * 1024);
        var output = new PipedOutputStream(input);
        var failure = new AtomicReference<Throwable>();
        var producer = Thread.startVirtualThread(() -> {
            var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            try (signals) {
                signals.sequential().forEachOrdered(signal -> { try { EventStream.write(signal, writer); } catch (IOException e) { throw new UncheckedIOException(e); } });
            } catch (Throwable e) {
                failure.set(e instanceof UncheckedIOException unchecked ? unchecked.getCause() : e);
            } finally {
                try { writer.close(); } catch (Throwable e) { failure.compareAndSet(null, e); try { output.close(); } catch (IOException ignored) {} }
            }
        });
        return new FilterInputStream(input) {
            public int read() throws IOException { var value = super.read(); if (value < 0) failed(failure.get()); return value; }
            public int read(byte[] bytes, int offset, int length) throws IOException { var count = super.read(bytes, offset, length); if (count < 0) failed(failure.get()); return count; }
            public void close() throws IOException { try { super.close(); } finally { producer.interrupt(); } }
            private void failed(Throwable cause) throws IOException { if (cause == null) return; if (cause instanceof IOException io) throw io; throw new IOException("event stream failed", cause); }
        };
    }
    private static String encode(Form form) {
        var result = new StringBuilder();
        form.fields().forEach((name, values) -> { for (var value : values) { if (!result.isEmpty()) result.append('&'); result.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8)); } });
        return result.toString();
    }

    /// Opens a new input stream for a [StreamBody].
    interface Opener {
        /// Opens the next stream for the body.
        InputStream open() throws IOException;
    }

    /// A body implementation that obtains bytes from an input stream opener.
    final class StreamBody implements Body {
        private final Opener opener; private final OptionalLong length; private final boolean repeatable; private final AtomicBoolean used = new AtomicBoolean();
        StreamBody(Opener opener, OptionalLong length, boolean repeatable) { this.opener = opener; this.length = length; this.repeatable = repeatable; }

        /// Returns the repeatability selected when this body was created.
        public boolean repeatable() { return repeatable; }

        /// Returns the length selected when this body was created.
        public OptionalLong length() { return length; }

        /// Opens the next stream, or throws when a one-shot body was already used.
        public InputStream stream() throws IOException { if (!repeatable && !used.compareAndSet(false, true)) throw new IllegalStateException("body was consumed"); return opener.open(); }

        /// Copies the stream to `out` and closes the input stream.
        public void writeTo(OutputStream out) throws IOException { try (var in = stream()) { in.transferTo(out); } }

        /// Returns a publisher that reads this body in chunks of at most 16 KiB.
        public Flow.Publisher<ByteBuffer> publisher() { return subscriber -> subscriber.onSubscribe(new InputSubscription(subscriber, this)); }
    }

    /// A subscription that reads a [Body] input stream according to demand.
    final class InputSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super ByteBuffer> subscriber; private final Body body; private boolean done; private InputStream input;
        InputSubscription(Flow.Subscriber<? super ByteBuffer> subscriber, Body body) { this.subscriber = subscriber; this.body = body; }

        /// Reads and emits up to `count` buffers, or signals completion when the body ends.
        ///
        /// A non-positive count signals `onError` with `IllegalArgumentException`.
        public synchronized void request(long count) {
            if (done) return;
            if (count <= 0) { done = true; subscriber.onError(new IllegalArgumentException("non-positive demand")); return; }
            try {
                if (input == null) input = body.stream();
                while (count-- > 0 && !done) { var bytes = input.readNBytes(16 * 1024); if (bytes.length == 0) { done = true; input.close(); subscriber.onComplete(); } else subscriber.onNext(ByteBuffer.wrap(bytes).asReadOnlyBuffer()); }
            } catch (Throwable e) { done = true; subscriber.onError(e); }
        }

        /// Stops reading and closes the input stream when it is open.
        public synchronized void cancel() { done = true; if (input != null) try { input.close(); } catch (IOException ignored) {} }
    }

    /// A one-shot body that delegates byte production to a Flow publisher.
    final class PublisherBody implements Body {
        private final Flow.Publisher<ByteBuffer> source; private final OptionalLong length; private final AtomicBoolean used = new AtomicBoolean();
        PublisherBody(Flow.Publisher<ByteBuffer> source, OptionalLong length) { this.source = java.util.Objects.requireNonNull(source); this.length = length; }

        /// Returns `false` because the publisher can be consumed only once.
        public boolean repeatable() { return false; }

        /// Returns the length supplied when this body was created.
        public OptionalLong length() { return length; }

        /// Returns the source publisher once, then throws when called again.
        public Flow.Publisher<ByteBuffer> publisher() { if (!used.compareAndSet(false, true)) throw new IllegalStateException("body was consumed"); return source; }

        /// Bridges the source publisher to an input stream and returns that stream.
        ///
        /// The returned stream ends when the source completes or fails. A
        /// source failure is not available through the returned stream. Use
        /// [#writeTo(OutputStream)] to receive that failure as an `IOException`.
        public InputStream stream() throws IOException {
            var input = new java.io.PipedInputStream(64 * 1024);
            var output = new java.io.PipedOutputStream(input);
            Thread.startVirtualThread(() -> {
                try { writeTo(output); } catch (IOException ignored) {} finally { try { output.close(); } catch (IOException ignored) {} }
            });
            return input;
        }

        /// Subscribes to the source, writes all emitted buffers to `out`, and waits for completion.
        ///
        /// The method requests unbounded demand and leaves `out` open.
        public void writeTo(OutputStream out) throws IOException {
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var complete = new java.util.concurrent.CountDownLatch(1);
            publisher().subscribe(new Flow.Subscriber<ByteBuffer>() {
                public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                public void onNext(ByteBuffer buffer) {
                    try {
                        var bytes = new byte[Math.min(buffer.remaining(), 16 * 1024)];
                        while (buffer.hasRemaining()) { var count = Math.min(buffer.remaining(), bytes.length); buffer.get(bytes, 0, count); out.write(bytes, 0, count); }
                    } catch (Throwable e) { failure.set(e); }
                }
                public void onError(Throwable error) { failure.set(error); complete.countDown(); }
                public void onComplete() { complete.countDown(); }
            });
            try { complete.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            if (failure.get() instanceof IOException io) throw io;
            if (failure.get() != null) throw new IOException(failure.get());
        }
    }
}
