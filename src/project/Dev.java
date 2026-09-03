package project;

import compiler.Compiler;
import reload.Generation;
import reload.Program;
import reload.Reload;
import reload.Revision;
import reload.RevisionSource;
import symbols.Vendor;
import web.serve.Http;

import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleReader;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/// Runs a reloadable project during development.
///
/// Compilation and directory observation live at this edge of the reload
/// system. [Reload] owns generation boundaries, leases, and the last-good
/// generation. Consequently a compiler error never changes the HTTP server or
/// the code that is currently answering requests.
public final class Dev {

    /// The delay after the last filesystem event before a revision is built.
    public static final Duration QUIET_PERIOD = Duration.ofMillis(150);

    private Dev() {}

    /// Compiles and hosts one project until the calling thread is interrupted.
    /// The listening socket is opened once; subsequent revisions replace the
    /// handler behind it. The method returns non-zero when the first revision
    /// cannot be compiled or defined.
    public static int run(Layout layout, String entrypoint, int port, Writer out, Writer err) throws IOException {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("port must be between 0 and 65535");
        var selected = entrypoint(layout, entrypoint == null ? "" : entrypoint);
        if (selected.isEmpty()) {
            write(err, "error: no entrypoint; create src/<name>/main.java\n");
            return 1;
        }

        var reload = new Reload();
        var builder = new Builder(layout, selected, err);
        var first = builder.build();
        if (first == null) {
            reload.close();
            return 1;
        }
        var status = reload.submit(first.revision());
        if (!status.active()) {
            printProblems(err, status.problems());
            reload.close();
            return 1;
        }

        var source = new DirectorySource(layout, selected, builder, QUIET_PERIOD, err, false);
        try (var server = Http.start(reload.handler(), port, failure -> write(err, "web: " + failure + "\n"))) {
            write(out, "development server at http://localhost:" + server.port() + "\n");
            write(out, "active generation " + status.activeRevision() + "\n");
            write(out, "watching " + layout.src().toAbsolutePath().normalize() + "\n");
            out.flush();
            reload.source(source);
            reload.subscribe(event -> {
                if (!event.kind().equals("rejected")) return;
                write(err, "reload rejected " + event.revision() + "\n");
                printProblems(err, reload.status().problems());
            });
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return 0;
        } catch (Exception failed) {
            write(err, "error: " + (failed.getMessage() == null ? failed : failed.getMessage()) + "\n");
            return 1;
        } finally {
            reload.close();
            source.close();
        }
    }

    private static String entrypoint(Layout layout, String named) throws IOException {
        if (!named.isBlank()) return named;
        var available = layout.entrypoints();
        if (available.contains("web")) return "web";
        return available.size() == 1 ? available.getFirst() : "";
    }

    /// An injectable directory revision source. It submits complete compiled
    /// programs; it does not know how activation or HTTP routing works.
    public static final class DirectorySource implements RevisionSource {
        private final Layout layout;
        private final String entrypoint;
        private final Builder builder;
        private final Duration quiet;
        private final boolean submitInitial;
        private volatile WatchService watches;
        private volatile Thread thread;
        private volatile boolean closed;

        public DirectorySource(Layout layout, String entrypoint, Builder builder, Duration quiet, Writer errors) {
            this(layout, entrypoint, builder, quiet, errors, true);
        }

        /// Creates a source with the normal builder seam. The first stable
        /// scan is submitted before the source waits for later changes.
        public DirectorySource(Layout layout, String entrypoint, Duration quiet, Writer errors) {
            this(layout, entrypoint, new Builder(layout, entrypoint, errors), quiet, errors, true);
        }

        private DirectorySource(Layout layout, String entrypoint, Builder builder, Duration quiet, Writer errors,
                boolean submitInitial) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.entrypoint = Objects.requireNonNull(entrypoint, "entrypoint");
            this.builder = Objects.requireNonNull(builder, "builder");
            this.quiet = requireQuiet(quiet);
            Objects.requireNonNull(errors, "errors");
            this.submitInitial = submitInitial;
        }

