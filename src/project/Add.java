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
import fetch.HttpException;
import fetch.Redirects;
import fetch.Response;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
        List<String> resolve(String coordinate) throws Exception;

        Download download(String coordinate, String kind, Consumer<Event> events) throws Exception;
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
    /// `type` is `resolve`, `resolved`, `resolve-failed`, `start`, `progress`,
    /// `done`, `cached`, `optional-missing`, `failed`, or `complete`.
    /// `bytes` and `total` apply to `start` and `progress`. A negative `total`
    /// means that the response did not provide a content length. `target`
    /// applies to `done` and `cached`. `reason` applies to failures. For
    /// `complete`, `coordinate` is `all` and `reason` is the summary.
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
    private static final int MAX_DOWNLOADS = 20;
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
            if (closed) throw new IllegalStateException("add client is closed");
            var sources = repositories == null || repositories.isEmpty() ? List.of(CENTRAL)
                    : repositories.stream().map(Add::repository).distinct().toList();
            Files.createDirectories(layout.vendor());
            try (var session = fetch.session().redirects(Redirects.BROWSER)) {
                return run(coordinates, out, mode, new MavenServices(layout.vendor(), sources, session));
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
        try (var client = client()) {
            return client.into(layout, coordinates, repositories, out, mode);
        }
    }

    static Result into(List<String> coordinates, Writer out, Mode mode, Services services) throws IOException {
        return run(coordinates, out, mode, services);
    }

    private static Result run(List<String> coordinates, Writer out, Mode mode, Services services) throws IOException {
        var roots = coordinates.stream().map(Coordinate::parse).distinct().toList();
        if (roots.isEmpty()) throw new IOException("tuul add needs at least one dependency");

        var progress = new Progress(out, mode);
        try (var system = ActorSystem.named("tuul-add")
                .define(new Coordinator(roots), Spawn.ephemeral().effects(ACTOR_EFFECT_DEADLINE))
                .define(progress, Spawn.ephemeral().mailbox(32).effects(ACTOR_EFFECT_DEADLINE))) {
            var root = Address.of(COORDINATOR, "run");
            var progressAddress = progress.at("run");
            progress.attach(system, progressAddress);
            system.effect(Progress.RENDER, progress::render);
            system.effect(Progress.CLOSE, progress::closeOutput);
            system.effect(DOWNLOAD, (effect, emit) -> download(effect, emit, services, progress));
            system.effect(RESOLVE, (effect, emit) -> resolve(effect, emit, services, progress));
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
        if (uri == null || uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("repository must be an HTTP or HTTPS URI: " + uri);
        }
        return URI.create(uri.toString().endsWith("/") ? uri.toString() : uri + "/");
    }

    private static final class MavenServices implements Services {
        private final Path vendor;
        private final List<URI> repositories;
        private final fetch.Session session;

        private MavenServices(Path vendor, List<URI> repositories, fetch.Session session) {
            this.vendor = vendor;
            this.repositories = repositories;
            this.session = session;
        }

        @Override
        public List<String> resolve(String coordinate) throws Exception {
            return new Maven.Resolver(session, repositories).resolve(Coordinate.parse(coordinate)).stream()
                    .map(Coordinate::text).toList();
        }

        @Override
        public Download download(String coordinate, String kind, Consumer<Event> events) throws Exception {
            var artifact = Artifact.of(Coordinate.parse(coordinate), kind);
            var target = vendor.resolve(artifact.file());
            if (Files.isRegularFile(target)) {
                events.accept(Event.cached(artifact.label(), target.toString()));
                return Download.cached(target.toString());
            }

            Files.createDirectories(target.getParent());
            events.accept(Event.start(artifact.label(), -1));
            Exception last = null;
            for (var repository : repositories) {
                var uri = artifact.coordinate().uri(repository);
                try (var response = session.get(uri).timeout(Duration.ofMinutes(2)).send()) {
                    if (response.status() == 404) continue;
                    response.requireSuccess();
                    write(response, artifact, target, events);
                    return Download.downloaded(target.toString());
                } catch (HttpException missingOrBroken) {
                    last = missingOrBroken;
                    if (missingOrBroken.status() != 404) break;
                } catch (Exception failure) {
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
    }

    private static void resolve(Effect effect, Effect.Emitter emit, Services services, Progress progress) {
        var coordinate = Coordinate.parse(effect.string("coordinate", ""));
        progress.publish(Event.resolve(coordinate.text()));
        try {
            var resolved = services.resolve(coordinate.text());
            progress.publish(Event.resolved(coordinate.text(), resolved.size()));
            emit.emit(Message.of(RESOLVED).with("coordinate", coordinate.text())
                    .with("artifacts", Json.Array.strings(resolved)));
        } catch (Exception failure) {
            var reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            progress.publish(Event.resolveFailed(coordinate.text(), reason));
            emit.emit(Message.of(RESOLUTION_FAILED).with("coordinate", coordinate.text()).with("reason", reason));
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
            var reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            progress.publish(Event.failed(artifact.label(), reason));
            emit.emit(Message.of(FAILED).with("coordinate", artifact.label()).with("reason", reason));
        }
    }

    private static void write(Response response, Artifact artifact, Path target,
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
            events.accept(Event.done(artifact.label(), bytes, target.toString()));
        } finally {
            Files.deleteIfExists(temporary);
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

    static record Coordinate(String group, String artifact, String version, String classifier) {
        static Coordinate parse(String text) {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("empty Maven coordinate");
            var parts = text.split(":", -1);
            if (parts.length != 3 && parts.length != 4)
                throw new IllegalArgumentException("Maven coordinate must be group:artifact:version[:classifier]: " + text);
            for (var part : parts) {
                if (part.isEmpty() || !part.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_'))
                    throw new IllegalArgumentException("invalid Maven coordinate: " + text);
            }
            return new Coordinate(parts[0], parts[1], parts[2], parts.length == 4 ? parts[3] : "");
        }

        String text() {
            return group + ":" + artifact + ":" + version + (classifier.isEmpty() ? "" : ":" + classifier);
        }

        String file() {
            return artifact + "-" + version + (classifier.isEmpty() ? "" : "-" + classifier) + ".jar";
        }

        URI uri(URI repository) {
            return repository.resolve(group.replace('.', '/') + "/" + artifact + "/" + version + "/" + file());
        }

        URI pomUri(URI repository) {
            return repository.resolve(group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                    + artifact + "-" + version + ".pom");
        }

        static Coordinate of(String group, String artifact, String version, String classifier) {
            return new Coordinate(group, artifact, version, classifier);
        }

        Coordinate withoutClassifier() {
            return new Coordinate(group, artifact, version, "");
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

        private Coordinator(List<Coordinate> roots) {
            this.roots = roots;
        }

        @Override
        public String type() {
            return COORDINATOR;
        }

        @Override
        public Application<Job> instantiate(Address self) {
            return Application.of(Job.waiting(roots))
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
            var effects = state.roots().stream()
                    .map(root -> Effect.of(RESOLVE).with("coordinate", root.text()))
                    .toList();
            return new Step<>(state.begin(), effects);
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
