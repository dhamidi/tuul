package jsonrpc2.transport;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import jsonrpc2.Server;
import jsonrpc2.Transport;

/// A transport that is not one.
///
/// Documents go in and documents come out. There is no socket, no port, no
/// thread, and nothing to start or stop. This exists because it is the proof
/// that [Transport] is an interface: everything the protocol needs from the
/// outside world is here, in a hundred lines, and what is not here is not
/// needed.
///
/// It is also the harness. A test of a [Server] or a [jsonrpc2.Client] should
/// use this, because a test that binds a port is a test that can fail because a
/// port was busy.
///
/// Two shapes, one class:
///
/// - [#of(Server)] answers every document itself. Give it to a
///   [jsonrpc2.Client] and the call goes straight into the server and comes
///   straight back.
/// - [#of(String...)] replays documents that a [Server] will read. What the
///   server writes lands in [#sent()].
///
/// This transport holds whole documents in memory, which is the one thing the
/// rest of the package refuses to do. That is the transport's business. Framing
/// is what a transport is for, and here the frame is a string.
public final class Memory implements Transport {

    private final Optional<Server> server;
    private final Deque<String> incoming = new ArrayDeque<>();
    private final List<String> sent = new ArrayList<>();

    private Memory(Optional<Server> server, List<String> incoming) {
        this.server = server;
        this.incoming.addAll(incoming);
    }

    /// A transport whose other end is this server.
    public static Memory of(Server server) {
        return new Memory(Optional.of(server), List.of());
    }

    /// A transport that delivers these documents, in this order, and then ends.
    public static Memory of(String... documents) {
        return new Memory(Optional.empty(), List.of(documents));
    }

    /// Every document this transport was asked to send, in order. Empty
    /// documents are not here, because they were never sent.
    public List<String> sent() {
        return List.copyOf(sent);
    }

    /// A document waiting to be read, in the order it was queued.
    @Override
    public Optional<Reader> receive() {
        return Optional.ofNullable(incoming.poll()).map(StringReader::new);
    }

    @Override
    public Writer send() {
        return new Framed();
    }

    /// One outgoing document. Closing it is what sends it, and a document with
    /// no characters in it was never sent at all — which is how a batch of
    /// notifications stays silent.
    private final class Framed extends StringWriter {

        @Override
        public void close() throws IOException {
            var document = toString();
            if (document.isEmpty()) return;
            sent.add(document);
            if (server.isPresent()) answer(document);
        }

        private void answer(String document) throws IOException {
            var out = new StringWriter();
            if (server.get().handle(new StringReader(document), out)) incoming.add(out.toString());
        }
    }
}
