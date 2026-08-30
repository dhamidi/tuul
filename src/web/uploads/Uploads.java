package web.uploads;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import web.Parameters;
import web.Request;

/// Taking a form with files in it and putting the files somewhere.
///
/// The whole of the security of an upload is in one sentence: **the name the
/// client sent never decides where the file lands**. Not after being cleaned,
/// not after being checked for `..`, not ever. This server names the file, out
/// of random bytes, and the client's name is carried alongside as a label.
/// Every filename vulnerability there has ever been is a variation on trusting
/// that string, and the only way to be sure is not to have the question.
///
/// The bytes go from the socket to the file. Nothing here reads a whole upload
/// into memory, and the limits in [Limits] are checked as it goes, so a body
/// that is too large stops partway through rather than after arriving.
public final class Uploads {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uploads() {}

    public static Received into(Request request, Path directory) throws IOException {
        return into(request, directory, Limits.DEFAULT);
    }

    /// Reads the request, writing every file into `directory` and collecting
    /// everything else as ordinary fields.
    public static Received into(Request request, Path directory, Limits limits) throws IOException {
        Files.createDirectories(directory);
        var fields = new LinkedHashMap<String, List<String>>();
        var files = new ArrayList<Upload>();
        try (var multipart = Multipart.of(request, limits)) {
            multipart.each(part -> {
                if (!part.file()) {
                    fields.computeIfAbsent(part.name(), ignored -> new ArrayList<>()).add(part.text());
                    return;
                }
                files.add(store(part, directory, limits));
            });
        }
        return new Received(new Parameters(fields), files);
    }

    /// Writes one part to a file this server named, and removes it again if the
    /// part turns out to be too large — a limit that leaves the evidence on
    /// disk is a way to fill a disk.
    private static Upload store(Part part, Path directory, Limits limits) throws IOException {
        var stored = name(directory, part.suggested());
        var written = 0L;
        try (var out = Files.newOutputStream(stored, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            written = copy(part, out, limits);
        } catch (IOException | RuntimeException refused) {
            Files.deleteIfExists(stored);
            throw refused;
        }
        return new Upload(part.name(), part.suggested(), part.type(), stored, written);
    }

    private static long copy(Part part, OutputStream out, Limits limits) throws IOException {
        var buffer = new byte[8192];
        var written = 0L;
        for (var read = part.body().read(buffer); read >= 0; read = part.body().read(buffer)) {
            written += read;
            if (written > limits.partBytes()) throw Limits.exceeded("a file in this upload", limits.partBytes());
            out.write(buffer, 0, read);
        }
        return written;
    }

    /// A name of this server's choosing: random, with the extension the client
    /// suggested only when that extension is plainly an extension.
    ///
    /// The extension is kept because a file that cannot be opened by its name
    /// is a nuisance, and it is rebuilt from scratch rather than taken, so
    /// there is nothing in it a client chose beyond a few letters.
    private static Path name(Path directory, String suggested) {
        var token = new byte[16];
        RANDOM.nextBytes(token);
        var name = HexFormat.of().formatHex(token) + extension(suggested);
        var stored = directory.resolve(name).normalize();
        if (!stored.startsWith(directory.normalize())) {
            throw new UploadException("an upload would have landed outside " + directory);
        }
        return stored;
    }

    private static String extension(String suggested) {
        var dot = suggested.lastIndexOf('.');
        if (dot < 0 || dot == suggested.length() - 1) return "";
        var extension = suggested.substring(dot + 1);
        if (extension.length() > 8) return "";
        for (var character : extension.toCharArray()) {
            if (!Character.isLetterOrDigit(character)) return "";
        }
        return "." + extension.toLowerCase(Locale.ROOT);
    }
}
