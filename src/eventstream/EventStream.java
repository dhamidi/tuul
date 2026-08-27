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

/// `text/event-stream`, in both directions.
///
/// Parsing is a [Gatherer] over lines:
///
/// ```
/// reader.lines().gather(EventStream.signals()).forEach(signal -> ...);
/// ```
///
/// That is the whole design decision. A gatherer is lazy, composes with
/// everything else a stream can do, and needs no pipeline, no callback
/// interface and no thread of its own — so this library adds a format, not a
/// world. Line splitting is [BufferedReader]'s, which already ends a line on
/// CR, LF or CRLF, exactly as the format requires.
///
/// An event is dispatched by the blank line that ends it, and only by that. A
/// stream that stops in the middle of an event dispatches nothing, because a
/// half-received event is not an event — the connection dropped, and whoever
/// reconnects will be sent it again.
public final class EventStream {

    private EventStream() {}

    /// Lines in, signals out.
    public static Gatherer<String, ?, Signal> signals() {
        return Gatherer.ofSequential(
                Parse::new,
                Gatherer.Integrator.<Parse, String, Signal>of((state, line, downstream) -> state.line(line, downstream)));
    }

    /// Reads a stream lazily. Closing what comes back closes `in` — everything
    /// else about a [Reader] is the caller's business, but a stream that owns a
    /// resource has to be able to let go of it.
    public static Stream<Signal> parse(Reader in) {
        var reader = in instanceof BufferedReader buffered ? buffered : new BufferedReader(in);
        return reader.lines().gather(signals()).onClose(() -> close(reader));
    }

    /// The events alone, for the callers that do not reconnect and so have no
    /// use for what the server suggested if they did.
    public static Stream<Event> events(Reader in) {
        return parse(in).<Event>mapMulti((signal, events) -> {
            if (signal instanceof Event event) events.accept(event);
        });
    }

    /// Reads an event stream straight out of an [java.net.http.HttpClient]
    /// response, which is where most of them come from:
    ///
    /// ```
    /// var response = client.send(request, EventStream.body());
    /// try (var signals = response.body()) { ... }
    /// ```
    ///
    /// The charset is UTF-8 and not negotiable — the format says so, whatever a
    /// `Content-Type` header claims.
    public static HttpResponse.BodyHandler<Stream<Signal>> body() {
        return info -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofLines(StandardCharsets.UTF_8),
                lines -> lines.gather(signals()));
    }

    /// Writes one signal and flushes. The flush is the point: an event sitting
    /// in a buffer has not been sent, and a client waiting on a stream cannot
    /// tell the difference between that and silence.
    public static void write(Signal signal, Writer out) throws IOException {
        switch (signal) {
            case Retry(var milliseconds) -> out.write("retry: " + milliseconds + "\n");
            case Event(var type, var data, var id) -> event(type, data, id, out);
        }
        out.flush();
    }

    /// A comment, which is how a server keeps a connection warm through a proxy
    /// that would otherwise close it.
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
