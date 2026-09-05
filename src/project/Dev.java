package project;

import compiler.Compiler;
import modules.ModuleGraph;
import reload.Reload;
import reload.Revision;
import reload.RevisionSource;
import symbols.Vendor;
import web.serve.Http;
import web.reload.JdkReloadHandler;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
/// Runs a reloadable project during development.
///
/// Compilation and directory observation live at this edge of the reload
/// system. [Reload] owns generation boundaries, leases, and the last-good
/// generation. Consequently a compiler error never changes the HTTP server or
/// the code that is currently answering requests.
/// The source root is a named module. When `entrypoints/` exists, its module
/// is the root of the application-plus-entrypoint closure. Otherwise `src/`
/// is the root and must provide exactly one reload service.
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
        var reload = new Reload();
        var chosen = entrypoint == null ? "" : entrypoint;
        Builder builder;
        try {
            builder = new Builder(layout, chosen);
        } catch (IllegalArgumentException failure) {
            write(err, "error: " + (failure.getMessage() == null ? failure : failure.getMessage()) + "\n");
            reload.close();
            return 1;
        }
        var http = new JdkReloadHandler(reload);
        Built first;
        try {
            first = builder.build();
        } catch (IOException failure) {
            write(err, "error: " + (failure.getMessage() == null ? failure : failure.getMessage()) + "\n");
            reload.close();
            return 1;
        }
        var status = reload.submit(first.revision());
        if (!status.active()) {
            printProblems(err, status.problems());
            reload.close();
            return 1;
        }

        var source = new DirectorySource(layout, chosen, builder, QUIET_PERIOD, err, false);
        try (var server = Http.start((com.sun.net.httpserver.HttpHandler) http, port,
                failure -> write(err, "web: " + failure + "\n"))) {
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

    /// An injectable directory source for complete project revisions.
    ///
    /// Each revision carries a lazy in-memory program. The source watches all
    /// source modules and vendor inputs; it does not know how activation or
    /// HTTP routing works.
    public static final class DirectorySource implements RevisionSource {
        private final Layout layout;
        private final Builder builder;
        private final Duration quiet;
        private final Writer errors;
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
            this(layout, entrypoint, new Builder(layout, entrypoint), quiet, errors, true);
        }

        private DirectorySource(Layout layout, String entrypoint, Builder builder, Duration quiet, Writer errors,
                boolean submitInitial) {
            this.layout = Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(entrypoint, "entrypoint");
            this.builder = Objects.requireNonNull(builder, "builder");
            this.quiet = requireQuiet(quiet);
            this.errors = Objects.requireNonNull(errors, "errors");
            this.submitInitial = submitInitial;
        }

        @Override
        public void start(Consumer<Revision> submit) throws Exception {
            Objects.requireNonNull(submit, "submit");
            if (thread != null) throw new IllegalStateException("directory source already started");
            try (var service = FileSystems.watch()) {
                watches = service;
                register(layout.src(), service);
                register(layout.entrypointsRoot(), service);
                // A generation also depends on the vendored binary jars. A
                // dependency replacement is a revision even when no project
                // source changed, so keep that input tree in the same source
                // boundary. Missing vendor/ is fine for a fresh project.
                register(layout.vendor(), service);
                var runner = Thread.currentThread();
                thread = runner;
                if (submitInitial && !closed && !runner.isInterrupted()) {
                    var first = build();
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
                    var built = build();
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

        private Built build() {
            try {
                return builder.build();
            } catch (Exception failure) {
                write(errors, "reload build failed: "
                        + (failure.getMessage() == null ? failure : failure.getMessage()) + "\n");
                return null;
            }
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

    /// Collects one named-module project revision and attaches the JDK's
    /// in-process compiler. No class output is written to the project tree.
    public static final class Builder {
        private final Layout layout;
        private final boolean hasEntrypoints;
        private final String entrypoint;
        private final Compiler compiler;

        /// Uses the compiler in the running JDK for revisions from `layout`.
        public Builder(Layout layout, String entrypoint) {
            this(layout, entrypoint, Compiler.system());
        }

        /// Uses `compiler` for revisions from `layout`.
        /// Null arguments throw `NullPointerException`.
        public Builder(Layout layout, String entrypoint, Compiler compiler) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.entrypoint = Objects.requireNonNull(entrypoint, "entrypoint");
            try {
                var module = layout.entrypointModule();
                if (module.isPresent()) {
                    layout.reloadEntrypoint(entrypoint);
                    this.hasEntrypoints = true;
                } else {
                    if (!entrypoint.isBlank()) throw new IOException(
                            "no entrypoints module; a single-module project has no named entrypoint");
                    this.hasEntrypoints = false;
                }
            } catch (IOException failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
            this.compiler = Objects.requireNonNull(compiler, "compiler");
        }

        Built build() throws IOException {
            var application = layout.application();
            var entrypoints = hasEntrypoints
                    ? layout.entrypointModule().orElseThrow(() -> new IOException("entrypoints module disappeared"))
                    : null;
            if (entrypoints != null) layout.reloadEntrypoint(entrypoint);
            var root = entrypoints == null ? application : entrypoints;
            var vendor = Vendor.of(List.of(layout.vendor()));
            var modules = new ArrayList<Revision.SourceModule>();
            if (entrypoints == null) {
                modules.add(module(root, layout.src()));
            } else {
                modules.add(module(application, layout.src()));
                modules.add(module(entrypoints, layout.entrypointsRoot()));
            }
            var dependencyPath = vendor.artifacts().isEmpty()
                    ? List.<Path>of()
                    : vendor.graph().modules().values().stream()
                            .map(ModuleGraph.Node::origin)
                            .flatMap(origin -> origin.path().stream())
                            .distinct().toList();
            var revision = Revision.from(root.name(), modules, dependencyPath);
            var parent = new ArrayList<Path>();
            parent.add(Home.find().classes());
            return new Built(new reload.RevisionCompiler(compiler, parent,
                    JdkReloadHandler::generation)
                    .compile(revision));
        }

        private Revision.SourceModule module(Layout.SourceModule module, Path root) throws IOException {
            var resourceRoot = root.equals(layout.src()) ? layout.resources() : root;
            var logicalRoot = root.equals(layout.src()) ? layout.resources() : root;
            var resources = resources(resourceRoot, logicalRoot);
            return new Revision.SourceModule(module.name(), module.root(), module.descriptor(), module.sources(), resources);
        }

        private static List<Revision.ResourceEntry> resources(Path root, Path logicalRoot) throws IOException {
            var found = new ArrayList<Revision.ResourceEntry>();
            collect(root, logicalRoot, found);
            return found;
        }

        private static void collect(Path root, Path logicalRoot, List<Revision.ResourceEntry> into) throws IOException {
            if (!Files.isDirectory(root)) return;
            try (var files = Files.walk(root)) {
                files.filter(Files::isRegularFile).filter(path -> !path.toString().endsWith(".java"))
                        .forEach(path -> into.add(new Revision.ResourceEntry(
                                logicalRoot.relativize(path).toString().replace('\\', '/'), path)));
            }
        }
    }

    private record Built(Revision revision) {}

    private static void printProblems(Writer output, List<reload.Problem> problems) {
        for (var problem : problems) write(output, problem.phase() + ": " + problem.message() + "\n");
    }

    private static void write(Writer output, String text) {
        synchronized (output) {
            try { output.write(text); output.flush(); } catch (IOException ignored) {}
        }
    }

    private static final class FileSystems {
        private FileSystems() {}

        private static WatchService watch() throws IOException {
            return java.nio.file.FileSystems.getDefault().newWatchService();
        }
    }
}
