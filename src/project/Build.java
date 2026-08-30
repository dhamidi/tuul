package project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
/// A project compiles against the jars in `vendor/` and nothing else. If
/// `src/module-info.java` exists, javac reads those jars from the module path.
/// Otherwise, javac reads them from the classpath. Entrypoints and tests stay
/// unnamed. Tuul does not enable preview features.
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
        var dependencies = Vendor.of(List.of(layout.vendor())).classpath();
        var descriptor = layout.src().resolve("module-info.java");
        var librarySources = sources(layout.libraries());
        if (Files.isRegularFile(descriptor)) librarySources.addFirst(descriptor);

        var libraries = javac(librarySources, layout.classes(), dependencies, Files.isRegularFile(descriptor));
        if (!libraries.ok()) return libraries;
        resources(layout.libraries(), layout.src(), layout.classes());

        var classes = libraries.classes();
        for (var entrypoint : layout.entrypoints()) {
            var directory = layout.src().resolve(entrypoint);
            var built = javac(sources(List.of(directory)), layout.entry(entrypoint),
                    with(dependencies, layout.classes()), false);
            if (!built.ok()) return built;
            resources(List.of(directory), directory, layout.entry(entrypoint));
            classes += built.classes();
        }
        return new Result(classes, List.of());
    }

    /// Tests are compiled on top of a built project, into a directory of their
    /// own, and run by their `run` class.
    public static Result compileTests(Layout layout) throws IOException {
        if (!Files.isDirectory(layout.test())) return new Result(0, List.of("no test/ in " + layout.root().toAbsolutePath()));
        var classpath = with(Vendor.of(List.of(layout.vendor())).classpath(), layout.classes());
        var built = javac(sources(List.of(layout.test())), layout.tests(), classpath, false);
        if (built.ok()) resources(List.of(layout.test()), layout.test(), layout.tests());
        return built;
    }

    private static Result javac(List<Path> sources, Path out, List<Path> classpath, boolean module) throws IOException {
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
            options.add(module ? "--module-path" : "-classpath");
            options.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        var task = compiler.getTask(null, files, problems, options, null, files.getJavaFileObjectsFromPaths(sources));
        if (!task.call()) return new Result(0, report(problems));
        return new Result(written(out), List.of());
    }

    /// Everything beside the code that is not code, copied where the code
    /// went.
    ///
    /// `javac -d` writes class files and nothing else, so a file sitting next
    /// to a class — an icon, a template, a spec — is not on the classpath when
    /// the program runs, and [Class#getResourceAsStream] answers null. That is
    /// a confusing failure: the file is plainly there in the source tree, and
    /// the only thing wrong is that nobody copied it.
    ///
    /// `from` is the directory the package structure is measured against, so a
    /// resource lands beside the class that expects to find it.
    private static void resources(List<Path> roots, Path from, Path out) throws IOException {
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                for (var file : tree.filter(Files::isRegularFile).sorted().toList()) {
                    if (file.toString().endsWith(".java")) continue;
                    var to = out.resolve(from.relativize(file).toString());
                    Files.createDirectories(to.getParent());
                    Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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
