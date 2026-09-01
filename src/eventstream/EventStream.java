package eventstream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Gatherer;
import java.util.stream.Stream;

/// Reads and writes the `text/event-stream` format.
///
/// Parsing is a [Gatherer] over lines. Call [#signals] for a reusable parser:
///
/// ```
/// reader.lines().gather(EventStream.signals()).forEach(signal -> ...);
/// ```
///
/// The gatherer emits [Event] and [Retry] values in input order. It does not
/// create a thread or read ahead.
///
/// A blank line dispatches the current event. End of input does not dispatch a
/// partial event. The parser accepts CR, LF, and CRLF line endings and removes
/// one leading byte-order mark from the first line. It ignores comments and
/// unknown fields. It carries the most recent valid event id into later events.
public final class EventStream {

    private EventStream() {}

    /// Returns a gatherer that converts event-stream lines into [Signal] values.
    ///
    /// The gatherer emits [Retry] immediately after a valid `retry:` line. It
    /// emits [Event] only when a blank line ends an event. It preserves input
    /// order and drops an event that remains incomplete when input ends.
    /// Consecutive `data:` fields join with line feeds. An event with no data
    /// does not emit. The event type resets after each blank line. The last
    /// valid event id stays in force until another `id:` field changes it.
    ///
    /// The gatherer ignores comments, unknown fields, invalid retry values,
    /// and id values that contain NUL.
    ///
    /// The caller supplies lines through a stream such as
    /// `reader.lines()`. This method does not consume that stream by itself.
    public static Gatherer<String, ?, Signal> signals() {
        return Gatherer.ofSequential(
                Parse::new,
                Gatherer.Integrator.<Parse, String, Signal>of((state, line, downstream) -> state.line(line, downstream)));
    }

    /// Returns a lazy stream of signals read from `in`.
    ///
    /// The returned stream emits [Event] and [Retry] values in input order. It
    /// reads only when a terminal stream operation requests the next value.
    /// The supplied reader controls byte decoding. The parser recognizes CR,
    /// LF, and CRLF line endings and drops an incomplete final event.
    ///
    /// Closing the returned stream closes `in`. The caller must close the
    /// returned stream when it stops before input ends or when it owns `in`.
    /// A read or close failure appears as [UncheckedIOException].
    public static Stream<Signal> parse(Reader in) {
        var reader = in instanceof BufferedReader buffered ? buffered : new BufferedReader(in);
        return reader.lines().gather(signals()).onClose(() -> close(reader));
    }

    /// Returns a lazy stream containing only the [Event] values from `in`.
    ///
    /// The stream preserves event order and discards [Retry] values. Closing
    /// it closes the underlying stream and therefore closes `in`.
    public static Stream<Event> events(Reader in) {
        return parse(in).<Event>mapMulti((signal, events) -> {
            if (signal instanceof Event event) events.accept(event);
        });
    }

