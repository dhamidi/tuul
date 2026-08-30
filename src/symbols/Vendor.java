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
import java.util.Set;
import json.Json;

/// The selected dependencies of a tuul-managed project.
///
/// `vendor/.tuul/resolution.json` selects the runtime and test binaries by
/// coordinate. Source and javadoc archives are separate documentation views.
/// They never enter a compile or runtime classpath. A compatibility scan reads
/// projects that do not have a resolution record yet and marks their binaries
/// as unmanaged.
public final class Vendor {

    private static final String SOURCES = "-sources.jar";
    private static final String JAVADOC = "-javadoc.jar";

    private final List<Path> runtime;
    private final List<Path> test;
    private final List<Path> binaries;
    private final Map<Path, Path> sources;
    private final List<Path> javadocs;
    private final Map<Path, String> coordinates;

    private Vendor(List<Path> runtime, List<Path> test, List<Path> binaries,
            Map<Path, Path> sources, List<Path> javadocs, Map<Path, String> coordinates) {
        this.runtime = List.copyOf(runtime);
        this.test = List.copyOf(test);
        this.binaries = binaries;
        this.sources = sources;
        this.javadocs = List.copyOf(javadocs);
        this.coordinates = Map.copyOf(coordinates);
    }

    public static Vendor none() {
        return new Vendor(List.of(), List.of(), List.of(), Map.of(), List.of(), Map.of());
    }

    /// Reads each selected vendor view. A root without metadata uses the flat
    /// layout compatibility reader.
    public static Vendor of(List<Path> roots) throws IOException {
        var runtime = new ArrayList<Path>();
        var test = new ArrayList<Path>();
        var binaries = new ArrayList<Path>();
        var sources = new LinkedHashMap<Path, Path>();
        var javadocs = new ArrayList<Path>();
        var coordinates = new LinkedHashMap<Path, String>();
        for (var root : roots) {
            var resolution = root.resolve(".tuul/resolution.json");
            if (Files.isRegularFile(resolution)) managed(root, resolution, runtime, test, binaries, sources, javadocs,
                    coordinates);
            else compatible(root, runtime, test, binaries, sources, javadocs, coordinates);
        }
        return new Vendor(distinct(runtime), distinct(test), distinct(binaries), sources, distinct(javadocs), coordinates);
    }

    private static void compatible(Path root, List<Path> runtime, List<Path> test, List<Path> binaries,
            Map<Path, Path> sources, List<Path> javadocs, Map<Path, String> coordinates) throws IOException {
        var jars = new ArrayList<Path>();
        if (!Files.isDirectory(root)) return;
        try (var tree = Files.walk(root)) {
            tree.filter(path -> path.toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        var selected = jars.stream().filter(jar -> !jar.toString().endsWith(SOURCES)
                && !jar.toString().endsWith(JAVADOC)).toList();
        runtime.addAll(selected);
        test.addAll(selected);
        binaries.addAll(selected);
        for (var binary : selected) coordinates.put(binary, "unmanaged:" + binary.getFileName());
        for (var binary : selected) attach(binary, jars).ifPresent(source -> sources.put(binary, source));
        javadocs.addAll(jars.stream().filter(jar -> jar.toString().endsWith(JAVADOC)).toList());
    }

    private static void managed(Path root, Path resolution, List<Path> runtime, List<Path> test,
            List<Path> binaries, Map<Path, Path> sources, List<Path> javadocs,
            Map<Path, String> coordinates) throws IOException {
        Json value;
        try (var reader = Files.newBufferedReader(resolution)) {
            value = Json.parse(reader);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid vendor resolution " + resolution + ": " + invalid.getMessage(), invalid);
        }
        if (!(value instanceof Json.Object document)) throw new IOException("vendor resolution is not an object: " + resolution);
        var runtimeCoordinates = coordinates(document.list("runtime"));
        var testCoordinates = coordinates(document.list("test"));
        var sourceByCoordinate = new LinkedHashMap<String, Path>();
        var binaryByCoordinate = new LinkedHashMap<String, Path>();
        for (var valueFile : document.list("files")) {
            if (!(valueFile instanceof Json.Object file)) continue;
            var relative = Path.of(file.string("path", "")).normalize();
            var path = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !path.startsWith(root.normalize()) || !Files.isRegularFile(path)) continue;
            var coordinate = file.string("coordinate", "");
            var kind = file.string("kind", "");
            switch (kind) {
                case "binary" -> {
                    binaryByCoordinate.put(coordinate, path);
                    coordinates.put(path, coordinate);
                }
                case "sources" -> sourceByCoordinate.put(coordinate.replaceFirst(":sources$", ""), path);
                case "javadoc" -> javadocs.add(path);
                default -> {}
            }
        }
        for (var coordinate : runtimeCoordinates) {
            var binary = binaryByCoordinate.get(coordinate);
            if (binary != null) runtime.add(binary);
        }
        for (var coordinate : testCoordinates) {
            var binary = binaryByCoordinate.get(coordinate);
            if (binary != null) test.add(binary);
        }
        var all = new LinkedHashSet<Path>();
        all.addAll(runtime);
        all.addAll(test);
        binaries.addAll(all);
        for (var entry : binaryByCoordinate.entrySet()) {
            var source = sourceByCoordinate.get(entry.getKey());
            if (source != null && all.contains(entry.getValue())) sources.put(entry.getValue(), source);
        }
    }

    private static Set<String> coordinates(List<Json> nodes) {
        var coordinates = new LinkedHashSet<String>();
        for (var value : nodes) if (value instanceof Json.Object node) coordinates.add(node.string("coordinate", ""));
        return coordinates;
    }

    private static List<Path> distinct(List<Path> paths) {
        return List.copyOf(new LinkedHashSet<>(paths));
    }

    /// Returns the selected runtime binaries in resolution order. Build,
    /// compile, and run use this same list.
    public List<Path> runtime() {
        return runtime;
    }

    /// Returns the selected test binaries in resolution order. This list also
    /// contains the runtime graph.
    public List<Path> test() {
        return test;
    }

    /// Returns the source archives for selected runtime and test binaries.
    public List<Path> sources() {
        return List.copyOf(new LinkedHashSet<>(sources.values()));
    }

    /// Returns the javadoc archives for selected runtime and test binaries.
    public List<Path> javadocs() {
        return javadocs;
    }

    /// Returns every public dependency type in binary-name order.
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

    /// Returns the coordinate that owns a source or binary archive location.
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
        for (var jar : runtime) {
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
        return stamp(test);
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
