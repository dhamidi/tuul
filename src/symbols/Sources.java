package symbols;

import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import compiler.ClassSink;
import compiler.Compiler;

/// Compiles a source tree with javac and keeps the class files in memory.
///
/// This is the parser: javac already knows how to read Java, resolve names and
/// erase generics, and it hands the answer over as class files — which
/// [Classes] then reads. Nothing is written to disk.
public final class Sources {

    private Sources() {}

    public static Map<String, byte[]> compile(List<Path> roots) throws IOException {
        return compile(roots, List.of(), Compiler.system());
    }

    /// Compiles against the vendored named modules. The source roots must each
    /// contain a module descriptor. There is no classpath fallback.
    public static Map<String, byte[]> compile(List<Path> roots, List<Path> dependencies) throws IOException {
        return compile(roots, dependencies, Compiler.system());
    }

    /// Compiles through `compiler`. The caller can control class output and
    /// diagnostics by supplying another [Compiler].
    public static Map<String, byte[]> compile(List<Path> roots, List<Path> dependencies, Compiler compiler)
            throws IOException {
        var sources = files(roots);
        if (sources.isEmpty()) return Map.of();
        var memory = new Memory();
        var result = compiler.compile(new Compiler.Request(
                sources, dependencies, moduleName(roots), Runtime.version().feature(), true,
                java.util.Optional.empty(), List.of(), java.util.Map.of()), memory);
        if (!result.ok()) throw new IOException("javac failed:\n" + report(result.problems()));
        return memory.classes();
    }

    /// Every Java file under one named source root, including its module
    /// descriptor. A source root without `module-info.java` is invalid.
    public static List<Path> files(List<Path> roots) throws IOException {
        var sources = new ArrayList<Path>();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            var descriptor = root.resolve("module-info.java");
            if (!Files.isRegularFile(descriptor)) {
                try (var java = Files.walk(root)) {
                    if (java.anyMatch(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".java"))) {
                        throw new IOException("source root has no module-info.java: " + root);
                    }
                }
            }
            try (var tree = Files.walk(root)) {
                tree.filter(Sources::java).forEach(sources::add);
            }
        }
        return sources;
    }

    /// Returns the one module declared by the source roots. A docs index is a
    /// module view, not a collection of unnamed classes.
    static String moduleName(List<Path> roots) throws IOException {
        var names = new ArrayList<String>();
        for (var root : roots) {
            var descriptor = root.resolve("module-info.java");
            if (!Files.isRegularFile(descriptor)) continue;
            var source = Files.readString(descriptor)
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("(?m)//.*$", "");
            var match = Pattern.compile("\\b(?:open\\s+)?module\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*\\{")
                    .matcher(source);
            if (!match.find()) throw new IOException("invalid module-info.java: " + descriptor);
            names.add(match.group(1));
        }
        if (names.isEmpty()) throw new IOException("no module-info.java in source roots");
        if (names.stream().distinct().count() != 1) {
            throw new IOException("multiple modules in one symbol index: " + names);
        }
        return names.getFirst();
    }

    /// Reads class files already produced by a matching build or docs cache.
    public static Map<String, byte[]> read(Path root) throws IOException {
        if (!Files.isDirectory(root)) return Map.of();
        var classes = new LinkedHashMap<String, byte[]>();
        try (var tree = Files.walk(root)) {
            for (var path : tree.filter(file -> file.toString().endsWith(".class")).sorted().toList()) {
                var relative = root.relativize(path).toString();
                var name = relative.substring(0, relative.length() - ".class".length())
                        .replace(path.getFileSystem().getSeparator(), ".");
                classes.put(name, Files.readAllBytes(path));
            }
        }
        return Map.copyOf(classes);
    }

    /// Reads the text at a source location the index recorded.
    ///
    /// Pass the `source` of a [TypeInfo] or a [Document]. A location is a
    /// file such as `src/a/B.java`, or an archive entry written as
    /// `archive!/entry`, such as `vendor/x/x-sources.jar!/a/B.java`. Empty
    /// when the file or entry cannot be read, and for a `jrt:/` location,
    /// which names a class file and has no source.
    public static java.util.Optional<String> text(String location) {
        if (location.isEmpty() || location.startsWith("jrt:")) return java.util.Optional.empty();
        try {
            var bang = location.indexOf("!/");
            if (bang < 0) {
                var file = Path.of(location);
                return Files.isRegularFile(file) ? java.util.Optional.of(Files.readString(file)) : java.util.Optional.empty();
            }
            return Archives.of(Path.of(location.substring(0, bang)))
                    .map(archive -> archive.getPath("/" + location.substring(bang + 2)))
                    .filter(Files::isRegularFile)
                    .map(entry -> {
                        try {
                            return Files.readString(entry);
                        } catch (IOException unreadable) {
                            return null;
                        }
                    });
        } catch (IOException | RuntimeException unreadable) {
            return java.util.Optional.empty();
        }
    }

    /// Whether this file is Java source, including `module-info.java`.
    private static boolean java(Path path) {
        return path.getFileName().toString().endsWith(".java");
    }

    private static String report(List<Compiler.Problem> problems) {
        return problems.stream()
                .map(problem -> problem.source() == null
                        ? problem.message()
                        : problem.source() + ":" + problem.line() + " " + problem.message())
                .limit(10)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /// A file manager that hands javac an [OutputStream] per class instead of a
    /// file.
    private static final class Memory implements ClassSink {

        private final Map<String, ByteArrayOutputStream> written = new LinkedHashMap<>();

        @Override
        public OutputStream open(String name) {
            var bytes = new ByteArrayOutputStream();
            written.put(name, bytes);
            return bytes;
        }

        private Map<String, byte[]> classes() {
            var classes = new LinkedHashMap<String, byte[]>();
            written.forEach((name, bytes) -> classes.put(name, bytes.toByteArray()));
            return classes;
        }
    }
}