        @Override
        public void start(Consumer<Revision> submit) throws Exception {
            Objects.requireNonNull(submit, "submit");
            if (thread != null) throw new IllegalStateException("directory source already started");
            try (var service = FileSystems.watch()) {
                watches = service;
                register(layout.src(), service);
                // A generation also depends on the vendored binary jars. A
                // dependency replacement is a revision even when no project
                // source changed, so keep that input tree in the same source
                // boundary. Missing vendor/ is fine for a fresh project.
                register(layout.vendor(), service);
                var runner = Thread.currentThread();
                thread = runner;
                if (submitInitial && !closed && !runner.isInterrupted()) {
                    var first = builder.build();
                    if (first != null) submit.accept(first.revision());
                }
                for (;;) {
                    if (closed || runner.isInterrupted()) return;
                    var key = service.take();
                    var changed = consume(key, service);
                    if (!changed) continue;
                    while (!stable(service, quiet)) {
                        if (closed || runner.isInterrupted()) return;
                    }
                    if (closed || runner.isInterrupted()) return;
                    var built = builder.build();
                    if (built != null) submit.accept(built.revision());
                }
            } finally {
                watches = null;
                thread = null;
            }
        }

        private boolean stable(WatchService service, Duration delay) throws IOException, InterruptedException {
            var deadline = System.nanoTime() + delay.toNanos();
            var changed = false;
            while (System.nanoTime() < deadline) {
                var key = service.poll(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
                if (key == null) break;
                changed |= consume(key, service);
            }
            return !changed;
        }

        private static boolean consume(WatchKey key, WatchService service) throws IOException {
            var changed = false;
            for (var event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    changed = true;
                    continue;
                }
                var name = ((Path) event.context()).getFileName().toString();
                if (temporary(name)) continue;
                changed = true;
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    var child = ((Path) key.watchable()).resolve((Path) event.context());
                    if (Files.isDirectory(child)) register(child, service);
                }
            }
            key.reset();
            return changed;
        }

        private static boolean temporary(String name) {
            return name.startsWith(".") || name.endsWith("~") || name.endsWith(".swp") || name.endsWith(".tmp");
        }

        private static void register(Path root, WatchService service) throws IOException {
            if (!Files.isDirectory(root)) return;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    directory.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        @Override
        public void close() {
            closed = true;
            var current = thread;
            if (current != null) current.interrupt();
            var service = watches;
            if (service != null) try { service.close(); } catch (IOException ignored) {}
        }

        private static Duration requireQuiet(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("quiet period must be positive");
            return value;
        }
    }

    /// Compiler seam used by tests and by [DirectorySource].
    public static final class Builder {
        private final Layout layout;
        private final String entrypoint;
        private final Writer errors;
        private final Compiler compiler;

        public Builder(Layout layout, String entrypoint, Writer errors) {
            this(layout, entrypoint, errors, Compiler.system());
        }

        public Builder(Layout layout, String entrypoint, Writer errors, Compiler compiler) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.entrypoint = Objects.requireNonNull(entrypoint, "entrypoint");
            this.errors = Objects.requireNonNull(errors, "errors");
            this.compiler = Objects.requireNonNull(compiler, "compiler");
        }

        Built build() throws IOException {
            var vendor = Vendor.of(List.of(layout.vendor()));
            var source = layout.src().resolve(entrypoint);
            if (!Files.isDirectory(source)) {
                write(errors, "compile: no entrypoint at " + source + "\n");
                return null;
            }
            var libraries = layout.libraries();
            var librarySources = sources(libraries);
            var descriptor = layout.src().resolve("module-info.java");
            if (Files.isRegularFile(descriptor)) librarySources.add(0, descriptor);
            var entrySources = sources(List.of(source));
            if (entrySources.stream().noneMatch(path -> path.getFileName().toString().equals("main.java"))) {
                write(errors, "compile: no main.java at " + source + "\n");
                return null;
            }
            var sourceFiles = new ArrayList<Path>(librarySources);
            sourceFiles.addAll(entrySources);
            var resources = resources(layout, libraries, source);
            Files.createDirectories(layout.root().resolve("build/reload"));
            var root = Files.createTempDirectory(layout.root().resolve("build/reload"), "generation-");
            var classes = root.resolve("classes");
            var entry = root.resolve("entry");
            try {
                var librariesResult = compile(librarySources, classes, vendor.runtime(), Files.isRegularFile(descriptor));
                if (!librariesResult.ok()) return fail(root, describe(librariesResult.problems()));
                copy(libraries, layout.src(), classes);
                copy(List.of(layout.resources()), layout.resources(), classes);
                var entryResult = compile(entrySources, entry, append(vendor.runtime(), classes), false);
                if (!entryResult.ok()) return fail(root, describe(entryResult.problems()));
                copy(List.of(source), source, entry);
                var identity = Revision.from(layout.root(), entrypoint, sourceFiles, resources, vendor.runtime()).identity();
                var loader = loader(classes, entry, vendor.runtime(), Files.isRegularFile(descriptor));
                return new Built(Revision.of(identity, new LoadedProgram(loader, root)), loader.entry(), root);
            } catch (Throwable failed) {
                remove(root);
                if (failed instanceof IOException io) throw io;
                throw new IOException("cannot build reload generation", failed);
            }
        }

        private Built fail(Path root, List<String> problems) throws IOException {
            for (var problem : problems) write(errors, "compile: " + problem + "\n");
            remove(root);
            return null;
        }

        private static List<String> describe(List<Compiler.Problem> problems) {
            return problems.stream().map(problem -> {
                var source = problem.source() == null ? "" : problem.source() + ":" + problem.line() + " ";
                return source + problem.message();
            }).toList();
        }

        private Compiler.Result compile(List<Path> sources, Path out, List<Path> classpath, boolean module)
                throws IOException {
            Files.createDirectories(out);
            return compiler.compile(new Compiler.Request(sources, classpath, module,
                    Runtime.version().feature(), true), name -> {
                var file = out.resolve(name.replace('.', '/') + ".class");
                Files.createDirectories(file.getParent());
                return Files.newOutputStream(file);
            });
        }

        private static List<Path> sources(List<Path> roots) throws IOException {
            var found = new ArrayList<Path>();
            for (var root : roots) {
                if (!Files.isDirectory(root)) continue;
                try (var files = Files.walk(root)) {
                    files.filter(path -> path.toString().endsWith(".java")).sorted().forEach(found::add);
                }
            }
            return found;
        }

        private static List<Path> resources(Layout layout, List<Path> libraries, Path entry) throws IOException {
            var found = new ArrayList<Path>();
            for (var root : libraries) collect(root, found);
            collect(layout.resources(), found);
            collect(entry, found);
            return found;
        }

        private static void collect(Path root, List<Path> into) throws IOException {
            if (!Files.isDirectory(root)) return;
            try (var files = Files.walk(root)) {
                files.filter(Files::isRegularFile).filter(path -> !path.toString().endsWith(".java"))
                        .forEach(into::add);
            }
        }

        private static void copy(List<Path> roots, Path from, Path to) throws IOException {
            for (var root : roots) {
                if (!Files.isDirectory(root)) continue;
                try (var files = Files.walk(root)) {
                    for (var file : files.filter(Files::isRegularFile)
                            .filter(path -> !path.toString().endsWith(".java")).toList()) {
                        var destination = to.resolve(from.relativize(file).toString());
                        Files.createDirectories(destination.getParent());
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        private static List<Path> append(List<Path> paths, Path path) {
            var result = new ArrayList<>(paths);
            result.add(path);
            return result;
        }

        /// Creates the candidate's class-loading boundary. A project without a
        /// module descriptor is an ordinary child URL loader. A named project
        /// gets a module layer for its library classes and a small child loader
        /// for the selected default-package `main` entrypoint. The latter is
        /// deliberately separate: a default-package entrypoint cannot belong
        /// to a named module, but it still must be replaced on every revision.
        private static GenerationLoader loader(Path classes, Path entry, List<Path> dependencies,
                boolean named) throws IOException {
            var parent = Dev.class.getClassLoader();
            if (!named) {
                var urls = new ArrayList<URL>();
                urls.add(classes.toUri().toURL());
                urls.add(entry.toUri().toURL());
                for (var dependency : dependencies) urls.add(dependency.toUri().toURL());
                var child = new ProjectClassLoader(urls.toArray(URL[]::new), parent);
                return new GenerationLoader(child, List.of(child), null);
            }

            var projectFinder = ModuleFinder.of(classes);
            var project = projectFinder.findAll().stream()
                    .findFirst()
                    .orElseThrow(() -> new IOException("named project has no module descriptor"));
            var module = project.descriptor().name();
            var finder = ModuleFinder.of(modulePaths(classes, dependencies));
            var framework = frameworkLayer(parent);
            var configuration = framework.configuration().resolve(ModuleFinder.of(), finder, Set.of(module));
            try {
                var controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(framework), parent);
                var layer = controller.layer();
                var namedModule = layer.findModule(module)
                        .orElseThrow(() -> new IOException("named project module did not load: " + module));
                // The host is normally on the class path. A module descriptor
                // can say `requires tuul` for compilation, but the actual
                // framework classes are owned by the host's unnamed module. Add
                // the edge so those classes retain one identity across
                // generations.
                controller.addReads(namedModule, Dev.class.getModule());
                var loadedModuleLoader = layer.findLoader(module);
                var child = new ProjectClassLoader(new URL[] { entry.toUri().toURL() }, loadedModuleLoader);
                return new GenerationLoader(child, List.of(child), layer);
            } catch (Throwable failure) {
                if (failure instanceof IOException io) throw io;
                throw new IOException("cannot load named project module", failure);
            }
        }

        private static Path[] modulePaths(Path classes, List<Path> dependencies) {
            var paths = new ArrayList<Path>();
            paths.add(classes);
            paths.addAll(dependencies);
            return paths.toArray(Path[]::new);
        }

        /// A small parent layer supplies the module name used by installed
        /// projects (`requires tuul`) without loading a second copy of Tuul.
        /// Its loader is the host loader, so every framework type still comes
        /// from the parent class path. The project's controller additionally
        /// reads the host unnamed module for the same reason.
        private static ModuleLayer frameworkLayer(ClassLoader parent) {
            if (Dev.class.getModule().isNamed() && Dev.class.getModule().getName().equals("tuul")) {
                return ModuleLayer.boot();
            }
            var descriptor = ModuleDescriptor.newModule("tuul").build();
            var reference = new ModuleReference(descriptor, URI.create("tuul:host")) {
                @Override
                public ModuleReader open() {
                    return new ModuleReader() {
                        @Override public Optional<java.net.URI> find(String name) { return Optional.empty(); }
                        @Override public java.util.stream.Stream<String> list() { return java.util.stream.Stream.empty(); }
                        @Override public Optional<java.nio.ByteBuffer> read(String name) { return Optional.empty(); }
                        @Override public Optional<java.io.InputStream> open(String name) { return Optional.empty(); }
                        @Override public void close() {}
                    };
                }
            };
            var finder = new ModuleFinder() {
                @Override public Optional<ModuleReference> find(String name) {
                    return name.equals("tuul") ? Optional.of(reference) : Optional.empty();
                }
                @Override public Set<ModuleReference> findAll() { return Set.of(reference); }
            };
            var configuration = ModuleLayer.boot().configuration()
                    .resolve(ModuleFinder.of(), finder, Set.of("tuul"));
            return ModuleLayer.defineModulesWithOneLoader(configuration, List.of(ModuleLayer.boot()), parent).layer();
        }
    }

    private record Built(Revision revision, URLClassLoader loader, Path output) {}

    private record GenerationLoader(ProjectClassLoader entry, List<URLClassLoader> closeable,
            ModuleLayer layer) implements AutoCloseable {
        @Override public void close() throws IOException {
            IOException failure = null;
            for (var loader : closeable.reversed()) {
                try {
                    loader.close();
                } catch (IOException thrown) {
                    if (failure == null) failure = thrown;
                    else failure.addSuppressed(thrown);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static final class LoadedProgram implements Program {
        private final GenerationLoader loader;
        private final Path output;
        private Program delegate;

        private LoadedProgram(GenerationLoader loader, Path output) {
            this.loader = loader;
            this.output = output;
        }

        @Override
        public Generation define() throws Exception {
            try {
                if (delegate == null) {
                    var type = Class.forName("main", true, loader.entry());
                    var value = type.getDeclaredConstructor().newInstance();
                    if (!(value instanceof Program program)) throw new IllegalArgumentException(
                            "main must implement reload.Program");
                    delegate = program;
                }
                var generation = Objects.requireNonNull(delegate.define(), "main.define returned null");
                return generation.closing(() -> {
                    try {
                        loader.close();
                    } finally {
                        remove(output);
                    }
                });
            } catch (Throwable failure) {
                try { loader.close(); } finally { remove(output); }
                if (failure instanceof Exception exception) throw exception;
                if (failure instanceof Error error) throw error;
                throw new Exception(failure);
            }
        }
    }

    /// The conventional entrypoint is named `main`, which is also the name of
    /// Tuul's CLI class. Load only that selected application class from the
    /// candidate output first. Every other class keeps URLClassLoader's
    /// parent-first behavior, so the parent owns one identity for Tuul and its
    /// shared contracts.
    private static final class ProjectClassLoader extends URLClassLoader {

        private ProjectClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

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

    private static void printProblems(Writer output, List<reload.Problem> problems) {
        for (var problem : problems) write(output, problem.phase() + ": " + problem.message() + "\n");
    }

    private static void write(Writer output, String text) {
        synchronized (output) {
            try { output.write(text); output.flush(); } catch (IOException ignored) {}
        }
    }

    private static void remove(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var files = Files.walk(path)) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private static final class FileSystems {
        private FileSystems() {}

        private static WatchService watch() throws IOException {
            return java.nio.file.FileSystems.getDefault().newWatchService();
        }
    }
}
