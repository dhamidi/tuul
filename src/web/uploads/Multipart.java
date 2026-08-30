package web.uploads;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import web.Headers;
import web.Request;

/// A `multipart/form-data` body, read as it arrives.
///
/// One part at a time, through a buffer the size of a page, however large the
/// body is. Nothing is held: the part being read is the only part that exists,
/// and asking for the next one ends the last. That is the difference between a
/// server that accepts a large upload and one that accepts a large upload
/// twenty times at once.
///
/// The format is a delimiter — `CRLF--boundary` — between parts, each with its
/// own headers, and `--` after the last. The reading is a search for that
/// delimiter that never lets go of more bytes than the delimiter is long, so a
/// boundary split across two reads is still found.
///
/// Every limit in [Limits] is checked while reading. A part that is too large
/// fails partway through rather than after arriving, which is the only kind of
/// limit worth having.
public final class Multipart implements Closeable {

    private static final String TYPE = "multipart/form-data";

    private final InputStream source;
    private final byte[] delimiter;
    private final Limits limits;
    private final byte[] buffer;

    private int start;
    private int end;
    private boolean drained;
    private boolean finished;
    private int parts;
    private long total;
    private Body current;

    private Multipart(InputStream source, String boundary, Limits limits) {
        this.delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        this.limits = limits;
        this.buffer = new byte[Math.max(8192, delimiter.length * 2 + 8)];
        // The first delimiter in the body has no CRLF before it. Supplying one
        // makes the first part exactly like every other, rather than a case.
        this.source = new SequenceInputStream(
                new ByteArrayInputStream("\r\n".getBytes(StandardCharsets.US_ASCII)), source);
    }

    public static boolean is(Request request) {
        return request.type().equals(TYPE);
    }

    public static Multipart of(Request request) {
        return of(request, Limits.DEFAULT);
    }

    public static Multipart of(Request request, Limits limits) {
        if (!is(request)) {
            throw new UploadException("this is a " + request.type() + " rather than a " + TYPE);
        }
        var boundary = parameter(request.headers().first("Content-Type", ""), "boundary")
                .orElseThrow(() -> new UploadException("a " + TYPE + " body with no boundary to split it on"));
        return new Multipart(request.body(), boundary, limits);
    }

    /// The next part, or nothing when the body is done.
    ///
    /// Whatever is left of the part before is thrown away first, so a caller
    /// that read half a file and lost interest does not leave the reader
    /// somewhere in the middle of it.
    public Optional<Part> next() throws IOException {
        if (current != null) {
            current.drain();
            current = null;
        }
        if (finished) return Optional.empty();
        if (!seek()) return Optional.empty();
        if (closing()) {
            finished = true;
            return Optional.empty();
        }
        if (++parts > limits.parts()) {
            throw new UploadException("more than " + limits.parts() + " parts in one upload", 413);
        }
        var headers = headers();
        var disposition = headers.first("Content-Disposition", "");
        var name = parameter(disposition, "name")
                .orElseThrow(() -> new UploadException("a part with no name in its Content-Disposition"));
        current = new Body();
        return Optional.of(new Part(name, parameter(disposition, "filename"),
                headers.first("Content-Type", "text/plain"), headers, current, limits.fieldBytes()));
    }

    /// Every part, handed to something that does the work — the shape a caller
    /// wants when they are not writing their own loop.
    public void each(PartReader reader) throws IOException {
        for (var part = next(); part.isPresent(); part = next()) reader.read(part.get());
    }

    /// What [#each] hands each part to.
    @FunctionalInterface
    public interface PartReader {
        void read(Part part) throws IOException;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    /// Moves to the delimiter, discarding whatever is before it — which for a
    /// well-formed body is nothing, and for one with a preamble is the
    /// preamble.
    private boolean seek() throws IOException {
        while (true) {
            var at = find();
            if (at >= 0) {
                advance(at - start);
                advance(delimiter.length);
                return true;
            }
            advance(Math.max(0, end - start - (delimiter.length - 1)));
            if (!fill()) return false;
        }
    }

    /// Whether what follows the delimiter is `--`, which ends the body.
    ///
    /// A body that stops immediately after a delimiter with nothing following
    /// is treated as ended rather than malformed. Every part has already been
    /// read at that point, so refusing would throw away a complete answer over
    /// two missing characters.
    private boolean closing() throws IOException {
        ensure(2);
        if (end - start < 2) return true;
        if (buffer[start] != '-' || buffer[start + 1] != '-') {
            line();
            return false;
        }
        advance(2);
        return true;
    }

    private Headers headers() throws IOException {
        var values = new LinkedHashMap<String, List<String>>();
        var read = 0;
        while (true) {
            var line = line();
            read += line.length() + 2;
            if (read > limits.headerBytes()) throw Limits.exceeded("the headers of a part", limits.headerBytes());
            if (line.isEmpty()) return new Headers(values);
            var colon = line.indexOf(':');
            if (colon < 0) continue;
            values.computeIfAbsent(line.substring(0, colon).strip(), ignored -> new ArrayList<>())
                    .add(line.substring(colon + 1).strip());
        }
    }

    /// One line, up to the CRLF, which is consumed. A part's headers are text
    /// and are short; the bound is the caller's.
    private String line() throws IOException {
        var line = new StringBuilder();
        while (true) {
            ensure(2);
            if (end - start == 0) throw malformed("the body ended in the middle of a part's headers");
            if (buffer[start] == '\r' && end - start >= 2 && buffer[start + 1] == '\n') {
                advance(2);
                return line.toString();
            }
            line.append((char) (buffer[start] & 0xff));
            advance(1);
            if (line.length() > limits.headerBytes()) throw Limits.exceeded("a header line", limits.headerBytes());
        }
    }

