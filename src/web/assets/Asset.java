package web.assets;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/// One asset: what it is called, what it is, and what makes its name unique.
///
/// `compiled` is the content when the pipeline rewrote it — a stylesheet whose
/// references were digested is no longer the file on disk, and serving the file
/// would send the wrong thing. Everything else is absent, and is read from disk
/// as it is sent rather than held: a video is an asset too.
///
/// The digest is of what is *sent*, not of what is stored. A stylesheet whose
/// references changed has changed, even though its own bytes on disk did not,
/// and a fingerprint that says otherwise is a stale page in someone's cache.
public record Asset(String logical, String digest, String type, Path file, Optional<String> compiled) {

    /// `images/logo.png` becomes `images/logo-<digest>.png`. The digest goes
    /// before the extension because everything downstream — a server guessing a
    /// type, a browser deciding what to do with it — reads the extension.
    public String digested() {
        var slash = logical.lastIndexOf('/');
        var dot = logical.lastIndexOf('.');
        return dot < 0 || dot < slash
                ? logical + "-" + digest
                : logical.substring(0, dot) + "-" + digest + logical.substring(dot);
    }

    public long length() {
        try {
            return compiled.map(text -> (long) text.getBytes(StandardCharsets.UTF_8).length).orElseGet(this::size);
        } catch (RuntimeException e) {
            throw new AssetException("cannot measure " + logical, e);
        }
    }

    /// Writes the asset out. A compiled one is already in memory — it is text,
    /// and small enough to have been rewritten. Everything else is copied from
    /// the file in chunks, so serving a large asset costs a buffer rather than
    /// its size.
    public void writeTo(OutputStream out) throws IOException {
        if (compiled.isPresent()) {
            out.write(compiled.get().getBytes(StandardCharsets.UTF_8));
            return;
        }
        Files.copy(file, out);
    }

    private long size() {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new AssetException("cannot measure " + logical, e);
        }
    }
}
