package web.serve;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import web.Headers;

/// What a handler answered, when nothing was on the wire.
///
/// The flush offsets are here because for an event stream they are the point:
/// a response that arrives in one piece at the end is not a stream, however
/// correct its bytes are, and this is how a test says so without a socket and
/// without waiting.
public record Recorded(int status, Headers headers, byte[] body, List<Integer> flushes, Optional<Exception> failure) {

    public Recorded {
        body = body.clone();
        flushes = List.copyOf(flushes);
    }

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public Optional<String> header(String name) {
        return headers.first(name);
    }

    public boolean ok() {
        return status == 200;
    }

    /// How many times the handler pushed something to the client before the
    /// response ended.
    public int pushes() {
        return flushes.size();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
