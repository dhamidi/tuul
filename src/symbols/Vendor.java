package symbols;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The dependencies of a tuul-managed project: every jar under `vendor/`.
///
/// The directory is the manifest — one directory per artifact, holding the
/// binary jar and, next to it, the sources jar. That sources jar is why
/// `tuul docs` can answer for a dependency the same way it answers for your own
/// code: the class file gives the symbols, the sources jar gives the comments.
///
/// Nothing here reads a manifest, a lockfile or a coordinate. What is on disk
/// is what the project depends on.
public final class Vendor {

    private static final String SOURCES = "-sources.jar";

    private final List<Path> binaries;
    private final Map<Path, Path> sources;

    private Vendor(List<Path> binaries, Map<Path, Path> sources) {
        this.binaries = binaries;
        this.sources = sources;
    }

    public static Vendor none() {
        return new Vendor(List.of(), Map.of());
    }

    /// Scans the given directories — `vendor/`, `vendor/test/`, anywhere — for
    /// jars, pairing each binary with its sources.
    public static Vendor of(List<Path> roots) throws IOException {
        var jars = new ArrayList<Path>();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                tree.filter(path -> path.toString().endsWith(".jar")).sorted().forEach(jars::add);
            }
        }
        var binaries = jars.stream().filter(jar -> !jar.toString().endsWith(SOURCES)).toList();
        var sources = new LinkedHashMap<Path, Path>();
        for (var binary : binaries) attach(binary, jars).ifPresent(source -> sources.put(binary, source));
        return new Vendor(binaries, sources);
    }

    /// What javac needs on its classpath to compile the project against these
    /// dependencies.
    public List<Path> classpath() {
        return binaries;
    }

    public Optional<Origin> lookup(String binaryName, String sourceFile) {
        var classFile = binaryName.replace('.', '/') + ".class";
        for (var jar : binaries) {
            var found = entry(jar, classFile);
            if (found.isEmpty()) continue;
            return found.map(path -> new Origin(read(path), source(jar, sourceFile)));
        }
        return Optional.empty();
    }

    /// `foo-1.2.jar` is documented by `foo-1.2-sources.jar` beside it; failing
    /// that, by the one sources jar in the same directory, since `tuul add`
    /// gives every artifact a directory of its own.
    private static Optional<Path> attach(Path binary, List<Path> jars) {
        var name = binary.getFileName().toString();
        var expected = binary.resolveSibling(name.substring(0, name.length() - ".jar".length()) + SOURCES);
        if (Files.isRegularFile(expected)) return Optional.of(expected);
        var beside = jars.stream()
                .filter(jar -> jar.toString().endsWith(SOURCES))
                .filter(jar -> jar.getParent().equals(binary.getParent()))
                .toList();
        return beside.size() == 1 ? Optional.of(beside.getFirst()) : Optional.empty();
    }

    /// Sources come from the sources jar, or from the binary jar itself when an
    /// artifact ships both together.
    private Optional<String> source(Path binary, String sourceFile) {
        var jar = sources.getOrDefault(binary, binary);
        return entry(jar, sourceFile).map(Vendor::text);
    }

    private static Optional<Path> entry(Path jar, String name) {
        return Archives.of(jar)
                .map(archive -> archive.getPath("/" + name))
                .filter(Files::isRegularFile);
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
