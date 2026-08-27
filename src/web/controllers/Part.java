package web.controllers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import web.Headers;

/// One piece of a multipart body, while it is being read.
///
/// The body is a stream that is valid until the next part is asked for, and
/// then it is not. That is what makes an upload of a hundred megabytes cost a
/// buffer rather than a hundred megabytes — and it is why this is a class with
/// a lifetime rather than a record of what was in it.
///
/// The filename is what the client suggested, and it is a suggestion in the
/// strongest sense: it arrived from the network, it may contain anything, and
/// nothing here or anywhere else may let it decide where a file lands. See
/// [Uploads].
public final class Part {

    private final String name;
    private final Optional<String> filename;
    private final String type;
    private final Headers headers;
    private final InputStream body;
    private final long limit;

    Part(String name, Optional<String> filename, String type, Headers headers, InputStream body, long limit) {
        this.name = name;
        this.filename = filename;
        this.type = type;
        this.headers = headers;
        this.body = body;
        this.limit = limit;
    }

    /// The form field this part was sent as.
    public String name() {
        return name;
    }

    /// What the client called the file, if it said this was one. Never a path,
    /// never a destination — see [#suggested].
    public Optional<String> filename() {
        return filename;
    }

    /// Whether this part is a file rather than an ordinary form field, which is
    /// decided by the client having named one.
    public boolean file() {
        return filename.isPresent();
    }

    public String type() {
        return type;
    }

    public Headers headers() {
        return headers;
    }

    /// The bytes of this part, until the next one is asked for.
    public InputStream body() {
        return body;
    }

    /// This part as text, for the ordinary form fields that travel beside the
    /// files. Bounded, because a field is not an upload and a body that claims
    /// to be one should not be read as though it were.
    public String text() throws IOException {
        var bytes = body.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE));
        if (bytes.length > limit) throw Limits.exceeded("the field " + name, limit);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /// The client's filename, reduced to something safe to show a person: the
    /// last segment, with anything that is not a plain character removed.
    ///
    /// Safe to *show*. Still not safe to use as a path, and this does not make
    /// it so — `....//passwd` reduces to something harmless, and the reason
    /// nothing is written under this name is that the next hostile name will
    /// not.
    public String suggested() {
        var given = filename.orElse("");
        var slash = Math.max(given.lastIndexOf('/'), given.lastIndexOf('\\'));
        var last = slash < 0 ? given : given.substring(slash + 1);
        var safe = new StringBuilder();
        for (var character : last.toCharArray()) {
            var plain = Character.isLetterOrDigit(character) || character == '.' || character == '-'
                    || character == '_' || character == ' ';
            safe.append(plain ? character : '_');
        }
        var cleaned = safe.toString().strip().replaceAll("^\\.+", "");
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }

    @Override
    public String toString() {
        return filename.map(given -> name + " (" + given + ")").orElse(name);
    }
}
