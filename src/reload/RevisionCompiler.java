package reload;

import compiler.ClassSink;
import compiler.Compiler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Compiles a path-backed [Revision] into a disposable program when the host activates it.
///
/// Pass [#compile] to [RevisionSource#map(java.util.function.UnaryOperator)]
/// to compile revisions before submission. Compilation runs when the returned
/// program is defined by [Reload]. Each definition gets a new child loader
/// backed by an in-memory class and resource snapshot. The parent owns Tuul
/// and the contracts shared with the running process.
public final class RevisionCompiler {

    private final Compiler compiler;
    private final List<Path> parentClasspath;

    /// Creates an in-process JDK compiler with the supplied parent class path.
    ///
    /// An empty or null path supplies no host classes to javac. Candidate class
    /// and resource bytes remain in memory until their generation retires.
    public RevisionCompiler(List<Path> parentClasspath) {
        this(Compiler.system(), parentClasspath);
    }

    /// Creates a compiler with an injectable compiler and parent class path.
    ///
    /// Pass a test double instead of [Compiler#system()] to control diagnostics.
    /// An empty or null path supplies no host classes to the compiler.
    public RevisionCompiler(Compiler compiler, List<Path> parentClasspath) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.parentClasspath = parentClasspath == null ? List.of()
                : parentClasspath.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    /// Attaches a lazy in-memory program to a path-backed revision.
    ///
    /// The returned revision compiles when [Reload#submit(Revision)] defines
    /// its program. A compiler diagnostic rejects that candidate. This method
    /// writes no class or resource output. The revision source still owns its
    /// input tree. A revision that already has a program returns unchanged.
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
            CandidateLoader loader = null;
            try {
                var classes = new HashMap<String, byte[]>();
                compileSources(classes);
                var resources = resources();
                loader = loader(classes, resources, revision.dependencies());
                var type = Class.forName("main", true, loader);
                var value = type.getDeclaredConstructor().newInstance();
                if (!(value instanceof Program program)) {
                    throw new CompilationFailure("main must implement reload.Program");
                }
                var defined = Objects.requireNonNull(program.define(), "main.define returned null");
                var ownedLoader = loader;
                loader = null;
                return defined.closing(ownedLoader);
            } catch (Throwable failure) {
                closeOwned(loader);
                if (failure instanceof Exception exception) throw exception;
                if (failure instanceof Error error) throw error;
                throw new Exception(failure);
            }
        }

        private void compileSources(Map<String, byte[]> classes) throws Exception {
            var sources = revision.sources();
            if (sources.isEmpty()) throw new CompilationFailure("revision has no source files");
            var root = revision.root();
            for (var source : sources) inside(root, source, "source");
            // A revision can carry module-info.java for project metadata. The
            // reload generation itself is unnamed, so compile the application
            // sources only and never define the module descriptor.
            sources = sources.stream()
                    .filter(path -> !path.getFileName().toString().equals("module-info.java")).toList();
            if (sources.isEmpty()) throw new CompilationFailure("revision has no compilable source files");
            var classpath = new ArrayList<Path>(parentClasspath);
            classpath.addAll(revision.dependencies());
            var result = compiler.compile(new Compiler.Request(sources, classpath, false,
                    Runtime.version().feature(), true), sink(classes));
            if (!result.ok()) throw new CompilationFailure(result.problems());
        }

        private Map<String, byte[]> resources() throws Exception {
            var snapshot = new HashMap<String, byte[]>();
            for (var resource : revision.resourceEntries()) {
                inside(revision.root(), resource.path(), "resource");
                if (!Files.isRegularFile(resource.path())) throw new CompilationFailure(
                        "resource does not exist: " + resource.path());
                snapshot.put(resource.name(), Files.readAllBytes(resource.path()));
            }
            return Map.copyOf(snapshot);
        }

        private ClassSink sink(Map<String, byte[]> classes) {
            return binaryName -> {
                var output = new ByteArrayOutputStream();
                return new OutputStream() {
                    @Override
                    public void write(int value) { output.write(value); }

                    @Override
                    public void write(byte[] bytes, int offset, int length) {
                        output.write(bytes, offset, length);
                    }

                    @Override
                    public void close() {
                        classes.put(binaryName, output.toByteArray());
                    }
                };
            };
        }

        private CandidateLoader loader(Map<String, byte[]> classes, Map<String, byte[]> resources,
                List<Path> dependencies) throws Exception {
            var urls = new ArrayList<URL>();
            for (var dependency : dependencies) {
                if (!Files.exists(dependency)) throw new CompilationFailure("dependency does not exist: " + dependency);
                urls.add(dependency.toUri().toURL());
            }
            return new CandidateLoader(classes, resources, urls.toArray(URL[]::new),
                    RevisionCompiler.class.getClassLoader());
        }
    }

    private static void inside(Path root, Path path, String kind) throws Exception {
        if (path == null || !path.toAbsolutePath().normalize().startsWith(root)) {
            throw new CompilationFailure(kind + " is outside revision root: " + path);
        }
        if (!Files.isRegularFile(path)) throw new CompilationFailure(kind + " does not exist: " + path);
    }

    private static void closeOwned(CandidateLoader loader) throws Exception {
        if (loader != null) loader.close();
    }

    /// Loads the selected default-package entrypoint from the candidate while
    /// delegating shared contracts to the host class loader.
    private static final class CandidateLoader extends URLClassLoader {
        private final Map<String, byte[]> classes;
        private final Map<String, byte[]> resources;

        private CandidateLoader(Map<String, byte[]> classes, Map<String, byte[]> resources,
                URL[] urls, ClassLoader parent) {
            super(urls, parent);
            this.classes = Map.copyOf(classes);
            this.resources = resources;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!classes.containsKey(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            var bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        public URL getResource(String name) {
            var candidate = memoryResource(name);
            return candidate == null ? super.getResource(name) : candidate;
        }

        @Override
        public java.util.Enumeration<URL> getResources(String name) throws IOException {
            var found = new ArrayList<URL>();
            var candidate = memoryResource(name);
            if (candidate != null) found.add(candidate);
            if (getParent() != null) getParent().getResources(name).asIterator().forEachRemaining(found::add);
            super.findResources(name).asIterator().forEachRemaining(found::add);
            return java.util.Collections.enumeration(found);
        }

        @Override
        public URL findResource(String name) {
            var candidate = memoryResource(name);
            return candidate == null ? super.findResource(name) : candidate;
        }

        private URL memoryResource(String name) {
            if (!resources.containsKey(name)) return null;
            try {
                return URL.of(new URI("memory", null, "/" + name, null),
                        new MemoryUrlHandler(resources.get(name)));
            } catch (java.net.URISyntaxException | java.net.MalformedURLException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    private static final class MemoryUrlHandler extends URLStreamHandler {
        private final byte[] bytes;

        private MemoryUrlHandler(byte[] bytes) { this.bytes = bytes; }

        @Override
        protected URLConnection openConnection(URL url) {
            return new URLConnection(url) {
                @Override
                public void connect() {}

                @Override
                public java.io.InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes);
                }

                @Override
                public long getContentLengthLong() { return bytes.length; }
            };
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
