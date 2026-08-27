package symbols;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sqlite3.SqliteException;

/// Every symbol tuul can answer questions about, in the order it looks: the
/// project's own sources, the jars under `vendor/`, and the running JDK.
///
/// A symbol is answered from two places at once. The class file says what the
/// type *is* — javac's output for the project, a vendored jar for a dependency,
/// `jrt:/` for the JDK. The source says what it *means*, because doc comments
/// do not survive compilation: the file on disk for the project, the
/// `-sources.jar` for a dependency, `lib/src.zip` for the JDK.
///
/// All of that is expensive and almost none of it changes, so the answers are
/// kept in a [Store] between runs. A lookup starts there, and only falls
/// through to javac when the sources have moved on. The compile is lazy for the
/// same reason: a question the index can answer must not pay for one.
public final class Index implements AutoCloseable {

    /// Derived data, and deliberately under `build/`: deleting it costs the
    /// time to build it again and nothing else.
    public static final Path INDEX = Path.of("build", "index.db");

    /// A symbol the search found: what it is called, what kind of thing it is,
    /// and what it says about itself.
    public record Match(String symbol, String kind, String doc) {}

    private final List<Path> roots;
    private final Vendor vendor;
    private final Optional<Store> store;
    private final String stamp;
    private Map<String, byte[]> classes;

    private Index(List<Path> roots, Vendor vendor, Optional<Store> store, String stamp) {
        this.roots = List.copyOf(roots);
        this.vendor = vendor;
        this.store = store;
        this.stamp = stamp;
    }

    public static Index of(List<Path> sourceRoots) throws IOException {
        return of(sourceRoots, List.of());
    }

    public static Index of(List<Path> sourceRoots, List<Path> vendorRoots) throws IOException {
        return of(sourceRoots, vendorRoots, INDEX);
    }

    /// The project as it stands: its sources compiled against its dependencies,
    /// and those dependencies available to be asked about themselves.
    public static Index of(List<Path> sourceRoots, List<Path> vendorRoots, Path index) throws IOException {
        var vendor = Vendor.of(vendorRoots);
        return new Index(sourceRoots, vendor, Store.open(index), stamp(sourceRoots, vendor));
    }

    /// Looks up a type by name. `a.b.Outer.Inner` also matches the nested type
    /// `a.b.Outer$Inner`, because that is how people write it.
    public Optional<TypeInfo> lookup(String name) {
        return project(name).or(() -> vendored(name)).or(() -> platform(name));
    }

    /// Every type name known from source. Vendored and JDK types are found on
    /// demand rather than enumerated.
    public List<String> names() {
        var origin = origin("project", "sources", stamp);
        if (origin.isPresent() && origin.get().fresh() && origin.get().complete()) {
            try {
                return store.orElseThrow().names(origin.get().id());
            } catch (SqliteException unavailable) {
                // ask javac instead
            }
        }
        indexProject();
        return List.copyOf(compile().keySet());
    }

    /// The symbols whose name or documentation match, best first. Only what has
    /// been indexed can be found, so the project is indexed first — the JDK and
    /// the jars turn up as they are asked about.
    public List<Match> search(String text, int limit) {
        var kept = store.orElseThrow(() -> new IllegalStateException("there is no index to search"));
        indexProject();
        return kept.search(text, limit);
    }

    @Override
    public void close() {
        store.ifPresent(Store::close);
    }

    private Optional<TypeInfo> project(String name) {
        var origin = origin("project", "sources", stamp);
        if (origin.isPresent() && origin.get().fresh()) {
            var kept = kept(origin.get().id(), name);
            if (kept.isPresent()) return kept;
            if (origin.get().complete()) return Optional.empty();
        }
        indexProject();
        return origin.isPresent()
                ? kept(origin.get().id(), name)
                : built(name, this::compiled).map(Found::type);
    }

    private Optional<TypeInfo> vendored(String name) {
        return remembered("vendor", "vendor", vendor.stamp(), name, this::vendoredOrigin);
    }

    /// The JDK is stamped with its own version. It is the one origin that never
    /// changes without being replaced outright.
    private Optional<TypeInfo> platform(String name) {
        return remembered("platform", System.getProperty("java.home"), Runtime.version().toString(), name,
                Index::platformOrigin);
    }

    /// The dependencies and the JDK are indexed a type at a time: there is no
    /// point enumerating a jar nobody asked about. So a miss here means *not
    /// indexed yet* rather than *not there*, and falls through to the work.
    private Optional<TypeInfo> remembered(String kind, String location, String stamp, String name, Origins origins) {
        var origin = origin(kind, location, stamp);
        if (origin.isPresent() && origin.get().fresh()) {
            var kept = kept(origin.get().id(), name);
            if (kept.isPresent()) return kept;
        }
        var built = built(name, origins);
        built.ifPresent(found -> origin.ifPresent(where ->
                remember(where.id(), Map.of(found.name(), found.type()), false)));
        return built.map(Found::type);
    }

    private interface Origins {
        Optional<Origin> at(String candidate);
    }

    /// A type and the binary name it was found under, which is what it gets
    /// filed as.
    private record Found(String name, TypeInfo type) {}

    private Optional<Found> built(String name, Origins origins) {
        for (var candidate : candidates(name)) {
            var found = origins.at(candidate);
            if (found.isEmpty()) continue;
            return found.map(origin ->
                    new Found(candidate, document(Classes.inspect(origin.classFile()), candidate, origin.source())));
        }
        return Optional.empty();
    }

