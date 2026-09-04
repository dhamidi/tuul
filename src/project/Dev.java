package project;

import compiler.Compiler;
import reload.Reload;
import reload.Revision;
import reload.RevisionSource;
import symbols.Vendor;
import web.serve.Http;
import web.reload.ReloadHandler;

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
        var http = new ReloadHandler(reload);
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
        try (var server = Http.start(http, port, failure -> write(err, "web: " + failure + "\n"))) {
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

    /// An injectable directory source for complete project revisions.
    ///
    /// Each revision carries a lazy in-memory program. The source does not know
    /// how activation or HTTP routing works.
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

    /// Collects one project revision and attaches its in-memory compiler.
    public static final class Builder {
        private final Layout layout;
        private final String entrypoint;
        private final Writer errors;
        private final Compiler compiler;

        /// Uses the compiler in the running JDK for revisions from `layout`.
        public Builder(Layout layout, String entrypoint, Writer errors) {
            this(layout, entrypoint, errors, Compiler.system());
        }

        /// Uses `compiler` for revisions from `layout`.
        ///
        /// Input-discovery problems are written to `errors`. [Dev#run] writes
        /// compiler diagnostics after the coordinator rejects a candidate.
        /// Null arguments throw `NullPointerException`.
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
            var resourceEntries = resources(layout, libraries, source);
            var classpath = List.of(Home.find().classes());
            var revision = Revision.fromEntries(layout.root(), entrypoint, sourceFiles,
                    resourceEntries, vendor.runtime());
            return new Built(new reload.RevisionCompiler(compiler, classpath).compile(revision));
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

        private static List<Revision.ResourceEntry> resources(Layout layout, List<Path> libraries, Path entry)
                throws IOException {
            var found = new ArrayList<Revision.ResourceEntry>();
            for (var root : libraries) collect(root, layout.src(), found);
            collect(layout.resources(), layout.resources(), found);
            collect(entry, entry, found);
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
