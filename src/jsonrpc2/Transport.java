package jsonrpc2;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Optional;

/// Where messages arrive from, and where they go to.
///
/// This is the only thing `jsonrpc2` knows about the outside world. The
/// protocol names no transport and depends on none, so neither does this
/// package. A socket, a pipe, an HTTP body, a `Content-Length` header, a
/// heartbeat, a reconnect: all of it lives behind this interface, and none of
/// it lives in front of it.
///
/// Framing belongs to the transport. The protocol layer reads one JSON value
/// and writes one JSON value. Only the transport knows where one document stops
/// and the next one starts.
///
/// Two rules make the whole package work, and an implementation must keep both:
///
/// 1. [#receive()] answers with nothing when no message will ever arrive again.
///    That is how a serving loop learns that it is over.
/// 2. [#send()] sends nothing when nothing is written to the writer before it
///    is closed. Send the frame on the first character, not on the call. A
///    batch of notifications produces no document at all, and this rule is what
///    lets that stay true on every transport.
public interface Transport extends Closeable {

    /// The next document that arrived, or nothing when the transport is
    /// finished. The transport owns the reader.
    Optional<Reader> receive() throws IOException;

    /// A writer for one outgoing document. Closing the writer ends the
    /// document. The caller closes it.
    Writer send() throws IOException;

    /// Releases whatever the transport holds. An in-memory transport holds
    /// nothing, so this does nothing until an implementation says otherwise.
    @Override
    default void close() throws IOException {}
}
