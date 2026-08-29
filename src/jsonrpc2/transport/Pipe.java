package jsonrpc2.transport;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import jsonrpc2.Transport;

/// Two [Transport]s that are each other's other end.
///
/// Each document one side writes becomes the next document the other side
/// reads. Receive on one thread and send on another is safe. An empty writer
/// still sends nothing. Closing one end means the other side's next
/// [Transport#receive] answers empty after the documents already queued.
///
/// The tests use this to join two [jsonrpc2.Conn] instances inside one
/// process. A test that binds a real port can fail because the port was busy.
///
/// ```
/// var pipe = Pipe.open();
/// try (var server = jsonrpc2.Conn.of(pipe.right()).answering(methods);
///         var client = jsonrpc2.Conn.of(pipe.left())) {
///     Thread.startVirtualThread(() -> {
///         try { server.listen(); } catch (java.io.IOException ignored) {}
///     });
///     client.call("ping");
/// }
/// ```
public final class Pipe {

    private static final String END = "";

    private Pipe() {}

    /// Two connected transports. `left` sends to `right`. `right` sends to
    /// `left`.
    public static Ends open() {
        var gate = new Object();
        var closed = new boolean[1];
        var leftToRight = new LinkedBlockingQueue<String>();
        var rightToLeft = new LinkedBlockingQueue<String>();
        return new Ends(new End(gate, closed, rightToLeft, leftToRight),
                new End(gate, closed, leftToRight, rightToLeft));
    }

    public record Ends(Transport left, Transport right) {}

    private static final class End implements Transport {

        private final Object gate;
        private final boolean[] closed;
        private final BlockingQueue<String> inbound;
        private final BlockingQueue<String> outbound;

        private End(Object gate, boolean[] closed, BlockingQueue<String> inbound, BlockingQueue<String> outbound) {
            this.gate = gate;
            this.closed = closed;
            this.inbound = inbound;
            this.outbound = outbound;
        }

        @Override
        public Optional<Reader> receive() throws IOException {
            try {
                var document = inbound.take();
                if (document.isEmpty()) return Optional.empty();
                return Optional.of(new StringReader(document));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }

        @Override
        public Writer send() {
            return new Framed();
        }

        @Override
        public void close() {
            synchronized (gate) {
                if (closed[0]) return;
                closed[0] = true;
                inbound.offer(END);
                outbound.offer(END);
            }
        }

        private final class Framed extends StringWriter {

            @Override
            public void close() throws IOException {
                var document = toString();
                if (document.isEmpty()) return;
                synchronized (gate) {
                    if (closed[0]) throw new IOException("the pipe is closed");
                    outbound.offer(document);
                }
            }
        }
    }
}
