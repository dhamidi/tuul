package compiler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
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
        try (var files = compiler.getStandardFileManager(problems, null, StandardCharsets.UTF_8);
                var output = new Output(files, classes, written)) {
            var task = compiler.getTask(null, output, problems, options(request), null,
                    files.getJavaFileObjectsFromPaths(request.sources()));
            if (task.call()) return new Result(written.get(), java.util.List.of());
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
        if (request.classpath().isEmpty()) return options;
        options.add(request.module() ? "--module-path" : "-classpath");
        options.add(request.classpath().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
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

    private static final class Output extends ForwardingJavaFileManager<JavaFileManager> {

        private final ClassSink classes;
        private final AtomicInteger written;

        private Output(JavaFileManager delegate, ClassSink classes, AtomicInteger written) {
            super(delegate);
            this.classes = classes;
            this.written = written;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind,
                FileObject sibling) {
            return new Compiled(name, classes, written);
        }
    }

    private static final class Compiled extends SimpleJavaFileObject {

        private final String name;
        private final ClassSink classes;
        private final AtomicInteger written;

        private Compiled(String name, ClassSink classes, AtomicInteger written) {
            super(URI.create("memory:///" + name.replace('.', '/') + ".class"), Kind.CLASS);
            this.name = name;
            this.classes = classes;
            this.written = written;
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            written.incrementAndGet();
            return classes.open(name);
        }
    }
}
