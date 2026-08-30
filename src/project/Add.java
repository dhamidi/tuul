package project;

import actors.ActorEffect;
import actors.ActorSystem;
import actors.Address;
import actors.Definition;
import actors.Spawn;
import application.Application;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.Properties;
import json.Json;

/// Resolves and downloads Maven artifacts into a project's `vendor/`.
///
/// One add is one ephemeral coordinator actor. Its first command creates one
/// resolution effect per root; after all roots resolve, the actor creates one
/// download effect per binary, source, and javadoc artifact. The actor runtime
/// carries out those effects concurrently. Each download publishes byte
/// progress immediately and emits one ordinary actor message when its file is
/// safely in place. The split is important: progress is live, while completion
/// is ordered state.
public final class Add {

    /// Maven Central, used when the command does not name a repository.
    public static final URI CENTRAL = URI.create("https://repo1.maven.org/maven2/");

    /// Selects the presentation for the live event feed.
    public enum Mode {
        /// ANSI progress bars for an interactive terminal.
        TTY,
        /// One plain event per line for pipes and agents.
        EVENTS
    }

    /// Selects explicit graph exclusions and accepted duplicate classes.
    public record Options(java.util.Set<String> exclusions, java.util.Set<String> duplicateExceptions,
            boolean dryRun, boolean migrate) {
        public Options {
            exclusions = java.util.Set.copyOf(exclusions);
            duplicateExceptions = java.util.Set.copyOf(duplicateExceptions);
        }

        public static Options defaults() {
            return new Options(java.util.Set.of(), java.util.Set.of(), false, false);
        }
    }

