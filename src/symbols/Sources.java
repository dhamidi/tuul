package symbols;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

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
        return compile(roots, List.of());
    }

    /// Compiles against the vendored jars. Javac reads them from the module path
    /// when the source tree has `module-info.java`. Otherwise, javac reads them
    /// from the classpath.
    public static Map<String, byte[]> compile(List<Path> roots, List<Path> dependencies) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("no javac in this runtime — run tuul on a JDK, not a JRE");

        var sources = find(roots);
        if (sources.isEmpty()) return Map.of();

        var problems = new DiagnosticCollector<JavaFileObject>();
        var files = compiler.getStandardFileManager(problems, null, StandardCharsets.UTF_8);
        var memory = new Memory(files);
        var module = sources.stream().anyMatch(path -> path.getFileName().toString().equals("module-info.java"));
        var task = compiler.getTask(null, memory, problems, options(dependencies, module), null,
                files.getJavaFileObjectsFromPaths(sources));
        if (!task.call()) throw new IOException("javac failed:\n" + report(problems));
        return memory.classes();
    }

    private static List<String> options(List<Path> classpath, boolean module) {
        var options = new ArrayList<>(List.of("-proc:none", "-parameters", "-g:none", "-nowarn",
                "--release", String.valueOf(Runtime.version().feature())));
        if (classpath.isEmpty()) return options;
        options.add(module ? "--module-path" : "-classpath");
        options.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        return options;
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
    private static List<Path> find(List<Path> roots) throws IOException {
        var sources = new ArrayList<Path>();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                tree.filter(Sources::documentable).forEach(sources::add);
            }
        }
        return sources;
    }

    /// Whether this file holds symbols worth compiling: Java, and not an
    /// entrypoint.
    private static boolean documentable(Path path) {
        var file = path.getFileName().toString();
        return file.endsWith(".java") && !file.equals(ENTRYPOINT);
    }

    private static String report(DiagnosticCollector<JavaFileObject> problems) {
        return problems.getDiagnostics().stream()
                .filter(problem -> problem.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                .map(problem -> problem.getSource() == null
                        ? problem.getMessage(null)
                        : problem.getSource().getName() + ":" + problem.getLineNumber() + " " + problem.getMessage(null))
                .limit(10)
                .collect(Collectors.joining("\n"));
    }

    /// A file manager that hands javac an [OutputStream] per class instead of a
    /// file.
    private static final class Memory extends ForwardingJavaFileManager<JavaFileManager> {

        private final Map<String, Compiled> written = new LinkedHashMap<>();

        private Memory(JavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind, FileObject sibling) {
            return written.computeIfAbsent(name, Compiled::new);
        }

        private Map<String, byte[]> classes() {
            var classes = new LinkedHashMap<String, byte[]>();
            written.forEach((name, file) -> classes.put(name, file.bytes()));
            return classes;
        }
    }

    private static final class Compiled extends SimpleJavaFileObject {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private Compiled(String name) {
            super(URI.create("memory:///" + name.replace('.', '/') + ".class"), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() {
            return bytes;
        }

        private byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
