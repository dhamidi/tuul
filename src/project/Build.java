package project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import symbols.Vendor;

/// Compiles a project onto disk: the libraries together, then each entrypoint
/// on top of them, one output directory apart.
///
/// A project is compiled against the jars in `vendor/` and nothing else. No
/// preview features: tuul itself needs them, a project should not have to.
public final class Build {

    /// What a compile did, or what stopped it. Problems are javac's own words.
    public record Result(int classes, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private Build() {}

    public static Result compile(Layout layout) throws IOException {
        if (!layout.exists()) return new Result(0, List.of("no src/ in " + layout.root().toAbsolutePath()));
        var classpath = Vendor.of(List.of(layout.vendor())).classpath();

        var libraries = javac(sources(layout.libraries()), layout.classes(), classpath);
        if (!libraries.ok()) return libraries;

        var classes = libraries.classes();
        for (var entrypoint : layout.entrypoints()) {
            var built = javac(
                    sources(List.of(layout.src().resolve(entrypoint))),
                    layout.entry(entrypoint),
                    with(classpath, layout.classes()));
            if (!built.ok()) return built;
            classes += built.classes();
        }
        return new Result(classes, List.of());
    }

    /// Tests are compiled on top of a built project, into a directory of their
    /// own, and run by their `run` class.
    public static Result compileTests(Layout layout) throws IOException {
        if (!Files.isDirectory(layout.test())) return new Result(0, List.of("no test/ in " + layout.root().toAbsolutePath()));
        var classpath = with(Vendor.of(List.of(layout.vendor())).classpath(), layout.classes());
        return javac(sources(List.of(layout.test())), layout.tests(), classpath);
    }

    private static Result javac(List<Path> sources, Path out, List<Path> classpath) throws IOException {
        if (sources.isEmpty()) return new Result(0, List.of());
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("no javac in this runtime — run tuul on a JDK, not a JRE");
        Files.createDirectories(out);

        var problems = new DiagnosticCollector<JavaFileObject>();
        var files = compiler.getStandardFileManager(problems, null, StandardCharsets.UTF_8);
        var options = new ArrayList<>(List.of(
                "-proc:none", "-parameters", "-nowarn",
                "-d", out.toString(),
                "--release", String.valueOf(Runtime.version().feature())));
        if (!classpath.isEmpty()) {
            options.add("-classpath");
            options.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        var task = compiler.getTask(null, files, problems, options, null, files.getJavaFileObjectsFromPaths(sources));
        if (!task.call()) return new Result(0, report(problems));
        return new Result(written(out), List.of());
    }

    private static List<Path> sources(List<Path> roots) throws IOException {
        var sources = new ArrayList<Path>();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                tree.filter(path -> path.toString().endsWith(".java")).sorted().forEach(sources::add);
            }
        }
        return sources;
    }

    private static int written(Path out) throws IOException {
        try (var tree = Files.walk(out)) {
            return (int) tree.filter(path -> path.toString().endsWith(".class")).count();
        }
    }

    private static List<String> report(DiagnosticCollector<JavaFileObject> problems) {
        return problems.getDiagnostics().stream()
                .filter(problem -> problem.getKind() == Diagnostic.Kind.ERROR)
                .map(Build::describe)
                .limit(20)
                .toList();
    }

    private static String describe(Diagnostic<? extends JavaFileObject> problem) {
        var where = problem.getSource() == null
                ? ""
                : Path.of(problem.getSource().getName()).getFileName() + ":" + problem.getLineNumber() + " ";
        return where + problem.getMessage(null);
    }

    private static List<Path> with(List<Path> classpath, Path extra) {
        var all = new ArrayList<>(classpath);
        all.add(extra);
        return all;
    }
}
