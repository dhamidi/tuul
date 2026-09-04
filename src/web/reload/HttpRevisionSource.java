package web.reload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import json.Json;
import web.Handler;
import web.Request;
import web.Response;
import web.Responses;
import web.Status;
import web.uploads.Limits;
import web.uploads.Multipart;
import web.uploads.Part;
import web.uploads.UploadException;
import reload.Revision;
import reload.RevisionSource;

/// Accepts a multipart revision and submits it to a [RevisionSource] callback.
///
/// The request contains one `manifest` field and file parts. A file's
/// `filename` is its relative entry name; it is validated before use. The
/// manifest is a JSON object with a `rootModule` and a `modules` array. Each
/// module has a name, a staging-root path, a descriptor, source paths, and
/// optional resource paths. It may also contain a `dependencies` array and an
/// `identity` SHA-256 value. Every listed entry must arrive exactly once, and
/// no extra file is accepted. Files are streamed into a server-created
/// staging directory, then submitted as a normal named-module [Revision].
/// This type never loads or activates Java classes.
public final class HttpRevisionSource implements RevisionSource {

    private static final String MANIFEST = "manifest";
    private static final String FILE = "file";

    private final Path staging;
    private final Limits limits;
    private final RevisionSubmissionPolicy policy;
    private final Object monitor = new Object();
    private final Set<Path> submitted = new HashSet<>();
    private Consumer<Revision> consumer;
    private boolean closed;

    /// Creates a source with explicit staging, upload limits, and authorization.
    /// A null argument throws `NullPointerException`.
    public HttpRevisionSource(Path staging, Limits limits, RevisionSubmissionPolicy policy) {
        this.staging = Objects.requireNonNull(staging, "staging").toAbsolutePath().normalize();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /// Creates a source with default upload limits and explicit authorization.
    /// A null staging path or policy throws `NullPointerException`.
    public HttpRevisionSource(Path staging, RevisionSubmissionPolicy policy) {
        this(staging, Limits.DEFAULT, policy);
    }

    /// Returns the handler to mount at the deployment endpoint.
    public Handler handler() {
        return this::handle;
    }

    /// Binds the callback that receives each valid revision. A second call or
    /// a call after [#close] throws `IllegalStateException`.
    @Override
    public void start(Consumer<Revision> submit) {
        Objects.requireNonNull(submit, "submit");
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("revision source is closed");
            if (consumer != null) throw new IllegalStateException("revision source already started");
            consumer = submit;
        }
    }

    /// Stops new uploads and removes every successfully submitted staging
    /// tree. The callback must finish using a revision before it returns, or
    /// copy the files that it needs elsewhere.
    @Override
    public void close() {
        Set<Path> cleanup;
        synchronized (monitor) {
            closed = true;
            consumer = null;
            cleanup = Set.copyOf(submitted);
            submitted.clear();
        }
        cleanup.forEach(HttpRevisionSource::remove);
    }

    private void handle(Request request, Response response) throws IOException {
        if (!request.method().equals("POST")) {
            response.status(Status.NOT_ALLOWED).header("Allow", "POST").close();
            return;
        }
        if (!allowed(request)) {
            Responses.empty(Status.FORBIDDEN, response);
            return;
        }
        Consumer<Revision> submit;
        synchronized (monitor) {
            if (closed || consumer == null) {
                Responses.text("revision source is not started\n", 503, response);
                return;
            }
            submit = consumer;
        }

        Path root = null;
        try {
            Files.createDirectories(staging);
            root = Files.createTempDirectory(staging, "revision-");
            var parsed = parse(request, root);
            submit.accept(parsed.revision());
            synchronized (monitor) {
                if (!closed) submitted.add(root);
            }
            if (closed()) remove(root);
            root = null;
            Responses.json(Json.Object.of("revision", parsed.revision().identity())
                    .with("status", "submitted"), response);
        } catch (UploadException refused) {
            remove(root);
            Responses.text(refused.getMessage() + "\n", refused.status(), response);
        } catch (IllegalArgumentException | IOException refused) {
            remove(root);
            Responses.text(message(refused) + "\n", Status.BAD_REQUEST, response);
        } catch (RuntimeException failure) {
            remove(root);
            throw failure;
        }
    }

    private boolean closed() {
        synchronized (monitor) { return closed; }
    }

