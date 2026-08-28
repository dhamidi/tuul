package jsonrpc2;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Optional;

/// A transport carries JSON-RPC documents into and out of this process.
///
/// This is the only thing `jsonrpc2` knows about the outside world. The
/// protocol names no transport and depends on none, so neither does this
/// package. A socket, a pipe, an HTTP body, a `Content-Length` header, a
/// heartbeat and a reconnect all live behind this interface, and the protocol
/// layer in front of it sees none of them.
///
/// Framing belongs to the transport. The protocol layer reads one JSON value
/// and writes one JSON value. Only the transport knows where one document stops
/// and the next one starts.
///
/// Two rules make the whole package work, and an implementation must keep both:
///
/// 1. [#receive()] answers with nothing when no more documents will arrive. A
///    serving loop learns from this that it is finished.
/// 2. [#send()] sends nothing when the caller writes nothing to the writer
///    before the caller closes it. Send the frame on the first character, not
///    on the call. A batch of notifications produces no document at all. This
///    rule is what keeps that true on every transport.
public interface Transport extends Closeable {

    /// The next document that arrived, or nothing when the transport is
    /// finished. The transport owns the reader.
    Optional<Reader> receive() throws IOException;

    /// A writer for one outgoing document. Closing the writer ends the
    /// document. The caller closes it.
    Writer send() throws IOException;

    /// Releases whatever the transport holds. This default does nothing,
    /// because an in-memory transport holds nothing. An implementation that
    /// holds a socket or a file must override it.
    @Override
    default void close() throws IOException {}
}
