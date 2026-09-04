package compiler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/// The [Compiler] supplied by the running JDK.
final class Javac implements Compiler {

    static final Javac INSTANCE = new Javac();

    private Javac() {}

    @Override
    public Result compile(Request request, ClassSink classes) throws IOException {
        if (request.sources().isEmpty()) return new Result(0, java.util.List.of());
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("no javac in this runtime — run tuul on a JDK, not a JRE");

        var problems = new DiagnosticCollector<JavaFileObject>();
        var written = new AtomicInteger();
        var outputDirectory = Files.createTempDirectory("tuul-javac-output-");
        try (var files = compiler.getStandardFileManager(problems, null, StandardCharsets.UTF_8);
                var output = new Output(files, classes, written, request.moduleSources())) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputDirectory));
            var task = compiler.getTask(null, output, problems, options(request), null,
                    files.getJavaFileObjectsFromPaths(request.sources()));
            if (task.call()) return new Result(written.get(), java.util.List.of());
        } finally {
            delete(outputDirectory);
        }
        return new Result(written.get(), problems.getDiagnostics().stream()
                .filter(problem -> problem.getKind() == Diagnostic.Kind.ERROR)
                .map(Javac::problem)
                .limit(20)
                .toList());
    }

    private static java.util.List<String> options(Request request) {
        var options = new ArrayList<>(java.util.List.of(
                "-proc:none", "-parameters", "-nowarn", "--release", String.valueOf(request.release())));
        if (!request.debug()) options.add("-g:none");
        if (!request.modulePath().isEmpty()) {
            options.add("--module-path");
            options.add(String.join(java.io.File.pathSeparator,
                    request.modulePath().stream().map(Path::toString).toList()));
        }
        request.patch().ifPresent(patch -> {
            options.add("--patch-module");
            options.add(patch.module() + "=" + patch.sourceRoot());
        });
        for (var export : request.addExports()) {
            options.add("--add-exports");
            options.add(export);
        }
        if (!request.moduleSources().isEmpty()) {
            for (var entry : request.moduleSources().entrySet()) {
                options.add("--module-source-path");
                options.add(entry.getKey() + "=" + entry.getValue());
            }
            options.add("--module");
            options.add(String.join(",", request.moduleSources().keySet()));
        }
        return options;
    }

    private static Problem problem(Diagnostic<? extends JavaFileObject> diagnostic) {
        Path source = null;
        if (diagnostic.getSource() != null) {
            try {
                source = Path.of(diagnostic.getSource().toUri());
            } catch (RuntimeException invalid) {
                source = Path.of(diagnostic.getSource().getName());
            }
        }
        return new Problem(source, diagnostic.getLineNumber(), diagnostic.getMessage(null));
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        }
    }

    private static final class Output extends ForwardingJavaFileManager<JavaFileManager> {


        private final ClassSink classes;
        private final AtomicInteger written;
        private final java.util.Map<String, Path> moduleSources;

        private Output(JavaFileManager delegate, ClassSink classes, AtomicInteger written,
                java.util.Map<String, Path> moduleSources) {
            super(delegate);
            this.classes = classes;
            this.written = written;
            this.moduleSources = moduleSources;
        }

        @Override
        public Location getLocationForModule(Location location, String moduleName) throws IOException {
            if (location == StandardLocation.CLASS_OUTPUT && moduleSources.containsKey(moduleName)) {
                return new ModuleLocation(moduleName);
            }
            return super.getLocationForModule(location, moduleName);
        }

        @Override
        public String inferModuleName(Location location) throws IOException {
            if (location instanceof ModuleLocation module) return module.name();
            return super.inferModuleName(location);
        }

        @Override
        public boolean hasLocation(Location location) {
            return location instanceof ModuleLocation || super.hasLocation(location);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind,
                FileObject sibling) {
            String module = null;
            try { module = inferModuleName(location); }
            catch (IOException ignored) {}
            if (module == null && name.indexOf('/') > 0) {
                module = name.substring(0, name.indexOf('/'));
                name = name.substring(name.indexOf('/') + 1);
            }
            if (module == null && sibling != null && !moduleSources.isEmpty()) {
                try {
                    var source = Path.of(sibling.toUri()).toAbsolutePath().normalize();
                    module = moduleSources.entrySet().stream()
                            .filter(entry -> source.startsWith(entry.getValue().toAbsolutePath().normalize()))
                            .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
                } catch (RuntimeException ignored) {}
            }
            return new Compiled(module, name, classes, written);
        }

        @Override
        public JavaFileObject getJavaFileForOutputForOriginatingFiles(Location location, String name,
                JavaFileObject.Kind kind, FileObject... originatingFiles) throws IOException {
            var sibling = originatingFiles.length == 0 ? null : originatingFiles[0];
            return getJavaFileForOutput(location, name, kind, sibling);
        }

        private record ModuleLocation(String name) implements Location {
            @Override public boolean isOutputLocation() { return true; }
            @Override public String getName() { return "CLASS_OUTPUT[" + name + "]"; }
        }
    }

    private static final class Compiled extends SimpleJavaFileObject {

        private final String module;
        private final String name;
        private final ClassSink classes;
        private final AtomicInteger written;

        private Compiled(String module, String name, ClassSink classes, AtomicInteger written) {
            super(URI.create("memory:///" + name.replace('.', '/') + ".class"), Kind.CLASS);
            this.module = module;
            this.name = name;
            this.classes = classes;
            this.written = written;
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            written.incrementAndGet();
            return module == null ? classes.open(name) : classes.open(module, name);
        }
    }
}