    /// The result of one add operation.
    ///
    /// `downloaded` and `cached` contain installed artifact labels in
    /// resolution order. `failed` contains one reason for each required
    /// artifact that did not reach `vendor/`; missing supplements are optional.
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
    private static final String RESOLVED = "add.resolved";
    private static final String RESOLUTION_FAILED = "add.resolve-failed";
    private static final String DOWNLOADED = "add.downloaded";
    private static final String CACHED = "add.cached";
    private static final String OPTIONAL_MISSING = "add.optional-missing";
    private static final String FAILED = "add.failed";
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
            var staging = layout.vendor().resolve(".tuul").resolve("staging-" + UUID.randomUUID());
            Files.createDirectories(staging);
            try (var session = fetch.session().redirects(Redirects.BROWSER)) {
                var services = new MavenServices(layout.vendor(), staging, sources, session, options);
                var result = run(coordinates, out, mode, services);
                if (result.ok()) {
                    services.validateDuplicates();
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

    /// Adds coordinates to `layout.vendor()` and renders the live event feed.
    ///
    /// Coordinates use `group:artifact:version` or
    /// `group:artifact:version:classifier`. The resolver reads each POM with
    /// the JDK DOM parser and follows compile and runtime dependencies.
    /// Downloads use one shared fetch client and one session, so requests share
    /// connections and run in parallel. Existing target files are cache hits.
    ///
    /// The returned result stops changing before this method returns. The
    /// event feed has completed when this method returns. The method creates
    /// `vendor/` when it does not exist. It resolves each root POM and adds
    /// its transitive compile and runtime dependencies. Each resolved jar
    /// also requests its sources and javadoc jars. Missing supplement jars do
    /// not fail the binary add.
    ///
    /// `Mode.TTY` writes ANSI bars. `Mode.EVENTS` writes one flushed line for
    /// each event. A failed download does not stop the other downloads. Setup
    /// failures throw `IOException`. Download failures appear in the result.
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
                .define(new Coordinator(roots, artifacts), Spawn.ephemeral().effects(ACTOR_EFFECT_DEADLINE))
                .define(progress, Spawn.ephemeral().mailbox(32).effects(ACTOR_EFFECT_DEADLINE))) {
            var root = Address.of(COORDINATOR, "run");
            var progressAddress = progress.at("run");
            system.effect(Progress.RENDER, progress::render);
            system.effect(Progress.CLOSE, progress::closeOutput);
            system.effect(DOWNLOAD, (effect, emit) -> download(effect, emit, services, progress));
            progress.attach(system, progressAddress);
            system.tell(root, Message.of("add.start"));
            var answer = system.ask(root, Message.of(RESULT), ASK_DEADLINE).join();
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
        private final Map<String, String> optionalMissing = new ConcurrentHashMap<>();
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

        @Override
        public Download download(String coordinate, String kind, Consumer<Event> events) throws Exception {
            var artifact = Artifact.of(Coordinate.parse(coordinate), kind);
            var relative = artifact.coordinate().directory().resolve(artifact.file());
            var active = vendor.resolve(relative);
            var target = tree.resolve(relative);
            if (Files.isRegularFile(active)) {
                Files.createDirectories(target.getParent());
                Files.copy(active, target, StandardCopyOption.REPLACE_EXISTING);
                validateArchive(target, artifact);
                var checksum = checksum(artifact, target, events);
                installed.put(relative.toString(), new FileRecord(relative.toString(), artifact.label(), kind,
                        checksum.algorithm(), checksum.value(), checksum.status(), checksum.repository()));
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
                    var checksum = checksum(artifact, target, events);
                    installed.put(relative.toString(), new FileRecord(relative.toString(), artifact.label(), kind,
                            checksum.algorithm(), checksum.value(), checksum.status(), checksum.repository()));
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
                optionalMissing.put(artifact.label(), reason);
                events.accept(Event.optionalMissing(artifact.label(), reason));
                return Download.optionalMissing(reason);
            }
            events.accept(Event.failed(artifact.label(), reason));
            return Download.failed(reason);
        }

        private void publish() throws IOException {
            if (resolution == null) throw new IOException("Maven resolution is missing");
            var metadata = staging.resolve("resolution.json");
            try (var writer = Files.newBufferedWriter(metadata)) {
                resolutionJson(resolution, installed.values().stream()
                        .sorted(java.util.Comparator.comparing(FileRecord::path)).toList(), optionalMissing,
                        options).write(writer);
            }
            publishTree(vendor, tree, staging.resolve("backup"));
            var state = vendor.resolve(".tuul");
            Files.createDirectories(state);
            move(metadata, state.resolve("resolution.json"));
        }

        private void validateDuplicates() throws IOException {
            var owners = new LinkedHashMap<String, List<FileRecord>>();
            var runtimeCoordinates = resolution.runtime().stream().map(node -> node.coordinate().text()).collect(
                    java.util.stream.Collectors.toSet());
            for (var file : installed.values()) {
                if (!file.kind().equals("binary") || !runtimeCoordinates.contains(file.coordinate())) continue;
                var classes = new LinkedHashSet<String>();
                try (var jar = new JarFile(tree.resolve(file.path()).toFile(), false)) {
                    for (var entries = jar.entries(); entries.hasMoreElements();) {
                        var entry = entries.nextElement();
                        var name = binaryClass(entry.getName());
                        if (!name.isEmpty()) classes.add(name);
                    }
                }
                for (var name : classes) owners.computeIfAbsent(name, ignored -> new ArrayList<>()).add(file);
            }
            var duplicates = owners.entrySet().stream().filter(entry -> entry.getValue().size() > 1)
                    .filter(entry -> !options.duplicateExceptions().contains(entry.getKey()))
                    .sorted(Map.Entry.comparingByKey()).toList();
            if (duplicates.isEmpty()) return;
            var report = new StringBuilder("duplicate binary classes prevent publication");
            for (var duplicate : duplicates) {
                report.append("\n  ").append(duplicate.getKey());
                for (var owner : duplicate.getValue()) report.append("\n    ").append(owner.coordinate())
                        .append(" ").append(vendor.resolve(owner.path()));
            }
            throw new IOException(report.toString());
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

        private Checksum checksum(Artifact artifact, Path target, Consumer<Event> events) throws IOException {
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
                        return new Checksum(algorithm, actual, "verified", publicRepository(repository).toString());
                    } catch (MavenTransport.Missing missing) {
                        continue;
                    }
                }
            }
            events.accept(new Event("warning", artifact.label(), 0, 0, "",
                    "repository checksum metadata is unavailable"));
            return new Checksum("", digest(target, "SHA-256"), "unavailable", "");
        }
    }

    private static void download(Effect effect, Effect.Emitter emit, Services services, Progress progress) {
        var artifact = Artifact.of(Coordinate.parse(effect.string("coordinate", "")),
                effect.string("kind", "binary"));
        try {
            var result = services.download(artifact.coordinate().text(), artifact.kind(), progress::publish);
            switch (result.status()) {
                case DOWNLOADED -> emit.emit(Message.of(DOWNLOADED).with("coordinate", artifact.label())
                        .with("target", result.target()));
                case CACHED -> emit.emit(Message.of(CACHED).with("coordinate", artifact.label())
                        .with("target", result.target()));
                case OPTIONAL_MISSING -> emit.emit(Message.of(OPTIONAL_MISSING).with("coordinate", artifact.label())
                        .with("reason", result.reason()));
                case FAILED -> emit.emit(Message.of(FAILED).with("coordinate", artifact.label())
                        .with("reason", result.reason()));
            }
        } catch (Exception failure) {
            if (Thread.currentThread().isInterrupted()) throw new RuntimeException(failure);
            var reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            progress.publish(Event.failed(artifact.label(), reason));
            emit.emit(Message.of(FAILED).with("coordinate", artifact.label()).with("reason", reason));
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
        deleteWithin(stagingParent(backup), backup);
    }

    private static Json resolutionJson(Maven.Resolution resolution, List<FileRecord> files,
            Map<String, String> optionalMissing, Options options) {
        var runtime = resolution.runtime().stream().map(Add::nodeJson).map(value -> (Json) value).toList();
        var test = resolution.test().stream().map(Add::nodeJson).map(value -> (Json) value).toList();
        var omitted = resolution.omitted().stream().map(entry -> (Json) nodeJson(entry.node())
                .with("selected", entry.selected().text()).with("reason", entry.reason())).toList();
        return Json.Object.of()
                .with("version", 1)
                .with("roots", Json.Array.strings(resolution.roots().stream().map(Coordinate::text).toList()))
                .with("runtime", Json.Array.of(runtime))
                .with("test", Json.Array.of(test))
                .with("omitted", Json.Array.of(omitted))
                .with("files", Json.Array.of(files.stream().map(file -> (Json) Json.Object.of()
                        .with("path", file.path()).with("coordinate", file.coordinate()).with("kind", file.kind())
                        .with("checksumAlgorithm", file.checksumAlgorithm()).with("checksum", file.checksum())
                        .with("checksumStatus", file.checksumStatus()).with("repository", file.repository())).toList()))
                .with("optionalMissing", Json.Array.of(optionalMissing.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(entry -> (Json) Json.Object.of().with("coordinate", entry.getKey())
                                .with("reason", entry.getValue())).toList()))
                .with("downloadLimits", Json.Object.of().with("global", MavenTransport.GLOBAL_LIMIT)
                        .with("perOrigin", MavenTransport.ORIGIN_LIMIT))
                .with("exclusions", Json.Array.strings(resolution.exclusions().stream().sorted().toList()))
                .with("duplicateExceptions", Json.Array.strings(options.duplicateExceptions().stream().sorted().toList()));
    }

    private static Json.Object nodeJson(Maven.Node node) {
        var coordinate = node.coordinate();
        return Json.Object.of().with("coordinate", coordinate.text())
                .with("group", coordinate.group()).with("artifact", coordinate.artifact())
                .with("version", coordinate.version()).with("type", coordinate.type())
                .with("classifier", coordinate.classifier()).with("scope", node.scope())
                .with("repository", node.repository().toString())
                .with("path", Json.Array.strings(node.path()))
                .with("relocatedFrom", node.relocatedFrom());
    }

    private record FileRecord(String path, String coordinate, String kind, String checksumAlgorithm,
            String checksum, String checksumStatus, String repository) {}

    private record Checksum(String algorithm, String value, String status, String repository) {}

    private static Path stagingParent(Path path) {
        var parent = path.getParent();
        return parent == null ? path : parent;
    }

    private static void delete(Path staging) throws IOException {
        var name = staging.getFileName() == null ? "" : staging.getFileName().toString();
        if (!name.startsWith("staging-") || staging.getParent() == null
                || !staging.getParent().getFileName().toString().equals(".tuul")) {
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
        public Application<Job> instantiate(Address self) {
            return Application.of(Job.waiting(roots).resolvedAll(artifacts))
                    .on("add.start", Coordinator::start)
                    .on(RESOLVED, Coordinator::resolved)
                    .on(RESOLUTION_FAILED, Coordinator::resolutionFailed)
                    .on(DOWNLOADED, Coordinator::downloaded)
                    .on(CACHED, Coordinator::cached)
                    .on(OPTIONAL_MISSING, Coordinator::optionalMissing)
                    .on(FAILED, Coordinator::failed)
                    .on(RESULT, Coordinator::answer);
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