    /// Where the delimiter starts in what has been read, or -1.
    private int find() {
        for (var at = start; at + delimiter.length <= end; at++) {
            var found = true;
            for (var i = 0; i < delimiter.length && found; i++) found = buffer[at + i] == delimiter[i];
            if (found) return at;
        }
        return -1;
    }

    /// Reads until there are at least this many bytes to look at, or the source
    /// runs out.
    private void ensure(int wanted) throws IOException {
        while (end - start < wanted && fill()) {
            // fill until there is enough or there is no more
        }
    }

    /// More bytes, compacting first so the window has somewhere to grow.
    /// Answers whether anything arrived.
    private boolean fill() throws IOException {
        if (drained) return false;
        if (start > 0) {
            System.arraycopy(buffer, start, buffer, 0, end - start);
            end -= start;
            start = 0;
        }
        if (end == buffer.length) return false;
        var read = source.read(buffer, end, buffer.length - end);
        if (read < 0) {
            drained = true;
            return false;
        }
        end += read;
        return read > 0;
    }

    /// Moves the window on. Deliberately not called `skip`: inside [Body],
    /// which is an [InputStream], an unqualified `skip` binds to the one
    /// inherited from it rather than this one — and that one would consume the
    /// source instead of the buffer, quietly.
    private void advance(int count) {
        start += count;
        count(count);
    }

    private void count(int bytes) {
        total += bytes;
        if (total > limits.totalBytes()) throw Limits.exceeded("this upload", limits.totalBytes());
    }

    private UploadException malformed(String what) {
        return new UploadException(what);
    }

    /// The stream of one part: everything up to the next delimiter, and never a
    /// byte that might turn out to be the start of one.
    private final class Body extends InputStream {

        private long read;
        private boolean done;

        @Override
        public int read() throws IOException {
            var one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            if (done) return -1;
            while (true) {
                var at = find();
                var usable = at >= 0 ? at - start : end - start - (delimiter.length - 1);
                if (usable > 0) return take(into, offset, Math.min(length, usable));
                if (at >= 0) {
                    done = true;
                    return -1;
                }
                if (!fill()) throw malformed("the body ended in the middle of a part");
            }
        }

        private int take(byte[] into, int offset, int count) {
            System.arraycopy(buffer, start, into, offset, count);
            advance(count);
            read += count;
            if (read > limits.partBytes()) throw Limits.exceeded("a part of this upload", limits.partBytes());
            return count;
        }

        /// Throws away whatever is left, so the reader ends up at the delimiter
        /// whether or not anybody wanted the bytes.
        private void drain() throws IOException {
            var waste = new byte[8192];
            while (read(waste, 0, waste.length) >= 0) {
                // the point is the reading
            }
        }
    }

    /// A parameter out of a structured header — `name="file"` out of a
    /// Content-Disposition, `boundary=x` out of a Content-Type.
    ///
    /// The starred form wins where it is present: `filename*=UTF-8''%e2%98%95`
    /// is how a name that is not ASCII arrives, and reading only the plain
    /// `filename` beside it would silently mangle it.
    static Optional<String> parameter(String header, String name) {
        var starred = plain(header, name + "*");
        if (starred.isPresent()) return starred.map(Multipart::extended);
        return plain(header, name);
    }

    private static Optional<String> plain(String header, String name) {
        for (var parameter : split(header)) {
            var equals = parameter.indexOf('=');
            if (equals < 0) continue;
            if (!parameter.substring(0, equals).strip().equalsIgnoreCase(name)) continue;
            return Optional.of(unquote(parameter.substring(equals + 1).strip()));
        }
        return Optional.empty();
    }

    /// Semicolons separate parameters, except inside a quoted value.
    private static List<String> split(String header) {
        var parameters = new ArrayList<String>();
        var parameter = new StringBuilder();
        var quoted = false;
        var escaped = false;
        for (var character : header.toCharArray()) {
            if (escaped) {
                escaped = false;
                parameter.append(character);
                continue;
            }
            if (character == '\\' && quoted) {
                escaped = true;
                parameter.append(character);
                continue;
            }
            if (character == '"') quoted = !quoted;
            if (character == ';' && !quoted) {
                parameters.add(parameter.toString());
                parameter.setLength(0);
                continue;
            }
            parameter.append(character);
        }
        parameters.add(parameter.toString());
        return parameters;
    }

    private static String unquote(String value) {
        if (value.length() < 2 || value.charAt(0) != '"' || !value.endsWith("\"")) return value;
        var unquoted = new StringBuilder();
        for (var i = 1; i < value.length() - 1; i++) {
            var character = value.charAt(i);
            if (character == '\\' && i + 1 < value.length() - 1) character = value.charAt(++i);
            unquoted.append(character);
        }
        return unquoted.toString();
    }

    /// `UTF-8''%e2%98%95` — a charset, a language nobody sends, and the value
    /// percent-encoded.
    private static String extended(String value) {
        var parts = value.split("'", 3);
        if (parts.length < 3) return value;
        var charset = parts[0].isEmpty() ? StandardCharsets.UTF_8 : charset(parts[0]);
        return URLDecoder.decode(parts[2].replace("+", "%2B"), charset);
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name.toUpperCase(Locale.ROOT));
        } catch (RuntimeException unknown) {
            return StandardCharsets.UTF_8;
        }
    }
}