    /// Returns an HTTP body handler that parses a response as event-stream data.
    ///
    /// ```
    /// var response = client.send(request, EventStream.body());
    /// try (var signals = response.body()) { ... }
    /// ```
    ///
    /// The handler decodes body bytes as UTF-8 and ignores the response
    /// charset. The response body becomes a lazy stream of [Event] and
    /// [Retry] values in wire order. The caller must close that stream to
    /// release the response body when it stops reading. The handler does not
    /// validate the response `Content-Type` header.
    public static HttpResponse.BodyHandler<Stream<Signal>> body() {
        return info -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofLines(StandardCharsets.UTF_8),
                lines -> lines.gather(signals()));
    }

    /// Writes one signal to `out` and flushes `out`.
    ///
    /// An [Event] writes its type, id, each data line, and a blank terminator.
    /// A line feed in event data starts another `data:` line. A carriage return
    /// or NUL in event data causes [IllegalArgumentException]. A line break or
    /// NUL in an event type or id also causes that exception.
    ///
    /// A [Retry] writes its numeric value on one `retry:` line without
    /// validation. The method writes no event terminator after a [Retry]. It
    /// propagates [IOException] from `out`.
    public static void write(Signal signal, Writer out) throws IOException {
        switch (signal) {
            case Retry(var milliseconds) -> out.write("retry: " + milliseconds + "\n");
            case Event(var type, var data, var id) -> event(type, data, id, out);
        }
        out.flush();
    }

    /// Writes one event-stream comment to `out` and flushes `out`.
    ///
    /// The comment has no event effect. A line break or NUL in `text` causes
    /// [IllegalArgumentException]. The method propagates [IOException] from
    /// `out`.
    public static void comment(String text, Writer out) throws IOException {
        out.write(line("", text));
        out.flush();
    }

    private static void event(String type, String data, String id, Writer out) throws IOException {
        if (!type.equals(Event.MESSAGE)) out.write(line("event", type));
        if (!id.isEmpty()) out.write(line("id", id));
        for (var text : data.split("\n", -1)) out.write(line("data", text));
        out.write("\n");
    }

    /// One field, refused rather than mangled if it would not survive the trip.
    /// A newline in a field is not an escaping problem to solve; it is another
    /// field, and letting one through would let a caller forge events.
    private static String line(String field, String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(named(field) + " cannot contain a line break or a NUL: " + display(value));
        }
        return field + ": " + value + "\n";
    }

    /// A comment has no field name, which makes for a poor complaint.
    private static String named(String field) {
        return field.isEmpty() ? "a comment" : field;
    }

    private static String display(String value) {
        return value.length() > 40 ? value.substring(0, 40) + "..." : value;
    }

    private static void close(Reader reader) {
        try {
            reader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The three buffers the format describes, plus the last event id — which
    /// is not reset between events, because that is the whole point of it.
    private static final class Parse {

        private final StringBuilder data = new StringBuilder();
        private String type = "";
        private String id = "";
        private boolean begun;

        private boolean line(String line, Gatherer.Downstream<? super Signal> downstream) {
            var text = start(line);
            if (text.isEmpty()) return dispatch(downstream);
            if (text.startsWith(":")) return true;
            var colon = text.indexOf(':');
            return colon < 0
                    ? field(text, "", downstream)
                    : field(text.substring(0, colon), value(text, colon), downstream);
        }

        /// A byte order mark belongs to the stream, not to the first line, and
        /// only the first one is a mark — a second is somebody's data.
        private String start(String line) {
            if (begun) return line;
            begun = true;
            return line.startsWith("\uFEFF") ? line.substring(1) : line;
        }

        /// One space after the colon is punctuation and belongs to the format.
        /// A second one is the value's.
        private static String value(String line, int colon) {
            var value = line.substring(colon + 1);
            return value.startsWith(" ") ? value.substring(1) : value;
        }

        private boolean field(String field, String value, Gatherer.Downstream<? super Signal> downstream) {
            switch (field) {
                case "event" -> type = value;
                case "data" -> data.append(value).append('\n');
                case "id" -> id = value.indexOf('\0') < 0 ? value : id;
                case "retry" -> {
                    return retry(value, downstream);
                }
                default -> {
                    // a field nobody knows is a field nobody has to know about
                }
            }
            return true;
        }

        private static boolean retry(String value, Gatherer.Downstream<? super Signal> downstream) {
            if (value.isEmpty() || !value.chars().allMatch(digit -> digit >= '0' && digit <= '9')) return true;
            try {
                return downstream.push(new Retry(Long.parseLong(value)));
            } catch (NumberFormatException more) {
                return true;
            }
        }

        /// The blank line. The last event id is taken up even when nothing is
        /// dispatched, so an `id:` on its own still applies to what follows.
        private boolean dispatch(Gatherer.Downstream<? super Signal> downstream) {
            var text = data.toString();
            var name = type;
            data.setLength(0);
            type = "";
            if (text.isEmpty()) return true;
            return downstream.push(new Event(name, text.substring(0, text.length() - 1), id));
        }
    }
}
