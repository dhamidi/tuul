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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
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
    private static final long PROGRESS_BYTES = 64 * 1024;
    private static final long PROGRESS_NANOS = Duration.ofMillis(100).toNanos();

    private Add() {}

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
        var roots = coordinates.stream().map(Coordinate::parse).distinct().toList();
        if (roots.isEmpty()) throw new IOException("tuul add needs at least one dependency");
        var sources = repositories == null || repositories.isEmpty() ? List.of(CENTRAL)
                : repositories.stream().map(Add::repository).distinct().toList();
        Files.createDirectories(layout.vendor());

        var events = new Events();
        var renderer = new Renderer(out, mode);
        events.subscribe(renderer);
        try (var fetch = Fetch.virtualThreads();
                var session = fetch.session().redirects(Redirects.BROWSER);
                var system = ActorSystem.named("tuul-add")
                        .define(new Coordinator(roots), Spawn.ephemeral().effects(ACTOR_EFFECT_DEADLINE))) {
            var root = Address.of(COORDINATOR, "run");
            system.effect(DOWNLOAD, (effect, emit) -> download(effect, emit, layout.vendor(), sources,
                    session, events));
            system.effect(RESOLVE, (effect, emit) -> resolve(effect, emit, sources, session, events));
            system.tell(root, Message.of("add.start"));
            var answer = system.ask(root, Message.of(RESULT), ASK_DEADLINE).join();
            var result = result(answer);
            events.publish(Event.complete(result.downloaded().size(), result.cached().size(), result.failed().size()));
            events.close();
            return result;
        } catch (RuntimeException failure) {
            events.close();
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

    private static void resolve(Effect effect, Effect.Emitter emit, List<URI> repositories,
            fetch.Session session, Events events) {
        var coordinate = Coordinate.parse(effect.string("coordinate", ""));
        events.publish(Event.resolve(coordinate.text()));
        try {
            var resolved = new Maven.Resolver(session, repositories).resolve(coordinate);
            events.publish(Event.resolved(coordinate.text(), resolved.size()));
            emit.emit(Message.of(RESOLVED).with("coordinate", coordinate.text())
                    .with("artifacts", Json.Array.strings(resolved.stream().map(Coordinate::text).toList())));
        } catch (Exception failure) {
            var reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            events.publish(Event.resolveFailed(coordinate.text(), reason));
            emit.emit(Message.of(RESOLUTION_FAILED).with("coordinate", coordinate.text()).with("reason", reason));
        }
    }

    private static void download(Effect effect, Effect.Emitter emit, Path vendor,
            List<URI> repositories, fetch.Session session, Events events) throws Exception {
        var artifact = Artifact.of(Coordinate.parse(effect.string("coordinate", "")),
                effect.string("kind", "binary"));
        var coordinate = artifact.coordinate();
        var target = vendor.resolve(artifact.file());
        var optional = artifact.optional();
        if (Files.isRegularFile(target)) {
            events.publish(Event.cached(artifact.label(), target.toString()));
            emit.emit(Message.of(CACHED).with("coordinate", artifact.label())
                    .with("target", target.toString()));
            return;
        }

        Files.createDirectories(target.getParent());
        events.publish(Event.start(artifact.label(), -1));
        var found = false;
        Exception last = null;
        for (var repository : repositories) {
            var uri = coordinate.uri(repository);
            try (var response = session.get(uri).timeout(Duration.ofMinutes(2)).send()) {
                if (response.status() == 404) continue;
                response.requireSuccess();
                found = true;
                write(response, artifact, target, events);
                emit.emit(Message.of(DOWNLOADED).with("coordinate", artifact.label())
                        .with("target", target.toString()));
                return;
            } catch (HttpException missingOrBroken) {
                last = missingOrBroken;
                if (missingOrBroken.status() != 404) break;
            } catch (Exception failure) {
                last = failure;
                break;
            }
        }
        if (!found) {
            var reason = last == null ? "artifact was not found in the configured repositories"
                    : last.getMessage() == null ? last.toString() : last.getMessage();
            if (optional) {
                events.publish(Event.optionalMissing(artifact.label(), reason));
                emit.emit(Message.of(OPTIONAL_MISSING).with("coordinate", artifact.label()).with("reason", reason));
            } else {
                events.publish(Event.failed(artifact.label(), reason));
                emit.emit(Message.of(FAILED).with("coordinate", artifact.label()).with("reason", reason));
            }
        }
    }

    private static void write(Response response, Artifact artifact, Path target,
            Events events) throws IOException {
        var total = length(response);
        events.publish(Event.progress(artifact.label(), 0, total));
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
                        events.publish(Event.progress(artifact.label(), bytes, total));
                        announced = bytes;
                        announcedAt = now;
                    }
                }
            }
            move(temporary, target);
            events.publish(Event.done(artifact.label(), bytes, target.toString()));
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
            Map<String, Failure> resolutionFailures, boolean started, boolean downloadsStarted) {
        Job {
            roots = List.copyOf(roots);
            artifacts = List.copyOf(artifacts);
            outcomes = Map.copyOf(outcomes);
            resolutions = Map.copyOf(resolutions);
            resolutionFailures = Map.copyOf(resolutionFailures);
        }

        static Job waiting(List<Coordinate> roots) {
            return new Job(roots, List.of(), Map.of(), Map.of(), Map.of(), false, false);
        }

        Job begin() {
            return new Job(roots, artifacts, outcomes, resolutions, resolutionFailures, true, downloadsStarted);
        }

        Job outcome(Outcome outcome) {
            var next = new LinkedHashMap<>(outcomes);
            next.putIfAbsent(outcome.coordinate(), outcome);
            return new Job(roots, artifacts, next, resolutions, resolutionFailures, started, downloadsStarted);
        }

        Job resolved(Coordinate root, List<Coordinate> found) {
            var next = new LinkedHashMap<>(resolutions);
            next.putIfAbsent(root.text(), found);
            var all = new LinkedHashMap<String, Coordinate>();
            for (var values : next.values()) for (var coordinate : values) {
                all.putIfAbsent(coordinate.text(), coordinate);
            }
            return new Job(roots, List.copyOf(all.values()), outcomes, next, resolutionFailures,
                    started, downloadsStarted);
        }

        Job resolutionFailure(Coordinate root, String reason) {
            var next = new LinkedHashMap<>(resolutionFailures);
            next.putIfAbsent(root.text(), new Failure(root.text(), reason));
            return new Job(roots, artifacts, outcomes, resolutions, next, started, downloadsStarted);
        }

        Job beginDownloads() {
            return new Job(roots, artifacts, outcomes, resolutions, resolutionFailures, started, true);
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
            var effects = scheduled(state.artifacts()).stream()
                    .map(artifact -> Effect.of(DOWNLOAD).with("coordinate", artifact.coordinate().text())
                            .with("kind", artifact.kind()))
                    .toList();
            return new Step<>(state.beginDownloads(), effects);
        }

        private static Step<Job> downloaded(Job state, Message message) {
            return Step.of(state.outcome(new Outcome(message.string("coordinate", ""),
                    message.string("target", ""), "", true, false, false)));
        }

        private static Step<Job> cached(Job state, Message message) {
            return Step.of(state.outcome(new Outcome(message.string("coordinate", ""),
                    message.string("target", ""), "", false, true, false)));
        }

        private static Step<Job> optionalMissing(Job state, Message message) {
            return Step.of(state.outcome(new Outcome(message.string("coordinate", ""), "",
                    message.string("reason", "supplement was not found"), false, false, true)));
        }

        private static Step<Job> failed(Job state, Message message) {
            return Step.of(state.outcome(new Outcome(message.string("coordinate", ""), "",
                    message.string("reason", "unknown failure"), false, false, false)));
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

    private static final class Events implements Flow.Publisher<Event>, AutoCloseable {
        private final CopyOnWriteArrayList<Subscription> subscribers = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        @Override
        public void subscribe(Flow.Subscriber<? super Event> subscriber) {
            if (subscriber == null) throw new NullPointerException("subscriber");
            var subscription = new Subscription(subscriber);
            if (closed) {
                subscriber.onSubscribe(subscription);
                subscriber.onComplete();
                return;
            }
            subscribers.add(subscription);
            subscriber.onSubscribe(subscription);
        }

        void publish(Event event) {
            if (closed) return;
            for (var subscriber : subscribers) subscriber.publish(event);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            for (var subscriber : subscribers) subscriber.complete();
            subscribers.clear();
        }

        private static final class Subscription implements Flow.Subscription {
            private final Flow.Subscriber<? super Event> subscriber;
            private volatile boolean cancelled;
            private volatile long demand;

            Subscription(Flow.Subscriber<? super Event> subscriber) {
                this.subscriber = subscriber;
            }

            @Override
            public void request(long count) {
                if (count <= 0) {
                    cancel();
                    subscriber.onError(new IllegalArgumentException("non-positive demand"));
                    return;
                }
                demand = demand == Long.MAX_VALUE || count == Long.MAX_VALUE
                        || Long.MAX_VALUE - demand < count ? Long.MAX_VALUE : demand + count;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }

            void publish(Event event) {
                if (cancelled || demand == 0) return;
                if (demand != Long.MAX_VALUE) demand--;
                try {
                    subscriber.onNext(event);
                } catch (RuntimeException failure) {
                    cancel();
                    subscriber.onError(failure);
                }
            }

            void complete() {
                if (!cancelled) subscriber.onComplete();
            }
        }
    }

    private static final class Renderer implements Flow.Subscriber<Event> {
        private final Writer out;
        private final Mode mode;
        private final List<String> order = new ArrayList<>();
        private final Map<String, Event> latest = new LinkedHashMap<>();
        private Flow.Subscription subscription;
        private int drawn;
        private boolean terminal;

        Renderer(Writer out, Mode mode) {
            this.out = out;
            this.mode = mode;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public synchronized void onNext(Event event) {
            if (terminal) return;
            latest.put(event.coordinate(), event);
            try {
                if (mode == Mode.TTY) {
                    if (!event.type().startsWith("resolve") && !event.type().equals("complete")
                            && !order.contains(event.coordinate())) {
                        order.add(event.coordinate());
                    }
                    if (!order.isEmpty()) render();
                }
                else line(event);
            } catch (IOException failure) {
                subscription.cancel();
            }
        }

        @Override
        public synchronized void onError(Throwable failure) {
            // The renderer is the last consumer. An unwritable stdout has no
            // useful recovery path, and the command still reports its result.
        }

        @Override
        public synchronized void onComplete() {
            if (terminal) return;
            terminal = true;
            try {
                if (mode == Mode.TTY && drawn > 0) {
                    out.write("\n");
                    out.flush();
                }
            } catch (IOException ignored) {}
        }

        private void line(Event event) throws IOException {
            if (event.type().equals("complete")) {
                out.write("add.complete " + clean(event.reason()) + "\n");
                out.flush();
                return;
            }
            out.write("add." + event.type() + " " + event.coordinate());
            switch (event.type()) {
                case "start", "progress" -> out.write(" " + event.bytes() + "/" + event.total());
                case "done", "cached" -> out.write(" " + event.target());
                case "resolved" -> out.write(" " + event.bytes() + " artifacts");
                case "resolve-failed", "failed", "optional-missing" -> out.write(" " + clean(event.reason()));
                default -> {}
            }
            out.write("\n");
            out.flush();
        }

        private void render() throws IOException {
            if (drawn > 0) out.write("\033[" + drawn + "A");
            for (var artifact : order) {
                var event = latest.get(artifact);
                out.write("\r\033[2K");
                out.write(bar(artifact, event));
                out.write("\n");
            }
            drawn = order.size();
            out.flush();
        }

        private static String bar(String coordinate, Event event) {
            if (event == null) return "[                    ] " + coordinate + " waiting";
            if (event.type().equals("failed")) return "[--------------------] " + coordinate + " failed: " + clean(event.reason());
            if (event.type().equals("optional-missing")) {
                return "[....................] " + coordinate + " missing (optional)";
            }
            if (event.type().equals("cached")) return "[####################] " + coordinate + " cached";
            if (event.type().equals("done")) return "[####################] " + coordinate + " done";
            var total = event.total();
            if (total <= 0) return "[????????????????????] " + coordinate + " " + bytes(event.bytes());
            var complete = Math.min(20, (int) (event.bytes() * 20 / total));
            return "[" + "#".repeat(complete) + "-".repeat(20 - complete) + "] "
                    + coordinate + " " + bytes(event.bytes()) + "/" + bytes(total);
        }

        private static String bytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return "%.1f KiB".formatted(bytes / 1024d);
            if (bytes < 1024 * 1024 * 1024) return "%.1f MiB".formatted(bytes / (1024d * 1024));
            return "%.1f GiB".formatted(bytes / (1024d * 1024 * 1024));
        }

        private static String clean(String text) {
            return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
        }
    }
}
