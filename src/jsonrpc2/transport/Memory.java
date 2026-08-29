package jsonrpc2.transport;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import jsonrpc2.Transport;

/// A scripted [Transport] that delivers given documents and then ends.
///
/// [#of(String...)] queues those documents for [Transport#receive]. What this
/// side writes lands in [#sent]. An empty writer is not recorded. Receive and
/// send may run on different threads.
///
/// This is the inbound queue for a [jsonrpc2.Conn#listen] test, and for a
/// [jsonrpc2.Conn#batch(java.util.List)] whose answers are already known. Two
/// live peers use [Pipe], not this.
///
/// This transport holds whole documents in memory, which is the one thing the
/// rest of the package refuses to do. Framing is what a transport exists for,
/// and here a frame is one string.
public final class Memory implements Transport {

    private final ConcurrentLinkedQueue<String> incoming = new ConcurrentLinkedQueue<>();
    private final List<String> sent = new ArrayList<>();

    private Memory(List<String> incoming) {
        this.incoming.addAll(incoming);
    }

    /// A transport that delivers these documents, in this order, and then ends.
    public static Memory of(String... documents) {
        return new Memory(List.of(documents));
    }

    /// Every document this transport sent, in order. An empty document is not
    /// here, because it was never sent.
    public List<String> sent() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    @Override
    public Optional<Reader> receive() {
        return Optional.ofNullable(incoming.poll()).map(StringReader::new);
    }

    @Override
    public Writer send() {
        return new Framed();
    }

    /// One outgoing document. Closing it is what sends it. A document with no
    /// characters in it was never sent at all.
    private final class Framed extends StringWriter {

        @Override
        public void close() throws IOException {
            var document = toString();
            if (document.isEmpty()) return;
            synchronized (sent) {
                sent.add(document);
            }
        }
    }
}
