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
import compiler.ClassSink;
import compiler.Compiler;

/// Compiles a source tree with javac and keeps the class files in memory.
///
/// This is the parser: javac already knows how to read Java, resolve names and
/// erase generics, and it hands the answer over as class files — which
/// [Classes] then reads. Nothing is written to disk.
public final class Sources {

    /// The file that makes a directory under `src/` an entrypoint. The name is
    /// repeated here rather than read from `project.Layout`, because `symbols`
    /// does not depend on `project`.
    public static final String ENTRYPOINT = "main.java";

    private Sources() {}

    public static Map<String, byte[]> compile(List<Path> roots) throws IOException {
        return compile(roots, List.of(), Compiler.system());
    }

    /// Compiles against the vendored jars. Javac reads them from the module path
    /// when the source tree has `module-info.java`. Otherwise, javac reads them
    /// from the classpath.
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
        var module = sources.stream().anyMatch(path -> path.getFileName().toString().equals("module-info.java"));
        var result = compiler.compile(new Compiler.Request(
                sources, dependencies, module, Runtime.version().feature(), true), memory);
        if (!result.ok()) throw new IOException("javac failed:\n" + report(result.problems()));
        return memory.classes();
    }

    /// Every Java file under the roots, except the entrypoints.
    ///
    /// A tuul project marks an entrypoint with `main.java`, and that file holds
    /// an implicitly declared class. Java names such a class after its file, so
    /// every `main.java` in a project compiles to the same class `main`. A
    /// project with two entrypoints — a command and a server, which is an
    /// ordinary shape — therefore made javac stop with `duplicate class: main`.
    /// One broken compile made **every** question fail, including questions
    /// about the JDK, because the index compiles the project before it answers
    /// anything.
    ///
    /// An entrypoint holds no symbol worth documenting: it is one method that
    /// reads arguments and calls a library. So the index leaves it out, and a
    /// project may have as many entrypoints as it needs.
    public static List<Path> files(List<Path> roots) throws IOException {
        var sources = new ArrayList<Path>();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                tree.filter(Sources::documentable).forEach(sources::add);
            }
        }
        return sources;
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
    /// A location is one of three shapes, and this reads all of them: a file
    /// such as `src/a/B.java`, an entry in an archive such as
    /// `vendor/x/x-sources.jar!/a/B.java` or `.../lib/src.zip!/java.base/java/lang/String.java`,
    /// and `jrt:/...`, which names a class file and holds no source at all.
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

    /// Whether this file holds symbols worth compiling: Java, and not an
    /// entrypoint.
    private static boolean documentable(Path path) {
        var file = path.getFileName().toString();
        return file.endsWith(".java") && !file.equals(ENTRYPOINT);
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
