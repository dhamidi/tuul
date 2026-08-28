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
import java.util.TreeSet;

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

    /// What the vendored jars looked like when they were last read: every jar's
    /// path, size and modification time. A dependency that has been replaced
    /// says so here.
    public String stamp() {
        var stamp = new StringBuilder();
        for (var jar : binaries) {
            stamp.append(jar).append(':');
            try {
                stamp.append(Files.size(jar)).append(':').append(Files.getLastModifiedTime(jar).toMillis());
            } catch (IOException gone) {
                stamp.append("gone");
            }
            stamp.append('\n');
        }
        return stamp.toString();
    }

    /// A file inside a vendored jar: what it says, and where a reader would
    /// have to look to see it themselves.
    public record Found(String text, String location) {}

    public Optional<Origin> lookup(String binaryName, String sourceFile) {
        var classFile = binaryName.replace('.', '/') + ".class";
        for (var jar : binaries) {
            var found = entry(jar, classFile);
            if (found.isEmpty()) continue;
            var source = source(jar, sourceFile);
            return found.map(path -> new Origin(read(path), source.map(Found::text),
                    source.map(Found::location).orElse("")));
        }
        return Optional.empty();
    }

    /// The top-level types a package holds, across every vendored jar.
    ///
    /// A jar has no directory entry to ask, so this reads the names it holds
    /// and keeps the ones under this package. `-info` files are the package
    /// speaking rather than a type in it, and a `$` is a nested type, which
    /// belongs to the type that declares it and not to the package listing.
    /// Every package a vendored jar holds a usable type in.
    ///
    /// A dependency has no name a reader can look up — `vendor/x/x-1.0.jar` is
    /// a file, not a symbol — so what the jars contribute to a listing is the
    /// packages inside them, each of which *is* a symbol and answers to
    /// `tuul docs`.
    public List<String> packages() {
        var packages = new TreeSet<String>();
        for (var jar : binaries) {
            var archive = Archives.of(jar);
            if (archive.isEmpty()) continue;
            try (var tree = Files.walk(archive.get().getPath("/"))) {
                for (var path : tree.toList()) {
                    var entry = path.toString().replaceFirst("^/", "");
                    if (!entry.endsWith(".class") || entry.contains("$")) continue;
                    var slash = entry.lastIndexOf('/');
                    if (slash < 0) continue;
                    if (!Classes.visible(read(path))) continue;
                    packages.add(entry.substring(0, slash).replace('/', '.'));
                }
            } catch (IOException unreadable) {
                continue;
            }
        }
        return List.copyOf(packages);
    }

    public List<String> types(String name) {
        var prefix = name.replace('.', '/') + "/";
        var types = new ArrayList<String>();
        for (var jar : binaries) {
            var archive = Archives.of(jar);
            if (archive.isEmpty()) continue;
            try (var tree = Files.walk(archive.get().getPath("/"))) {
                for (var path : tree.toList()) {
                    var entry = path.toString().replaceFirst("^/", "");
                    if (!entry.startsWith(prefix) || !entry.endsWith(".class")) continue;
                    var type = entry.substring(0, entry.length() - ".class".length());
                    if (type.contains("$") || type.endsWith("-info")) continue;
                    if (!Classes.visible(read(path))) continue;
                    types.add(type.replace('/', '.'));
                }
            } catch (IOException unreadable) {
                continue;
            }
        }
        return List.copyOf(types);
    }

    /// What a vendored package says about itself, if its sources jar carries a
    /// `package-info.java`.
    public Optional<Found> packageInfo(String name) {
        var entry = name.replace('.', '/') + "/package-info.java";
        for (var jar : binaries) {
            var found = source(jar, entry);
            if (found.isPresent()) return found;
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
    private Optional<Found> source(Path binary, String sourceFile) {
        var jar = sources.getOrDefault(binary, binary);
        return entry(jar, sourceFile).map(path -> new Found(text(path), jar + "!/" + sourceFile));
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