    private Optional<TypeInfo> kept(long origin, String name) {
        try {
            for (var candidate : candidates(name)) {
                var kept = store.orElseThrow().type(origin, candidate);
                if (kept.isPresent()) return kept;
            }
        } catch (SqliteException unavailable) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /// The index is an optimisation, never a gate. An index that is busy —
    /// another tuul is writing it — or that cannot be read at all means the
    /// answer comes the slow way, not that there is no answer.
    private Optional<Store.Origin> origin(String kind, String location, String stamp) {
        try {
            return store.map(kept -> kept.origin(kind, location, stamp));
        } catch (SqliteException unavailable) {
            return Optional.empty();
        }
    }

    /// Writing what was learned is worth doing and not worth failing over.
    private void remember(long origin, Map<String, TypeInfo> types, boolean complete) {
        try {
            store.orElseThrow().write(origin, types, complete);
        } catch (SqliteException unavailable) {
            // the next run will learn it again
        }
    }

    /// Compiles the project once and writes down everything it holds. Doing the
    /// whole tree at once is what earns the right to say a name is *not* there
    /// without compiling again.
    private void indexProject() {
        var origin = origin("project", "sources", stamp);
        if (origin.isEmpty()) return;
        if (origin.get().fresh() && origin.get().complete()) return;

        var comments = new HashMap<String, Map<String, Javadoc.Comment>>();
        var types = new LinkedHashMap<String, TypeInfo>();
        compile().forEach((name, bytes) -> types.put(name, documented(Classes.inspect(bytes), name, comments)));
        remember(origin.get().id(), types, true);
    }

    /// One source file holds a type and everything nested in it, so its
    /// comments are read once and shared between them.
    private TypeInfo documented(TypeInfo type, String name, Map<String, Map<String, Javadoc.Comment>> comments) {
        var file = sourceFile(name);
        var read = comments.computeIfAbsent(file, path -> roots.stream()
                .map(root -> root.resolve(path))
                .filter(Files::isRegularFile)
                .findFirst()
                .map(found -> Javadoc.of(text(found), file(name)))
                .orElse(Map.of()));
        return read.isEmpty() ? type : Javadoc.attach(type, read, path(name));
    }

    private Map<String, byte[]> compile() {
        if (classes != null) return classes;
        try {
            classes = Sources.compile(roots, vendor.classpath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return classes;
    }

    private Optional<Origin> compiled(String name) {
        var bytes = compile().get(name);
        if (bytes == null) return Optional.empty();
        var source = roots.stream()
                .map(root -> root.resolve(sourceFile(name)))
                .filter(Files::isRegularFile)
                .findFirst()
                .map(Index::text);
        return Optional.of(new Origin(bytes, source));
    }

    private Optional<Origin> vendoredOrigin(String name) {
        return vendor.lookup(name, sourceFile(name));
    }

    /// `/modules/java.base/java/lang/String.class` names the module, and the
    /// module names the directory inside `src.zip`.
    private static Optional<Origin> platformOrigin(String name) {
        var classFile = name.replace('.', '/') + ".class";
        try (var modules = Files.list(FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules"))) {
            return modules
                    .map(module -> module.resolve(classFile))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .map(path -> new Origin(read(path), jdk(path.getName(1).toString(), name)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> jdk(String module, String name) {
        return Archives.jdkSources()
                .map(zip -> zip.getPath(module + "/" + sourceFile(name)))
                .filter(Files::isRegularFile)
                .map(Index::text);
    }

    private static TypeInfo document(TypeInfo type, String name, Optional<String> source) {
        return source
                .map(text -> Javadoc.attach(type, Javadoc.of(text, file(name)), path(name)))
                .orElse(type);
    }

    /// What the project looked like when it was indexed: every source file's
    /// path, size and modification time, and the jars it is compiled against.
    /// Anything that would change what javac produces changes this.
    private static String stamp(List<Path> roots, Vendor vendor) throws IOException {
        var digest = new StringBuilder();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                for (var path : tree.filter(file -> file.toString().endsWith(".java")).sorted().toList()) {
                    digest.append(path).append(':').append(Files.size(path)).append(':')
                            .append(Files.getLastModifiedTime(path).toMillis()).append('\n');
                }
            }
        }
        digest.append(vendor.stamp());
        return fingerprint(digest.toString());
    }

    private static String fingerprint(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("no SHA-256 in this runtime", e);
        }
    }

    /// `a.b.C.D` could be a nested type, so try turning each dot into a `$`
    /// from the right.
    private static List<String> candidates(String name) {
        var candidates = new ArrayList<String>();
        candidates.add(name);
        var binary = name.toCharArray();
        for (var i = binary.length - 1; i >= 0; i--) {
            if (binary[i] != '.') continue;
            binary[i] = '$';
            candidates.add(new String(binary));
        }
        return candidates;
    }

    /// The file that holds the type: `a.b.Outer$Inner` lives in `a/b/Outer.java`.
    private static String sourceFile(String name) {
        return outer(name).replace('.', '/') + ".java";
    }

    private static String outer(String name) {
        var nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    private static String file(String name) {
        var outer = outer(name);
        return outer.substring(outer.lastIndexOf('.') + 1) + ".java";
    }

    /// The type as it is written inside its file: `TypeInfo.Kind`.
    private static String path(String name) {
        return name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
    }

    private static String text(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
