package web;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

/// A response being written.
///
/// This is the interface a server implements, and the only thing the rest of
/// `web` knows about how a reply reaches a client. It is deliberately about
/// *writing* rather than about a finished value: a page is rendered as it is
/// built, an asset is copied from disk without being read into memory, and an
/// event stream is a response that stays open for as long as somebody is
/// listening.
///
/// The headers go out exactly once, and when they do is not a matter of timing:
/// [#body], [#writer] and [#close] send them, [#sent] says whether they are
/// gone, and setting a status or a header afterwards throws rather than being
/// quietly lost. A framework that leaves that boundary implicit produces bugs
/// that only appear under load, which is the worst kind.
///
/// Length is a header like any other. Set `Content-Length` before writing and a
/// binding may use it; leave it out and the body is sent as it comes, which is
/// what streaming needs.
public interface Response extends Closeable {

    Response status(int status);

    int status();

    /// Replaces this header.
    Response header(String name, String value);

    /// Adds a value beside any already there, for the headers that repeat.
    Response add(String name, String value);

    Headers headers();

    /// Whether the headers have gone out. After this, the status and the
    /// headers are history.
    boolean sent();

    /// Sends the headers, if they have not gone, and answers with the stream the
    /// body is written to. Calling it twice answers with the same stream.
    OutputStream body() throws IOException;

    /// The same stream as text. UTF-8, because a framework that lets a page be
    /// rendered in anything else has made a decision it cannot take back.
    Writer writer() throws IOException;

    /// Pushes what has been written this far to the client. Not an optimisation
    /// — an event stream that is never flushed never arrives, so this belongs
    /// in the interface rather than in one server's implementation of it.
    void flush() throws IOException;

    /// Ends the response, sending the headers first if nothing else has.
    @Override
    void close() throws IOException;
}
