package reload;

import compiler.ClassSink;
import compiler.Compiler;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/// Compiles a path-backed [Revision] into a disposable program.
///
/// The method reference `compiler::compile` is suitable for
/// [RevisionSource#map(java.util.function.UnaryOperator)]. Compilation is
/// performed when the returned program is defined by [Reload], so compiler
/// failures follow the normal candidate rejection path. Each definition gets
/// a new output directory and a new child loader. The parent owns Tuul and the
/// contracts shared with the running process.
public final class RevisionCompiler {

    private final Path output;
    private final Compiler compiler;
    private final List<Path> parentClasspath;

    /// Creates a compiler using the JDK compiler and the supplied parent
    /// contract class path. The path is never modified or deleted.
    public RevisionCompiler(Path output, List<Path> parentClasspath) {
        this(output, Compiler.system(), parentClasspath);
    }

    /// Creates a compiler with an injectable Java compiler.
    public RevisionCompiler(Path output, Compiler compiler, List<Path> parentClasspath) {
        this.output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.parentClasspath = parentClasspath == null ? List.of()
                : parentClasspath.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    /// Attaches a lazily defined, isolated program while preserving the
    /// revision identity and source metadata. The source tree remains owned by
    /// its source; candidate class output is owned by the resulting generation.
    public Revision compile(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        if (revision.program() != null) return revision;
        return revision.withProgram(new LoadedProgram(revision));
    }

    private final class LoadedProgram implements Program {
        private final Revision revision;

        private LoadedProgram(Revision revision) {
            this.revision = revision;
        }

        @Override
        public Generation define() throws Exception {
            if (revision.root() == null) throw new CompilationFailure("revision has no source root");
            Path generation = null;
            CandidateLoader loader = null;
            try {
                Files.createDirectories(output);
                generation = Files.createTempDirectory(output, "generation-");
                var classes = generation.resolve("classes");
                compileSources(classes);
                copyResources(classes);
                loader = loader(classes, revision.dependencies());
                var type = Class.forName("main", true, loader);
                var value = type.getDeclaredConstructor().newInstance();
                if (!(value instanceof Program program)) {
                    throw new CompilationFailure("main must implement reload.Program");
                }
                var defined = Objects.requireNonNull(program.define(), "main.define returned null");
                var ownedLoader = loader;
                var ownedGeneration = generation;
                loader = null;
                generation = null;
                return defined.closing(() -> closeOwned(ownedLoader, ownedGeneration));
            } catch (Throwable failure) {
                closeOwned(loader, generation);
                if (failure instanceof Exception exception) throw exception;
                if (failure instanceof Error error) throw error;
                throw new Exception(failure);
            }
        }

        private void compileSources(Path classes) throws Exception {
            var sources = revision.sources();
            if (sources.isEmpty()) throw new CompilationFailure("revision has no source files");
            var root = revision.root();
            for (var source : sources) inside(root, source, "source");
            if (sources.stream().anyMatch(path -> path.getFileName().toString().equals("module-info.java"))) {
                throw new CompilationFailure("named revisions are not supported by RevisionCompiler");
            }
            Files.createDirectories(classes);
            var classpath = new ArrayList<Path>(parentClasspath);
            classpath.addAll(revision.dependencies());
            var result = compiler.compile(new Compiler.Request(sources, classpath, false,
                    Runtime.version().feature(), true), sink(classes));
            if (!result.ok()) throw new CompilationFailure(result.problems());
        }

        private void copyResources(Path classes) throws Exception {
            var root = revision.root();
            for (var resource : revision.resources()) {
                inside(root, resource, "resource");
                if (!Files.isRegularFile(resource)) throw new CompilationFailure(
                        "resource does not exist: " + resource);
                var destination = classes.resolve(root.relativize(resource).toString()).normalize();
                if (!destination.startsWith(classes)) throw new CompilationFailure("resource is outside output");
                Files.createDirectories(destination.getParent());
                Files.copy(resource, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private ClassSink sink(Path classes) {
            return binaryName -> {
                var destination = classes.resolve(binaryName.replace('.', '/') + ".class").normalize();
                if (!destination.startsWith(classes)) throw new IOException("compiler output is outside candidate");
                Files.createDirectories(destination.getParent());
                return Files.newOutputStream(destination);
            };
        }

        private CandidateLoader loader(Path classes, List<Path> dependencies) throws Exception {
            var urls = new ArrayList<URL>();
            urls.add(classes.toUri().toURL());
            for (var dependency : dependencies) {
                if (!Files.exists(dependency)) throw new CompilationFailure("dependency does not exist: " + dependency);
                urls.add(dependency.toUri().toURL());
            }
            return new CandidateLoader(urls.toArray(URL[]::new), RevisionCompiler.class.getClassLoader());
        }
    }

    private static void inside(Path root, Path path, String kind) throws Exception {
        if (path == null || !path.toAbsolutePath().normalize().startsWith(root)) {
            throw new CompilationFailure(kind + " is outside revision root: " + path);
        }
        if (!Files.isRegularFile(path)) throw new CompilationFailure(kind + " does not exist: " + path);
    }

    private static void closeOwned(CandidateLoader loader, Path generation) throws Exception {
        Exception failure = null;
        if (loader != null) {
            try { loader.close(); } catch (Exception thrown) { failure = thrown; }
        }
        try { remove(generation); } catch (Exception thrown) {
            if (failure == null) failure = thrown;
            else failure.addSuppressed(thrown);
        }
        if (failure != null) throw failure;
    }

    private static void remove(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        try (var files = Files.walk(path)) {
            var failure = new IOException[1];
            files.sorted(Comparator.reverseOrder()).forEach(file -> {
                try { Files.deleteIfExists(file); }
                catch (IOException thrown) {
                    if (failure[0] == null) failure[0] = thrown;
                    else failure[0].addSuppressed(thrown);
                }
            });
            if (failure[0] != null) throw failure[0];
        }
    }

    /// Loads the selected default-package entrypoint from the candidate while
    /// delegating shared contracts to the host class loader.
    private static final class CandidateLoader extends URLClassLoader {
        private CandidateLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals("main")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    /// A compiler diagnostic which keeps all javac problems together for the
    /// coordinator's normal candidate failure report.
    public static final class CompilationFailure extends Exception {
        private final List<Compiler.Problem> problems;

        private CompilationFailure(String message) {
            super(message);
            problems = List.of();
        }

        private CompilationFailure(List<Compiler.Problem> problems) {
            super(format(problems));
            this.problems = List.copyOf(problems);
        }

        /// Returns javac diagnostics in compiler order. A non-javac failure has
        /// an empty list and uses the exception message.
        public List<Compiler.Problem> problems() { return problems; }

        private static String format(List<Compiler.Problem> problems) {
            return problems.isEmpty() ? "compilation failed"
                    : problems.stream().map(problem -> (problem.source() == null ? "" : problem.source() + ":")
                            + problem.line() + " " + problem.message()).reduce((one, two) -> one + "; " + two).orElse("compilation failed");
        }
    }
}
