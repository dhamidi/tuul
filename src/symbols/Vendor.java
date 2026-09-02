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
import java.util.LinkedHashSet;

/// The dependencies of a tuul-managed project.
///
/// Every binary JAR under `vendor/` enters the runtime and test classpaths.
/// A source or javadoc JAR contributes documentation and does not enter a
/// classpath. No manifest or generated file selects dependencies.
public final class Vendor {

    private static final String SOURCES = "-sources.jar";
    private static final String JAVADOC = "-javadoc.jar";

    private final List<Path> binaries;
    private final Map<Path, Path> sources;
    private final List<Path> javadocs;
    private final Map<Path, String> coordinates;

    private Vendor(List<Path> binaries, Map<Path, Path> sources,
            List<Path> javadocs, Map<Path, String> coordinates) {
        this.binaries = List.copyOf(binaries);
        this.sources = Map.copyOf(sources);
        this.javadocs = List.copyOf(javadocs);
        this.coordinates = Map.copyOf(coordinates);
    }

    public static Vendor none() {
        return new Vendor(List.of(), Map.of(), List.of(), Map.of());
    }

    /// Scans each root for JARs. Missing roots contribute nothing.
    ///
    /// Binary JARs appear in path order. A sibling `-sources.jar` attaches to
    /// its binary. A `-javadoc.jar` appears only in [#javadocs].
    public static Vendor of(List<Path> roots) throws IOException {
        var jars = new ArrayList<Path>();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                tree.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".jar"))
                        .sorted().forEach(jars::add);
            }
        }
        var selected = jars.stream().filter(jar -> !jar.toString().endsWith(SOURCES)
                && !jar.toString().endsWith(JAVADOC)).toList();
        var sources = new LinkedHashMap<Path, Path>();
        var coordinates = new LinkedHashMap<Path, String>();
        for (var binary : selected) coordinates.put(binary, "vendor:" + binary.getFileName());
        for (var binary : selected) attach(binary, jars).ifPresent(source -> sources.put(binary, source));
        var javadocs = jars.stream().filter(jar -> jar.toString().endsWith(JAVADOC)).toList();
        return new Vendor(distinct(selected), sources, distinct(javadocs), coordinates);
    }

    private static List<Path> distinct(List<Path> paths) {
        return List.copyOf(new LinkedHashSet<>(paths));
    }

    /// The binary JARs for compilation and runtime, in path order.
    public List<Path> runtime() {
        return binaries;
    }

    /// The binary JARs for test compilation and runtime, in path order.
    public List<Path> test() {
        return binaries;
    }

    /// The source archives attached to binary JARs, in binary path order.
    public List<Path> sources() {
        return List.copyOf(new LinkedHashSet<>(sources.values()));
    }

    /// Every javadoc JAR under the roots, in path order.
    public List<Path> javadocs() {
        return javadocs;
    }

    /// Every public dependency type in binary-name order.
    public List<String> typeNames() {
        var names = new TreeSet<String>();
        for (var jar : binaries) {
            var archive = Archives.of(jar);
            if (archive.isEmpty()) continue;
            try (var tree = Files.walk(archive.get().getPath("/"))) {
                for (var path : tree.filter(Files::isRegularFile).toList()) {
                    var entry = path.toString().replaceFirst("^/", "");
                    if (!entry.endsWith(".class") || entry.startsWith("META-INF/versions/")
                            || entry.endsWith("module-info.class") || !Classes.visible(read(path))) continue;
                    names.add(entry.substring(0, entry.length() - ".class".length()).replace('/', '.'));
                }
            } catch (IOException unreadable) {
                // This archive contributes no searchable names.
            }
        }
        return List.copyOf(names);
    }

    /// The binary archive label for a source or binary location.
    ///
    /// The label is `vendor:<file name>`. A location outside the scanned
    /// archives returns empty.
    public Optional<String> origin(String location) {
        for (var entry : coordinates.entrySet()) {
            if (location.startsWith(entry.getKey().toString())) return Optional.of(entry.getValue());
            var source = sources.get(entry.getKey());
            if (source != null && location.startsWith(source.toString())) return Optional.of(entry.getValue());
        }
        return Optional.empty();
    }

    /// What the vendored jars looked like when they were last read: every jar's
    /// path, size and modification time. A dependency that has been replaced
    /// says so here.
    public String stamp() {
        var stamp = new StringBuilder();
        for (var jar : binaries) {
            stamp.append(jar).append(':');
            try {
                stamp.append(Files.size(jar)).append(':').append(Files.getLastModifiedTime(jar).toInstant());
            } catch (IOException gone) {
                stamp.append("gone");
            }
            stamp.append('\n');
        }
        return stamp.toString();
    }

    /// The source jars that contribute dependency documentation. This stamp is
    /// separate because changing prose must not invalidate project compilation.
    public String sourceStamp() {
        var stamp = new StringBuilder();
        for (var jar : new java.util.TreeSet<>(sources.values())) {
            stamp.append(jar).append(':');
            try {
                stamp.append(Files.size(jar)).append(':').append(Files.getLastModifiedTime(jar).toInstant());
            } catch (IOException gone) {
                stamp.append("gone");
            }
            stamp.append('\n');
        }
        return stamp.toString();
    }

    public String testStamp() {
        return stamp(binaries);
    }

    private static String stamp(List<Path> jars) {
        var stamp = new StringBuilder();
        for (var jar : jars) {
            stamp.append(jar).append(':');
            try {
                stamp.append(Files.size(jar)).append(':').append(Files.getLastModifiedTime(jar).toInstant());
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
                    source.map(Found::location).orElse(jar + "!/" + classFile)));
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
