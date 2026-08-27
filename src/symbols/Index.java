package symbols;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/// Every symbol tuul can answer questions about, in the order it looks: the
/// project's own sources, the jars under `vendor/`, and the running JDK.
///
/// A symbol is answered from two places at once. The class file says what the
/// type *is* — javac's output for the project, a vendored jar for a dependency,
/// `jrt:/` for the JDK. The source says what it *means*, because doc comments
/// do not survive compilation: the file on disk for the project, the
/// `-sources.jar` for a dependency, `lib/src.zip` for the JDK.
public final class Index {

    private final List<Path> roots;
    private final Vendor vendor;
    private final Map<String, byte[]> classes;

    private Index(List<Path> roots, Vendor vendor, Map<String, byte[]> classes) {
        this.roots = List.copyOf(roots);
        this.vendor = vendor;
        this.classes = classes;
    }

    public static Index of(List<Path> sourceRoots) throws IOException {
        return of(sourceRoots, List.of());
    }

    /// The project as it stands: its sources compiled against its dependencies,
    /// and those dependencies available to be asked about themselves.
    public static Index of(List<Path> sourceRoots, List<Path> vendorRoots) throws IOException {
        var vendor = Vendor.of(vendorRoots);
        return new Index(sourceRoots, vendor, Sources.compile(sourceRoots, vendor.classpath()));
    }

    /// Looks up a type by name. `a.b.Outer.Inner` also matches the nested type
    /// `a.b.Outer$Inner`, because that is how people write it.
    public Optional<TypeInfo> lookup(String name) {
        return find(name, this::compiled)
                .or(() -> find(name, this::vendored))
                .or(() -> find(name, Index::platform));
    }

    /// Every type name known from source, in compilation order. Vendored and
    /// JDK types are found on demand rather than enumerated.
    public List<String> names() {
        return List.copyOf(classes.keySet());
    }

    private Optional<TypeInfo> find(String name, Function<String, Optional<Origin>> where) {
        for (var candidate : candidates(name)) {
            var found = where.apply(candidate);
            if (found.isEmpty()) continue;
            return found.map(origin -> document(Classes.inspect(origin.classFile()), candidate, origin.source()));
        }
        return Optional.empty();
    }

    private Optional<Origin> compiled(String name) {
        var bytes = classes.get(name);
        if (bytes == null) return Optional.empty();
        var source = roots.stream()
                .map(root -> root.resolve(sourceFile(name)))
                .filter(Files::isRegularFile)
                .findFirst()
                .map(Index::text);
        return Optional.of(new Origin(bytes, source));
    }

    private Optional<Origin> vendored(String name) {
        return vendor.lookup(name, sourceFile(name));
    }

    /// `/modules/java.base/java/lang/String.class` names the module, and the
    /// module names the directory inside `src.zip`.
    private static Optional<Origin> platform(String name) {
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
