package project;

import actors.ActorEffect;
import actors.ActorSystem;
import actors.Address;
import actors.Behavior;
import actors.Definition;
import actors.MessageType;
import actors.Spawn;
import application.Effect;
import application.Message;
import application.Step;
import fetch.Fetch;
import fetch.Redirects;
import fetch.Response;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.Properties;
import java.lang.module.ModuleFinder;
import modules.ModuleGraph;
import symbols.Vendor;
import json.Json;

/// Resolves all requested Maven roots as one graph and installs the selected
/// artifacts in `vendor/<group>/<artifact>/<version>/`.
///
/// Downloads go to a temporary tree outside `vendor/`. Required JARs must pass
/// checksum, archive, coordinate, and JPMS module-graph validation before
/// publication. A failure leaves `vendor/` unchanged. Source and javadoc JARs
/// are optional. They never enter the runtime module path.
///
/// One coordinator actor runs the selected downloads. Progress is live.
/// Completion is ordered state. At most eight requests run at once and at most
/// four use one origin. A GET has at most four attempts. Transport failures,
/// response-body failures, HTTP 408, 425, 429, 500, 502, 503, and 504 retry.
/// HTTP 404 does not retry. Cancellation preserves the interrupted state.
public final class Add {

    /// Maven Central, used when the command does not name a repository.
    public static final URI CENTRAL = URI.create("https://repo1.maven.org/maven2/");

    /// Selects the presentation for the live event feed.
    public enum Mode {
        /// ANSI progress bars for an interactive terminal.
        TTY,
        /// Plain plan, lifecycle, diagnostic, and final event lines for pipes
        /// and agents. Byte-level `progress` events are omitted.
        EVENTS
    }

    /// Selects graph exclusions and write mode. A dry run reports the selected graph and missing files without changing
    /// `vendor/`. The other values apply only to this call.
    public record Options(java.util.Set<String> exclusions, boolean dryRun) {
        public Options {
            exclusions = java.util.Set.copyOf(exclusions);
        }

        public static Options defaults() {
            return new Options(java.util.Set.of(), false);
        }
    }

    /// The result of one add operation.
    ///
    /// `downloaded` and `cached` contain staged artifact labels in resolution
    /// order. `failed` contains one reason for each required artifact that did
    /// not pass staging. Missing supplements are optional. A successful return
    /// means the staged graph was published before this result was returned.
    public record Result(List<String> downloaded, List<String> cached, List<Failure> failed) {
        public Result {
            downloaded = List.copyOf(downloaded);
            cached = List.copyOf(cached);
            failed = List.copyOf(failed);
        }

        public boolean ok() {
            return failed.isEmpty();
        }
    }

    /// One coordinate that could not be installed.
    public record Failure(String coordinate, String reason) {}

    interface Services {
        Resolved resolve(List<String> coordinates) throws Exception;

        Download download(String coordinate, String kind, Consumer<Event> events) throws Exception;
    }

    record Resolved(List<String> artifacts, List<Event> plan) {
        Resolved {
            artifacts = List.copyOf(artifacts);
            plan = List.copyOf(plan);
        }
    }

    record Download(Status status, String target, String reason) {
        enum Status {
            DOWNLOADED, CACHED, OPTIONAL_MISSING, FAILED
        }

        static Download downloaded(String target) {
            return new Download(Status.DOWNLOADED, target, "");
        }

        static Download cached(String target) {
            return new Download(Status.CACHED, target, "");
        }

        static Download optionalMissing(String reason) {
            return new Download(Status.OPTIONAL_MISSING, "", reason);
        }

        static Download failed(String reason) {
            return new Download(Status.FAILED, "", reason);
        }
    }

