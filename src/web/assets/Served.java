package web.assets;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Optional;

/// What should be sent, without saying how to send it.
///
/// This package knows about files, digests and caching; it does not know about
/// [com.sun.net.httpserver], and should not. `web` takes one of these and puts
/// it on the wire — which also means an asset can be served over anything, and
/// tested without a socket.
///
/// The body is the asset itself rather than its bytes, so nothing is read until
/// somebody asks for it. A 304 has no body at all, which is the entire point of
/// having sent an ETag.
public record Served(int status, Map<String, String> headers, Optional<Asset> body) {

    public Served {
        headers = Map.copyOf(headers);
    }

    public boolean ok() {
        return status == 200;
    }

    /// How long the body is, or nothing when there is no body — a caller has to
    /// tell the difference, because a `Content-Length: 0` and no length at all
    /// mean different things to a client.
    public Optional<Long> length() {
        return body.map(Asset::length);
    }

    public void writeTo(OutputStream out) throws IOException {
        if (body.isPresent()) body.get().writeTo(out);
    }
}