    private boolean allowed(Request request) {
        try {
            return policy.allow(request);
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private Parsed parse(Request request, Path root) throws IOException {
        Json.Object manifest = null;
        var entries = new LinkedHashMap<String, Path>();
        try (var multipart = Multipart.of(request, limits)) {
            for (var next = multipart.next(); next.isPresent(); next = multipart.next()) {
                var part = next.get();
                if (!part.file() && part.name().equals(MANIFEST)) {
                    if (manifest != null) throw invalid("manifest was sent more than once");
                    manifest = manifest(part);
                    continue;
                }
                if (!part.file() || !part.name().equals(FILE)) {
                    throw invalid("a revision contains only one manifest and file parts named file");
                }
                var name = entry(part);
                if (entries.containsKey(name)) throw invalid("duplicate revision entry: " + name);
                var destination = root.resolve(name).normalize();
                if (!destination.startsWith(root)) throw invalid("revision entry is outside its staging root");
                Files.createDirectories(destination.getParent());
                try (var out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    part.body().transferTo(out);
                }
                entries.put(name, destination);
            }
        }
        if (manifest == null) throw invalid("revision has no manifest");
        return revision(manifest, entries, root);
    }

    private static Json.Object manifest(Part part) throws IOException {
        try {
            var value = Json.parse(part.text());
            if (!(value instanceof Json.Object object)) throw invalid("manifest must be a JSON object");
            return object;
        } catch (RuntimeException malformed) {
            if (malformed instanceof UploadException refusal) throw refusal;
            throw invalid("manifest is not valid JSON");
        }
    }

    private static String entry(Part part) {
        var raw = part.filename().orElseThrow(() -> invalid("a file part has no filename"));
        if (raw.isBlank() || raw.indexOf('\0') >= 0) throw invalid("a revision entry has an invalid name");
        var slash = raw.replace('\\', '/');
        if (slash.startsWith("/") || slash.matches("^[A-Za-z]:.*")) {
            throw invalid("absolute revision entries are not allowed: " + raw);
        }
        var path = Path.of(slash);
        for (var component : slash.split("/", -1)) {
            if (component.equals("..")) throw invalid("parent revision entries are not allowed: " + raw);
            if (component.isEmpty() || component.equals(".")) throw invalid("revision entry is not normalized: " + raw);
        }
        var normalized = path.normalize().toString().replace('\\', '/');
        if (normalized.isEmpty() || normalized.equals(".")) throw invalid("revision entry is empty");
        return normalized;
    }

    private static Parsed revision(Json.Object manifest, LinkedHashMap<String, Path> entries, Path root) {
        var rootModule = required(manifest, "rootModule");
        var modules = modules(manifest, entries, root);
        var dependencies = paths(manifest, "dependencies", entries, root);
        var all = new ArrayList<String>();
        for (var module : modules) {
            all.addAll(module.sourceNames());
            all.addAll(module.resourceNames());
        }
        all.addAll(names(manifest, "dependencies"));
        var expected = new HashSet<>(all);
        if (expected.size() != all.size()) throw invalid("manifest contains duplicate entries");
        if (!expected.equals(entries.keySet())) {
            var missing = new HashSet<>(expected);
            missing.removeAll(entries.keySet());
            var extra = new HashSet<>(entries.keySet());
            extra.removeAll(expected);
            throw invalid("manifest entries do not match uploaded files (missing=" + missing + ", extra=" + extra + ")");
        }
        if (modules.isEmpty()) throw invalid("manifest must list at least one module");
        try {
            var sourceModules = modules.stream().map(module -> module.revision(root, entries)).toList();
            var revision = Revision.from(rootModule, sourceModules, dependencies);
            var declared = manifest.string("identity", "");
            if (!declared.isEmpty()) {
                if (!declared.matches("[0-9a-fA-F]{64}")) throw invalid("manifest identity is not a SHA-256 digest");
                if (!declared.equalsIgnoreCase(revision.identity())) throw invalid("manifest identity does not match its files");
            }
            return new Parsed(revision, root);
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read staged revision", failure);
        }
    }

    private static List<ModuleSpec> modules(Json.Object manifest,
            LinkedHashMap<String, Path> entries, Path root) {
        var value = manifest.get("modules");
        if (!(value instanceof Json.Array(var items))) throw invalid("manifest field modules must be an array");
        var found = new ArrayList<ModuleSpec>();
        var names = new HashSet<String>();
        for (var item : items) {
            if (!(item instanceof Json.Object module)) throw invalid("manifest modules must contain objects");
            var name = required(module, "name");
            if (!names.add(name)) throw invalid("manifest contains duplicate module: " + name);
            var moduleRoot = normalized(required(module, "root"));
            var descriptor = normalized(required(module, "descriptor"));
            var sources = namesRequired(module, "sources");
            if (!sources.contains(descriptor)) throw invalid("module descriptor is not listed as a source: " + name);
            var resources = namesOptional(module, "resources");
            var all = new ArrayList<String>();
            all.add(descriptor);
            all.addAll(sources);
            all.addAll(resources);
            for (var path : all) {
                if (!path.equals(moduleRoot) && !path.startsWith(moduleRoot + "/")) {
                    throw invalid("module entry is outside its root: " + path);
                }
            }
            found.add(new ModuleSpec(name, moduleRoot, descriptor, sources, resources));
        }
        return List.copyOf(found);
    }

    private static List<String> namesRequired(Json.Object object, String key) {
        var value = object.get(key);
        if (!(value instanceof Json.Array(var items))) throw invalid("module field " + key + " must be an array");
        return names(items, key);
    }

    private static List<String> namesOptional(Json.Object object, String key) {
        var value = object.get(key);
        if (value == null) return List.of();
        if (!(value instanceof Json.Array(var items))) throw invalid("module field " + key + " must be an array");
        return names(items, key);
    }

    private static List<String> names(List<Json> values, String key) {
        var names = new ArrayList<String>();
        for (var item : values) {
            if (!(item instanceof Json.Str(var name))) throw invalid("manifest field " + key + " must contain strings");
            names.add(normalized(name));
        }
        return List.copyOf(names);
    }

    private static List<Path> paths(Json.Object manifest, String key, LinkedHashMap<String, Path> entries, Path root) {
        return names(manifest, key).stream().map(entries::get).toList();
    }

    private static List<String> names(Json.Object manifest, String key) {
        var value = manifest.get(key);
        if (!(value instanceof Json.Array(var items))) {
            if (key.equals("dependencies")) return List.of();
            throw invalid("manifest field " + key + " must be an array");
        }
        var names = new ArrayList<String>();
        for (var item : items) {
            if (!(item instanceof Json.Str(var name))) throw invalid("manifest field " + key + " must contain strings");
            names.add(normalized(name));
        }
        return List.copyOf(names);
    }

    private record ModuleSpec(String name, String rootName, String descriptorName,
            List<String> sourceNames, List<String> resourceNames) {
        private Revision.SourceModule revision(Path staging, LinkedHashMap<String, Path> entries) {
            var moduleRoot = staging.resolve(rootName).normalize();
            var descriptor = entries.get(descriptorName);
            var sources = sourceNames.stream().map(entries::get).toList();
            var resources = resourceNames.stream().map(path -> {
                var logical = moduleRoot.relativize(staging.resolve(path).normalize()).toString().replace('\\', '/');
                return new Revision.ResourceEntry(logical, entries.get(path));
            }).toList();
            return new Revision.SourceModule(name, moduleRoot, descriptor, sources, resources);
        }
    }

    private static String required(Json.Object manifest, String key) {
        var value = manifest.get(key);
        if (!(value instanceof Json.Str(var text)) || text.isBlank()) throw invalid("manifest needs " + key);
        return text;
    }

    private static String normalized(String raw) {
        if (raw == null || raw.isBlank()) throw invalid("manifest contains an empty entry");
        if (raw.indexOf('\0') >= 0) throw invalid("manifest contains an invalid entry");
        var slash = raw.replace('\\', '/');
        if (slash.startsWith("/") || slash.matches("^[A-Za-z]:.*")) throw invalid("manifest contains an absolute entry: " + raw);
        for (var component : slash.split("/", -1)) {
            if (component.equals("..")) throw invalid("manifest contains a parent entry: " + raw);
            if (component.isEmpty() || component.equals(".")) throw invalid("manifest entry is not normalized: " + raw);
        }
        return Path.of(slash).normalize().toString().replace('\\', '/');
    }

    private static UploadException invalid(String message) {
        return new UploadException(message, Status.BAD_REQUEST);
    }

    private static String message(Exception failure) {
        return failure.getMessage() == null ? "invalid revision upload" : failure.getMessage();
    }

    private static void remove(Path root) {
        if (root == null) return;
        try (var files = Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private record Parsed(Revision revision, Path root) {}
}