    /// A live change in one download. Subscribers must treat it as immutable.
    ///
    /// `type` is `resolve`, `resolved`, `resolve-failed`, `selected`, `omitted`,
    /// `limits`, `warning`, `start`, `progress`, `done`, `cached`,
    /// `optional-missing`, `failed`, or `complete`.
    /// `bytes` and `total` apply to `start` and `progress`. A negative `total`
    /// means that the response did not provide a content length. `target`
    /// applies to `done` and `cached`. `reason` explains plan and failure
    /// events. For `complete`, `coordinate` is `all` and `reason` is the summary.
    public record Event(String type, String coordinate, long bytes, long total,
            String target, String reason) {
        public Event {
            type = required(type, "type");
            coordinate = required(coordinate, "coordinate");
            target = target == null ? "" : target;
            reason = reason == null ? "" : reason;
        }

        static Event start(String coordinate, long total) {
            return new Event("start", coordinate, 0, total, "", "");
        }

        static Event resolve(String coordinate) {
            return new Event("resolve", coordinate, 0, 0, "", "");
        }

        static Event resolved(String coordinate, int count) {
            return new Event("resolved", coordinate, count, count, "", "");
        }

        static Event resolveFailed(String coordinate, String reason) {
            return new Event("resolve-failed", coordinate, 0, 0, "", reason);
        }

        static Event progress(String coordinate, long bytes, long total) {
            return new Event("progress", coordinate, bytes, total, "", "");
        }

        static Event done(String coordinate, long bytes, String target) {
            return new Event("done", coordinate, bytes, bytes, target, "");
        }

        static Event cached(String coordinate, String target) {
            return new Event("cached", coordinate, 0, 0, target, "");
        }

        static Event failed(String coordinate, String reason) {
            return new Event("failed", coordinate, 0, 0, "", reason);
        }

        static Event optionalMissing(String coordinate, String reason) {
            return new Event("optional-missing", coordinate, 0, 0, "", reason);
        }

        static Event complete(int downloaded, int cached, int failed) {
            return new Event("complete", "all", 0, 0, "", downloaded + " downloaded, "
                    + cached + " cached, " + failed + " failed");
        }

        private static String required(String value, String name) {
            if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " is empty");
            return value;
        }
    }

    private static final String COORDINATOR = "add";
    private static final String DOWNLOAD = "add.download";
    private static final String RESOLVE = "add.resolve";
    private static final MessageType START = MessageType.command("start-add");
    private static final MessageType RECORD_RESOLUTION = MessageType.command("record-resolution");
    private static final MessageType RECORD_RESOLUTION_FAILURE = MessageType.command("record-resolution-failure");
    private static final MessageType RECORD_DOWNLOAD = MessageType.command("record-download");
    private static final MessageType RECORD_CACHE = MessageType.command("record-cache");
    private static final MessageType RECORD_OPTIONAL_MISSING = MessageType.command("record-optional-missing");
    private static final MessageType RECORD_FAILURE = MessageType.command("record-failure");
    private static final MessageType GET_RESULT = MessageType.query("get-result");
    private static final String RESULT = "add.result";
    private static final Duration ASK_DEADLINE = Duration.ofDays(1);
    private static final Duration ACTOR_EFFECT_DEADLINE = Duration.ofDays(1);
    private static final int MAX_DOWNLOADS = MavenTransport.GLOBAL_LIMIT;
    private static final long PROGRESS_BYTES = 64 * 1024;
    private static final long PROGRESS_NANOS = Duration.ofMillis(100).toNanos();

    private Add() {}

    /// Creates a client that reuses one HTTP connection pool across add calls.
    public static Client client() {
        return new Client();
    }

    /// A reusable Maven add client. One command normally needs one client; a
    /// caller that performs several adds can keep the pool warm between them.
    public static final class Client implements AutoCloseable {
        private final Fetch fetch = Fetch.virtualThreads();
        private boolean closed;

        private Client() {}

        public Result into(Layout layout, List<String> coordinates,
                List<URI> repositories, Writer out, Mode mode) throws IOException {
            return into(layout, coordinates, repositories, out, mode, Options.defaults());
        }

        public Result into(Layout layout, List<String> coordinates,
                List<URI> repositories, Writer out, Mode mode, Options options) throws IOException {
            if (closed) throw new IllegalStateException("add client is closed");
            var sources = repositories == null || repositories.isEmpty() ? List.of(CENTRAL)
                    : repositories.stream().map(Add::repository).distinct().toList();
            Files.createDirectories(layout.vendor());
            var staging = Files.createTempDirectory(layout.root().toAbsolutePath().normalize(), ".tuul-add-");
            try (var session = fetch.session().redirects(Redirects.BROWSER)) {
                var services = new MavenServices(layout.vendor(), staging, sources, session, options);
                if (options.dryRun()) return services.preview(coordinates, out);
                var result = run(coordinates, out, mode, services);
                if (result.ok()) {
                    services.validateModules();
                    services.publish();
                }
                return result;
            } finally {
                delete(staging);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            fetch.close();
        }
    }

    /// Adds one or more coordinates to `layout.vendor()` and renders the event
    /// feed.
    ///
    /// A coordinate uses `group:artifact:version` or
    /// `group:artifact:version:classifier`. All roots enter one deterministic
    /// resolution. Existing selected files are verified cache hits. Each
    /// selected artifact also requests optional source and javadoc JARs.
    /// Publication replaces selected version directories and preserves every
    /// other file under `vendor/`. It writes no dependency metadata.
    ///
    /// `Mode.TTY` writes one flushed, bounded batch of ANSI progress lines.
    /// `Mode.EVENTS` writes one flushed semantic event per line and omits byte-level progress.
    /// Resolution and setup errors throw `IOException`. Download failures
    /// appear in the result. A required failure does not publish staged files.
    /// Module-graph failures throw `IOException` before publication.
    public static Result into(Layout layout, List<String> coordinates,
            List<URI> repositories, Writer out, Mode mode) throws IOException {
        return into(layout, coordinates, repositories, out, mode, Options.defaults());
    }

    public static Result into(Layout layout, List<String> coordinates,
            List<URI> repositories, Writer out, Mode mode, Options options) throws IOException {
        try (var client = client()) {
            return client.into(layout, coordinates, repositories, out, mode, options);
        }
    }

    static Result into(List<String> coordinates, Writer out, Mode mode, Services services) throws IOException {
        return run(coordinates, out, mode, services);
    }

    private static Result run(List<String> coordinates, Writer out, Mode mode, Services services) throws IOException {
        var roots = coordinates.stream().map(Coordinate::parse).distinct().toList();
        if (roots.isEmpty()) throw new IOException("tuul add needs at least one dependency");

        var progress = new Progress(out, mode);
        List<Coordinate> artifacts;
        for (var root : roots) progress.publish(Event.resolve(root.text()));
        try {
            var resolved = services.resolve(roots.stream().map(Coordinate::text).toList());
            for (var event : resolved.plan()) progress.publish(event);
            artifacts = resolved.artifacts().stream()
                    .map(Coordinate::parse).toList();
            progress.schedule(scheduled(artifacts).size());
            for (var root : roots) progress.publish(Event.resolved(root.text(), artifacts.size()));
        } catch (Exception failure) {
            var reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            for (var root : roots) progress.publish(Event.resolveFailed(root.text(), reason));
            var result = new Result(List.of(), List.of(),
                    roots.stream().map(root -> new Failure(root.text(), reason)).toList());
            progress.publish(Event.complete(0, 0, result.failed().size()));
            progress.close();
            return result;
        }
        try (var system = ActorSystem.named("tuul-add")
                .define(new Coordinator(roots, artifacts), Spawn.ephemeral().effects(ACTOR_EFFECT_DEADLINE))) {
            var root = Address.of(COORDINATOR, "run");
            system.effect(DOWNLOAD, (effect, emit) -> download(effect, emit, services, progress));
            progress.attach(system);
            system.tell(root, START.message());
            var answer = system.ask(root, GET_RESULT.message(), ASK_DEADLINE).join();
            var result = result(answer);
            progress.publish(Event.complete(result.downloaded().size(), result.cached().size(), result.failed().size()));
            progress.close();
            return result;
        } catch (RuntimeException failure) {
            progress.close();
            var cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof IOException io) throw io;
            throw failure;
        }
    }

    private static URI repository(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getUserInfo() != null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("repository must be an HTTP or HTTPS URI without credentials: " + uri);
        }
        return URI.create(uri.toString().endsWith("/") ? uri.toString() : uri + "/");
    }

    private static final class MavenServices implements Services {
        private final Path vendor;
        private final Path staging;
        private final Path tree;
        private final List<URI> repositories;
        private final MavenTransport transport;
        private final Options options;
        private final Map<String, FileRecord> installed = new ConcurrentHashMap<>();
        private Maven.Resolution resolution;

        private MavenServices(Path vendor, Path staging, List<URI> repositories, fetch.Session session,
                Options options) {
            this.vendor = vendor;
            this.staging = staging;
            this.tree = staging.resolve("tree");
            this.repositories = repositories;
            this.transport = new MavenTransport(session);
            this.options = options;
        }

        @Override
        public Resolved resolve(List<String> coordinates) throws Exception {
            resolution = new Maven.Resolver(this::pom)
                    .resolve(coordinates.stream().map(Coordinate::parse).toList(), options.exclusions());
            var plan = new ArrayList<Event>();
            plan.add(new Event("limits", "all", MavenTransport.GLOBAL_LIMIT, MavenTransport.ORIGIN_LIMIT,
                    "", MavenTransport.GLOBAL_LIMIT + " global, " + MavenTransport.ORIGIN_LIMIT + " per origin"));
            for (var node : resolution.runtime()) plan.add(new Event("selected", node.coordinate().text(), 0, 0, "",
                    "runtime via " + String.join(" -> ", node.path())));
            for (var node : resolution.test()) {
                if (resolution.runtime().stream().anyMatch(runtime -> runtime.coordinate().equals(node.coordinate()))) continue;
                plan.add(new Event("selected", node.coordinate().text(), 0, 0, "",
                        "test via " + String.join(" -> ", node.path())));
            }
            for (var omitted : resolution.omitted()) plan.add(new Event("omitted",
                    omitted.node().coordinate().text(), 0, 0, "", "selected " + omitted.selected().text()
                            + " via " + String.join(" -> ", omitted.node().path())));
            return new Resolved(resolution.selected().stream().map(node -> node.coordinate().wire()).toList(), plan);
        }

        private Result preview(List<String> coordinates, Writer out) throws IOException {
            try {
                var resolved = resolve(coordinates);
                for (var event : resolved.plan()) writePlan(out, event);
                var planned = new LinkedHashSet<String>();
                for (var value : resolved.artifacts()) {
                    var coordinate = Coordinate.parse(value);
                    for (var kind : List.of("binary", "sources", "javadoc")) {
                        var artifact = Artifact.of(coordinate, kind);
                        planned.add(coordinate.directory().resolve(artifact.file()).toString());
                    }
                }
                for (var path : planned) if (!Files.isRegularFile(vendor.resolve(path))) writePlan(out,
                        new Event("add", path, 0, 0, "", "missing from vendor"));
                out.flush();
                return new Result(List.of(), List.of(), List.of());
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException(failure.getMessage(), failure);
            }
        }

        @Override
        public Download download(String coordinate, String kind, Consumer<Event> events) throws Exception {
            var artifact = Artifact.of(Coordinate.parse(coordinate), kind);
            var relative = artifact.coordinate().directory().resolve(artifact.file());
            var active = vendor.resolve(relative);
            var target = tree.resolve(relative);
            if (Files.isRegularFile(target)) {
                validateArchive(target, artifact);
                checksum(artifact, target, events);
                installed.put(relative.toString(), new FileRecord(relative.toString(), artifact.label(), kind));
                events.accept(Event.cached(artifact.label(), active.toString()));
                return Download.cached(active.toString());
            }
            if (Files.isRegularFile(active)) {
                Files.createDirectories(target.getParent());
                Files.copy(active, target, StandardCopyOption.REPLACE_EXISTING);
                validateArchive(target, artifact);
                checksum(artifact, target, events);
                installed.put(relative.toString(), new FileRecord(relative.toString(), artifact.label(), kind));
                events.accept(Event.cached(artifact.label(), active.toString()));
                return Download.cached(active.toString());
            }

            Files.createDirectories(target.getParent());
            events.accept(Event.start(artifact.label(), -1));
            Exception last = null;
            for (var repository : repositories) {
                var uri = artifact.uri(repository);
                try {
                    var bytes = transport.get(uri, artifact.label(), kind,
                            response -> write(response, artifact, target, events));
                    validateArchive(target, artifact);
                    checksum(artifact, target, events);
                    installed.put(relative.toString(), new FileRecord(relative.toString(), artifact.label(), kind));
                    events.accept(Event.done(artifact.label(), bytes, active.toString()));
                    return Download.downloaded(active.toString());
                } catch (MavenTransport.Missing missing) {
                    last = missing;
                    continue;
                } catch (MavenTransport.Permanent broken) {
                    last = broken;
                    break;
                } catch (Exception failure) {
                    if (Thread.currentThread().isInterrupted()) throw failure;
                    last = failure;
                    break;
                }
            }
            var reason = last == null ? "artifact was not found in the configured repositories"
                    : last.getMessage() == null ? last.toString() : last.getMessage();
            if (artifact.optional()) {
                events.accept(Event.optionalMissing(artifact.label(), reason));
                return Download.optionalMissing(reason);
            }
            events.accept(Event.failed(artifact.label(), reason));
            return Download.failed(reason);
        }

        private void publish() throws IOException {
            if (resolution == null) throw new IOException("Maven resolution is missing");
            publishTree(vendor, tree, staging.resolve("backup"));
        }

        private void validateModules() throws IOException {
            var artifacts = new LinkedHashMap<Path, String>();
            // Validate the complete post-publication vendor set. A new add must
            // not make an already broken module set look valid by only checking
            // the newly selected coordinates.
            artifacts.putAll(Vendor.of(List.of(vendor)).artifacts());
            for (var file : installed.values()) {
                if (file.kind().equals("binary")) {
                    artifacts.remove(vendor.resolve(file.path()));
                }
            }
            for (var file : installed.values()) {
                if (!file.kind().equals("binary")) continue;
                var path = tree.resolve(file.path());
                if (Files.isRegularFile(path)) artifacts.put(path, file.coordinate());
            }
            var roots = new LinkedHashSet<String>();
            for (var path : artifacts.keySet()) {
                try {
                    ModuleFinder.of(path).findAll().stream()
                            .map(reference -> reference.descriptor().name()).forEach(roots::add);
                } catch (RuntimeException ignored) {
                    // ModuleGraph collects the path-specific invalid-artifact diagnostic.
                }
            }
            ModuleGraph.resolve(artifacts, roots);
        }

        private Maven.PomDocument pom(Coordinate coordinate) throws IOException {
            MavenTransport.Missing last = null;
            for (var repository : repositories) {
                try {
                    var uri = coordinate.pomUri(repository);
                    var xml = transport.get(uri, coordinate.text(), "pom", response -> response.text());
                    return new Maven.PomDocument(xml, publicRepository(repository));
                } catch (MavenTransport.Missing missing) {
                    last = missing;
                }
            }
            throw last == null ? new IOException("POM was not found for " + coordinate.text()) : last;
        }

        private void checksum(Artifact artifact, Path target, Consumer<Event> events) throws IOException {
            for (var algorithm : List.of("SHA-256", "SHA-1")) {
                var suffix = algorithm.equals("SHA-256") ? ".sha256" : ".sha1";
                for (var repository : repositories) {
                    try {
                        var uri = URI.create(artifact.uri(repository) + suffix);
                        var expected = transport.get(uri, artifact.label(), "checksum " + algorithm,
                                response -> response.text()).trim().split("\\s+", 2)[0].toLowerCase();
                        var actual = digest(target, algorithm);
                        if (!expected.equals(actual)) throw new MavenTransport.Permanent(artifact.label()
                                + " checksum mismatch: expected " + expected + " but got " + actual);
                        return;
                    } catch (MavenTransport.Missing missing) {
                        continue;
                    }
                }
            }
            events.accept(new Event("warning", artifact.label(), 0, 0, "",
                    "repository checksum metadata is unavailable"));
        }
    }

    private static void download(Effect effect, Effect.Emitter emit, Services services, Progress progress) {
        var artifact = Artifact.of(Coordinate.parse(effect.string("coordinate", "")),
                effect.string("kind", "binary"));
        try {
            var result = services.download(artifact.coordinate().text(), artifact.kind(), progress::publish);
            switch (result.status()) {
                case DOWNLOADED -> emit.emit(RECORD_DOWNLOAD.message().with("coordinate", artifact.label())
                        .with("target", result.target()));
                case CACHED -> emit.emit(RECORD_CACHE.message().with("coordinate", artifact.label())
                        .with("target", result.target()));
                case OPTIONAL_MISSING -> emit.emit(RECORD_OPTIONAL_MISSING.message().with("coordinate", artifact.label())
                        .with("reason", result.reason()));
                case FAILED -> emit.emit(RECORD_FAILURE.message().with("coordinate", artifact.label())
                        .with("reason", result.reason()));
            }
        } catch (Exception failure) {
            if (Thread.currentThread().isInterrupted()) throw new RuntimeException(failure);
            var reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            progress.publish(Event.failed(artifact.label(), reason));
            emit.emit(RECORD_FAILURE.message().with("coordinate", artifact.label()).with("reason", reason));
        }
    }

    private static long write(Response response, Artifact artifact, Path target,
            Consumer<Event> events) throws IOException {
        var total = length(response);
        events.accept(Event.progress(artifact.label(), 0, total));
        var temporary = Files.createTempFile(target.getParent(), "." + artifact.file(), ".part");
        var bytes = 0L;
        var announced = 0L;
        var announcedAt = System.nanoTime();
        try {
            try (var input = response.body().stream(); var output = Files.newOutputStream(temporary)) {
                var buffer = new byte[16 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    if (count == 0) continue;
                    output.write(buffer, 0, count);
                    bytes += count;
                    var now = System.nanoTime();
                    if (bytes - announced >= PROGRESS_BYTES || now - announcedAt >= PROGRESS_NANOS) {
                        events.accept(Event.progress(artifact.label(), bytes, total));
                        announced = bytes;
                        announcedAt = now;
                    }
                }
            }
            move(temporary, target);
            return bytes;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateArchive(Path file, Artifact artifact) throws IOException {
        try (var jar = new JarFile(file.toFile(), false)) {
            var entries = jar.entries();
            if (!entries.hasMoreElements()) throw new MavenTransport.Permanent(
                    artifact.label() + " is an empty jar");
            var metadata = "META-INF/maven/" + artifact.coordinate().group() + "/"
                    + artifact.coordinate().artifact() + "/pom.properties";
            var entry = jar.getJarEntry(metadata);
            if (entry == null) return;
            var properties = new Properties();
            try (var input = jar.getInputStream(entry)) {
                properties.load(input);
            }
            var coordinate = artifact.coordinate();
            if (!coordinate.group().equals(properties.getProperty("groupId"))
                    || !coordinate.artifact().equals(properties.getProperty("artifactId"))
                    || !coordinate.version().equals(properties.getProperty("version"))) {
                throw new MavenTransport.Permanent(artifact.label()
                        + " jar metadata does not match the requested coordinate");
            }
        } catch (MavenTransport.Permanent mismatch) {
            throw mismatch;
        } catch (IOException | RuntimeException unreadable) {
            throw new MavenTransport.Permanent(artifact.label() + " is not a readable jar", unreadable);
        }
    }

    private static String digest(Path file, String algorithm) throws IOException {
        try {
            var digest = MessageDigest.getInstance(algorithm);
            try (var input = Files.newInputStream(file)) {
                var buffer = new byte[16 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) if (count > 0) digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String binaryClass(String entry) {
        var name = entry;
        if (name.startsWith("META-INF/versions/")) {
            var slash = name.indexOf('/', "META-INF/versions/".length());
            if (slash < 0) return "";
            name = name.substring(slash + 1);
        }
        if (!name.endsWith(".class") || name.equals("module-info.class")
                || name.endsWith("/module-info.class")) return "";
        return name.substring(0, name.length() - ".class".length()).replace('/', '.');
    }

    private static URI publicRepository(URI repository) {
        try {
            return new URI(repository.getScheme(), null, repository.getHost(), repository.getPort(),
                    repository.getPath(), repository.getQuery(), repository.getFragment());
        } catch (java.net.URISyntaxException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }

    private static long length(Response response) {
        try {
            var value = Long.parseLong(response.headers().first("Content-Length", "-1"));
            return value >= 0 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void publishTree(Path vendor, Path tree, Path backup) throws IOException {
        if (!Files.isDirectory(tree)) return;
        var directories = new LinkedHashSet<Path>();
        try (var paths = Files.walk(tree)) {
            for (var file : paths.filter(Files::isRegularFile).toList()) directories.add(file.getParent());
        }
        var published = new ArrayList<Path>();
        var replaced = new ArrayList<Path>();
        try {
            for (var directory : directories) {
                var relative = tree.relativize(directory);
                if (relative.getNameCount() != 3) throw new IOException("invalid staged Maven directory " + relative);
                var target = vendor.resolve(relative).normalize();
                if (!target.startsWith(vendor.normalize())) throw new IOException("staged path escapes vendor: " + relative);
                if (Files.exists(target)) {
                    var saved = backup.resolve(relative);
                    Files.createDirectories(saved.getParent());
                    move(target, saved);
                    replaced.add(relative);
                }
                Files.createDirectories(target.getParent());
                move(directory, target);
                published.add(relative);
            }
        } catch (IOException failure) {
            for (var relative : published.reversed()) deleteWithin(vendor, vendor.resolve(relative));
            for (var relative : replaced.reversed()) {
                var saved = backup.resolve(relative);
                if (Files.exists(saved)) move(saved, vendor.resolve(relative));
            }
            throw failure;
        }
        deleteWithin(backup.getParent(), backup);
    }

    private static void writePlan(Writer out, Event event) throws IOException {
        out.write("add." + event.type() + " " + event.coordinate());
        if (!event.target().isEmpty()) out.write(" -> " + event.target());
        if (!event.reason().isEmpty()) out.write(" " + event.reason());
        out.write("\n");
    }

    private record FileRecord(String path, String coordinate, String kind) {}

    private static void delete(Path staging) throws IOException {
        var name = staging.getFileName() == null ? "" : staging.getFileName().toString();
        if (!name.startsWith(".tuul-add-") || staging.getParent() == null) {
            throw new IOException("refusing to delete an invalid staging directory: " + staging);
        }
        deleteWithin(staging.getParent(), staging);
    }

    private static void deleteWithin(Path base, Path target) throws IOException {
        var normalizedBase = base.toAbsolutePath().normalize();
        var normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedBase) || !normalizedTarget.startsWith(normalizedBase)) {
            throw new IOException("refusing to delete outside " + base + ": " + target);
        }
        if (!Files.exists(target)) return;
        try (var paths = Files.walk(target)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Result result(Message answer) {
        var downloaded = strings(answer.list("downloaded"));
        var cached = strings(answer.list("cached"));
        var failed = new ArrayList<Failure>();
        for (var value : answer.list("failed")) {
            if (value instanceof Json.Object object) failed.add(new Failure(
                    object.string("coordinate", ""), object.string("reason", "unknown failure")));
        }
        return new Result(downloaded, cached, failed);
    }

    private static List<String> strings(List<Json> values) {
        var strings = new ArrayList<String>();
        for (var value : values) if (value instanceof Json.Str(var text)) strings.add(text);
        return List.copyOf(strings);
    }

    static record Coordinate(String group, String artifact, String version, String type, String classifier) {
        static Coordinate parse(String text) {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("empty Maven coordinate");
            var parts = text.split(":", -1);
            if (parts.length != 3 && parts.length != 4 && parts.length != 5)
                throw new IllegalArgumentException("Maven coordinate must be group:artifact:version[:classifier]: " + text);
            for (var part : parts) {
                if (part.isEmpty() || !part.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_'))
                    throw new IllegalArgumentException("invalid Maven coordinate: " + text);
            }
            return parts.length == 5
                    ? new Coordinate(parts[0], parts[1], parts[2], parts[3], parts[4])
                    : new Coordinate(parts[0], parts[1], parts[2], "jar", parts.length == 4 ? parts[3] : "");
        }

        String text() {
            if (!type.equals("jar")) return wire();
            return group + ":" + artifact + ":" + version + (classifier.isEmpty() ? "" : ":" + classifier);
        }

        String wire() {
            return type.equals("jar") ? text() : group + ":" + artifact + ":" + version + ":" + type + ":" + classifier;
        }

        String file() {
            var extension = type.equals("pom") ? "pom" : "jar";
            return artifact + "-" + version + (classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
        }

        URI uri(URI repository) {
            return repository.resolve(group.replace('.', '/') + "/" + artifact + "/" + version + "/" + file());
        }

        URI pomUri(URI repository) {
            return repository.resolve(group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                    + artifact + "-" + version + ".pom");
        }

        static Coordinate of(String group, String artifact, String version, String classifier) {
            return new Coordinate(group, artifact, version, "jar", classifier);
        }

        static Coordinate of(String group, String artifact, String version, String type, String classifier) {
            return new Coordinate(group, artifact, version, type, classifier);
        }

        Coordinate withoutClassifier() {
            return new Coordinate(group, artifact, version, type, "");
        }

        Coordinate withType(String value) {
            return new Coordinate(group, artifact, version, value, classifier);
        }

        Coordinate pomCoordinate() {
            return new Coordinate(group, artifact, version, "pom", "");
        }

        String dependencyKey() {
            return group + ":" + artifact;
        }

        String conflictKey() {
            return group + ":" + artifact + ":" + type + ":" + classifier;
        }

        Path directory() {
            return Path.of(group, artifact, version);
        }
    }

    private record Artifact(Coordinate coordinate, String kind) {
        static Artifact of(Coordinate coordinate, String kind) {
            if (!kind.equals("binary") && !kind.equals("sources") && !kind.equals("javadoc")) {
                throw new IllegalArgumentException("unknown Maven artifact kind: " + kind);
            }
            return new Artifact(coordinate, kind);
        }

        boolean optional() {
            return !kind.equals("binary");
        }

        String label() {
            return kind.equals("binary") ? coordinate.text() : coordinate.withoutClassifier().text() + ":" + kind;
        }

        String file() {
            var base = coordinate.withoutClassifier();
            return base.artifact() + "-" + base.version()
                    + (kind.equals("binary") && !coordinate.classifier().isEmpty()
                            ? "-" + coordinate.classifier() : "")
                    + (kind.equals("binary") ? "" : "-" + kind) + ".jar";
        }

        URI uri(URI repository) {
            var base = coordinate.withoutClassifier();
            return repository.resolve(base.group().replace('.', '/') + "/" + base.artifact() + "/"
                    + base.version() + "/" + file());
        }
    }

    private record Outcome(String coordinate, String target, String reason,
            boolean downloaded, boolean cached, boolean optionalMissing) {}

    private record Job(List<Coordinate> roots, List<Coordinate> artifacts,
            Map<String, Outcome> outcomes, Map<String, List<Coordinate>> resolutions,
            Map<String, Failure> resolutionFailures, boolean started, boolean downloadsStarted,
            int launched) {
        Job {
            roots = List.copyOf(roots);
            artifacts = List.copyOf(artifacts);
            outcomes = Map.copyOf(outcomes);
            resolutions = Map.copyOf(resolutions);
            resolutionFailures = Map.copyOf(resolutionFailures);
        }

        static Job waiting(List<Coordinate> roots) {
            return new Job(roots, List.of(), Map.of(), Map.of(), Map.of(), false, false, 0);
        }

        Job begin() {
            return new Job(roots, artifacts, outcomes, resolutions, resolutionFailures, true, downloadsStarted,
                    launched);
        }

        Job outcome(Outcome outcome) {
            var next = new LinkedHashMap<>(outcomes);
            next.putIfAbsent(outcome.coordinate(), outcome);
            return new Job(roots, artifacts, next, resolutions, resolutionFailures, started, downloadsStarted,
                    launched);
        }

        Job resolved(Coordinate root, List<Coordinate> found) {
            var next = new LinkedHashMap<>(resolutions);
            next.putIfAbsent(root.text(), found);
            var all = new LinkedHashMap<String, Coordinate>();
            for (var values : next.values()) for (var coordinate : values) {
                all.putIfAbsent(coordinate.text(), coordinate);
            }
            return new Job(roots, List.copyOf(all.values()), outcomes, next, resolutionFailures,
                    started, downloadsStarted, launched);
        }

        Job resolvedAll(List<Coordinate> found) {
            var next = new LinkedHashMap<String, List<Coordinate>>();
            for (var root : roots) next.put(root.text(), found);
            return new Job(roots, found, outcomes, next, resolutionFailures,
                    started, downloadsStarted, launched);
        }

        Job resolutionFailure(Coordinate root, String reason) {
            var next = new LinkedHashMap<>(resolutionFailures);
            next.putIfAbsent(root.text(), new Failure(root.text(), reason));
            return new Job(roots, artifacts, outcomes, resolutions, next, started, downloadsStarted, launched);
        }

        Job beginDownloads(int launched) {
            return new Job(roots, artifacts, outcomes, resolutions, resolutionFailures, started, true, launched);
        }

        Job launch(int count) {
            return new Job(roots, artifacts, outcomes, resolutions, resolutionFailures, started,
                    downloadsStarted, launched + count);
        }

        boolean complete() {
            return downloadsStarted && outcomes.size() == scheduled(artifacts).size()
                    && resolutions.size() + resolutionFailures.size() == roots.size();
        }
    }

    private static List<Artifact> scheduled(List<Coordinate> coordinates) {
        var found = new LinkedHashMap<String, Artifact>();
        for (var coordinate : coordinates) for (var kind : List.of("binary", "sources", "javadoc")) {
            var artifact = Artifact.of(coordinate, kind);
            found.putIfAbsent(artifact.label(), artifact);
        }
        return List.copyOf(found.values());
    }

    static List<Integer> batches(int total) {
        if (total < 0) throw new IllegalArgumentException("a download queue cannot be negative: " + total);
        var batches = new ArrayList<Integer>();
        for (var remaining = total; remaining > 0;) {
            var count = Math.min(MAX_DOWNLOADS, remaining);
            batches.add(count);
            remaining -= count;
        }
        return List.copyOf(batches);
    }

    private static final class Coordinator implements Definition<Job> {
        private final List<Coordinate> roots;
        private final List<Coordinate> artifacts;

        private Coordinator(List<Coordinate> roots, List<Coordinate> artifacts) {
            this.roots = roots;
            this.artifacts = artifacts;
        }

        @Override
        public String type() {
            return COORDINATOR;
        }

        @Override
        public Behavior<Job> instantiate(Address self) {
            return Behavior.of(Job.waiting(roots).resolvedAll(artifacts))
                    .on(START, Coordinator::start)
                    .on(RECORD_RESOLUTION, Coordinator::resolved)
                    .on(RECORD_RESOLUTION_FAILURE, Coordinator::resolutionFailed)
                    .on(RECORD_DOWNLOAD, Coordinator::downloaded)
                    .on(RECORD_CACHE, Coordinator::cached)
                    .on(RECORD_OPTIONAL_MISSING, Coordinator::optionalMissing)
                    .on(RECORD_FAILURE, Coordinator::failed)
                    .on(GET_RESULT, Coordinator::answer);
        }

        private static Step<Job> start(Job state, Message message) {
            if (state.started()) return Step.of(state);
            return downloads(state.begin());
        }

        private static Step<Job> resolved(Job state, Message message) {
            var root = Coordinate.parse(message.string("coordinate", ""));
            var found = strings(message.list("artifacts")).stream().map(Coordinate::parse).toList();
            return downloads(state.resolved(root, found));
        }

        private static Step<Job> resolutionFailed(Job state, Message message) {
            var root = Coordinate.parse(message.string("coordinate", ""));
            return downloads(state.resolutionFailure(root, message.string("reason", "resolution failed")));
        }

        private static Step<Job> downloads(Job state) {
            if (state.resolutions().size() + state.resolutionFailures().size() != state.roots().size()
                    || state.downloadsStarted()) return Step.of(state);
            var artifacts = scheduled(state.artifacts());
            if (artifacts.isEmpty()) return Step.of(state.beginDownloads(0));
            var count = batches(artifacts.size()).getFirst();
            var effects = artifacts.subList(0, count).stream().map(Coordinator::download).toList();
            return new Step<>(state.beginDownloads(count), effects);
        }

        private static Step<Job> downloaded(Job state, Message message) {
            return refill(state.outcome(new Outcome(message.string("coordinate", ""),
                    message.string("target", ""), "", true, false, false)));
        }

        private static Step<Job> cached(Job state, Message message) {
            return refill(state.outcome(new Outcome(message.string("coordinate", ""),
                    message.string("target", ""), "", false, true, false)));
        }

        private static Step<Job> optionalMissing(Job state, Message message) {
            return refill(state.outcome(new Outcome(message.string("coordinate", ""), "",
                    message.string("reason", "supplement was not found"), false, false, true)));
        }

        private static Step<Job> failed(Job state, Message message) {
            return refill(state.outcome(new Outcome(message.string("coordinate", ""), "",
                    message.string("reason", "unknown failure"), false, false, false)));
        }

        private static Step<Job> refill(Job state) {
            var artifacts = scheduled(state.artifacts());
            if (!state.downloadsStarted() || state.launched() >= artifacts.size()
                    || state.outcomes().size() == 0 || state.outcomes().size() % MAX_DOWNLOADS != 0) {
                return Step.of(state);
            }
            var count = batches(artifacts.size() - state.launched()).getFirst();
            var effects = artifacts.subList(state.launched(), state.launched() + count)
                    .stream().map(Coordinator::download).toList();
            return new Step<>(state.launch(count), effects);
        }

        private static Effect download(Artifact artifact) {
            return Effect.of(DOWNLOAD).with("coordinate", artifact.coordinate().text())
                    .with("kind", artifact.kind());
        }

        private static Step<Job> answer(Job state, Message message) {
            if (!state.complete()) return Step.of(state);
            var downloaded = new ArrayList<String>();
            var cached = new ArrayList<String>();
            var failed = new ArrayList<Json>();
            for (var artifact : scheduled(state.artifacts())) {
                var outcome = state.outcomes().get(artifact.label());
                if (outcome.downloaded()) downloaded.add(outcome.coordinate());
                else if (outcome.cached()) cached.add(outcome.coordinate());
                else if (!outcome.optionalMissing()) failed.add(Json.Object.of().with("coordinate", outcome.coordinate())
                        .with("reason", outcome.reason()));
            }
            failed.addAll(state.resolutionFailures().values().stream()
                    .map(failure -> (Json) Json.Object.of().with("coordinate", failure.coordinate())
                            .with("reason", failure.reason())).toList());
            return Step.of(state, ActorEffect.reply(Message.of(RESULT)
                    .with("downloaded", Json.Array.strings(downloaded))
                    .with("cached", Json.Array.strings(cached))
                    .with("failed", Json.Array.of(failed))));
        }

        @Override
        public Json inspect(Job state) {
            return Json.Object.of().with("started", state.started())
                    .with("complete", state.complete())
                    .with("finished", state.outcomes().size())
                    .with("total", state.artifacts().size());
        }
    }

}
