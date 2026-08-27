package symbols;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/// Zip archives read as file systems: vendored jars, and the JDK's own
/// `lib/src.zip`.
///
/// An archive is opened once and stays open. Paths taken out of a closed file
/// system stop working, tuul is a command rather than a server, and the process
/// exit closes them all — so the alternative buys nothing and costs a class of
/// bug.
final class Archives {

    private static final Map<Path, FileSystem> open = new HashMap<>();

    private Archives() {}

    static synchronized Optional<FileSystem> of(Path archive) {
        if (!Files.isRegularFile(archive)) return Optional.empty();
        var key = archive.toAbsolutePath().normalize();
        var opened = open.get(key);
        if (opened != null) return Optional.of(opened);
        try {
            var zip = FileSystems.newFileSystem(key);
            open.put(key, zip);
            return Optional.of(zip);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /// The sources that ship with the running JDK. Absent on a JDK installed
    /// without them, in which case the JDK's symbols simply come without their
    /// documentation.
    static Optional<FileSystem> jdkSources() {
        return of(Path.of(System.getProperty("java.home"), "lib", "src.zip"));
    }
}
