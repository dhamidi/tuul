package settings;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import json.Json;
import jsonschema.Output;
import jsonschema.Schema;
import jsonschema.Store;

/// Composes component settings and validates runtime values.
///
/// A configuration owns the installed namespaces, their schemas, their secret
/// paths, and their initializer declarations. It does not create an actor,
/// read a source, or retain a mutable document.
///
/// Call [#of] during application assembly. Call [#values] with the JSON
/// document that the application wants to use. The returned [Values] is an
/// immutable runtime snapshot.
///
/// ```
/// var configuration = Configuration.of(images, cable);
/// var runtime = configuration.values(Json.Object.of()
///         .with("images", imageValues)
///         .with("cable", cableValues));
/// ```
public final class Configuration {

    static final long MAX_DOCUMENT = 8L * 1024L * 1024L;

    record Initializer(String pointer, List<String> path, Initial initial) {}

    record ContributionInfo(String namespace, Schema schema,
                             List<String> secrets, List<Initializer> initializers) {}

    record Located(boolean present, Json value) {}

    private final Map<String, ContributionInfo> contributions;
    private final Map<String, Initializer> initializers;
    private final List<Initializer> orderedInitializers;

    private Configuration(List<ContributionInfo> contributions) {
        this.contributions = new LinkedHashMap<>();
        contributions.stream().sorted(java.util.Comparator.comparing(ContributionInfo::namespace))
                .forEach(info -> this.contributions.put(info.namespace(), info));
        var declared = new HashMap<String, Initializer>();
        this.contributions.values().forEach(info -> info.initializers().forEach(initial ->
                declared.put(initial.pointer(), initial)));
        this.initializers = Map.copyOf(declared);
        this.orderedInitializers = this.initializers.values().stream()
                .sorted(java.util.Comparator.comparing(Initializer::pointer)).toList();
    }

    /// Composes the installed contributions into one immutable configuration.
    ///
    /// The method sorts namespaces by name. It compiles every schema with the
    /// built-in draft 2020-12 store. It rejects duplicate namespaces, invalid
    /// root schemas, duplicate initializer pointers, and invalid initializer
    /// or secret declarations before values are used.
    ///
    /// Passing no contributions creates a configuration with no namespaces.
    public static Configuration of(Contribution... contributions) {
        Objects.requireNonNull(contributions, "contributions");
        var names = new HashSet<String>();
        var infos = new ArrayList<ContributionInfo>();
        for (var contribution : contributions) {
            Objects.requireNonNull(contribution, "contribution");
            if (!names.add(contribution.namespace())) {
                throw new IllegalArgumentException("duplicate settings namespace: " + contribution.namespace());
            }
            if (!(contribution.schema() instanceof Json.Object schemaObject)
                    || !(schemaObject.get("type") instanceof Json.Str(var type))
                    || !type.equals("object")) {
                throw new IllegalArgumentException("the schema for " + contribution.namespace()
                        + " must be an object with type object");
            }
            var schema = Store.of().compile(contribution.schema());
            var secretPaths = contribution.secrets().stream()
                    .map(relative -> Pointer.join(contribution.namespace(), relative))
                    .toList();
            var declarations = new ArrayList<Initializer>();
            for (var declaration : contribution.initializers()) {
                var path = Pointer.relative(declaration.relative());
                var pointer = Pointer.join(contribution.namespace(), declaration.relative());
                var initial = declaration.initial();
                if (secretPaths.stream().map(Pointer::absolute).anyMatch(secret ->
                        Pointer.overlaps(secret, Pointer.absolute(pointer)))) {
                    if (!initial.constant() || !Secret.isReference(initial.value())) {
                        throw new IllegalArgumentException("only a secret reference can initialize " + pointer);
                    }
                    if (secretPaths.stream().map(Pointer::absolute).anyMatch(secret ->
                            Pointer.prefix(Pointer.absolute(pointer), secret)
                                    && !Pointer.absolute(pointer).equals(secret))) {
                        throw new IllegalArgumentException("an initializer cannot write below secret path " + pointer);
                    }
                }
                var fullPath = new ArrayList<String>();
                fullPath.add(contribution.namespace());
                fullPath.addAll(path);
                declarations.add(new Initializer(pointer, List.copyOf(fullPath), initial));
            }
            for (var secret : secretPaths) {
                var parsed = Pointer.absolute(secret);
                if (!parsed.getFirst().equals(contribution.namespace())) {
                    throw new IllegalArgumentException("secret path is outside " + contribution.namespace());
                }
            }
            if (declarations.stream().map(Initializer::pointer).distinct().count() != declarations.size()) {
                throw new IllegalArgumentException("duplicate initializer pointer in " + contribution.namespace());
            }
            infos.add(new ContributionInfo(contribution.namespace(), schema,
                    secretPaths, List.copyOf(declarations)));
        }
        var all = infos.stream().flatMap(info -> info.initializers().stream()).toList();
        if (all.stream().map(Initializer::pointer).distinct().count() != all.size()) {
            throw new IllegalArgumentException("duplicate settings initializer pointer");
        }
        return new Configuration(infos);
    }

    /// Validates one runtime document and freezes it in a [Values] snapshot.
    ///
    /// The document must be a JSON object containing only installed namespaces.
    /// Present namespaces must satisfy their schemas. Secret paths must contain
    /// secret references. A missing namespace remains missing, so an application
    /// can validate a partial document before it resolves all sources.
    ///
    /// The returned snapshot never changes. This method does not read an
    /// initializer or create an actor.
    public Values values(Json.Object document) {
        Objects.requireNonNull(document, "document");
        for (var entry : document.fields().entrySet()) {
            var info = contributions.get(entry.getKey());
            if (info == null) throw new IllegalArgumentException("unknown settings namespace: " + entry.getKey());
            if (!info.schema().validate(entry.getValue()).valid()) {
                throw new IllegalArgumentException("invalid settings value at /" + entry.getKey());
            }
            checkSecrets(info, entry.getValue(), List.of(entry.getKey()));
        }
        if (size(document) > MAX_DOCUMENT) throw new IllegalArgumentException("settings document is too large");
        return new Values(this, document);
    }

    Map<String, Initializer> initializers() {
        return initializers;
    }

    List<Initializer> orderedInitializers() {
        return orderedInitializers;
    }

    List<String> owned(String pointer) {
        var path = Pointer.absolute(pointer);
        if (!contributions.containsKey(path.getFirst())) throw new IllegalArgumentException("unknown settings namespace");
        return path;
    }

    void checkSecrets(List<String> path, Json value) {
        var info = contributions.get(path.getFirst());
        for (var secret : info.secrets()) {
            var secretPath = Pointer.absolute(secret);
            if (secretPath.equals(path)) {
                if (!Secret.isReference(value)) throw new IllegalArgumentException("secret value must be a reference");
            } else if (Pointer.prefix(secretPath, path)) {
                throw new IllegalArgumentException("a write below a secret path is not allowed");
            } else if (Pointer.prefix(path, secretPath)) {
                var supplied = locate(value, secretPath.subList(path.size(), secretPath.size()));
                if (supplied.present() && !Secret.isReference(supplied.value())) {
                    throw new IllegalArgumentException("secret value must be a reference");
                }
            }
        }
    }

    boolean validCandidate(Json values, List<String> path) {
        if (!(values instanceof Json.Object object)) return false;
        var candidate = object.get(path.getFirst());
        var info = contributions.get(path.getFirst());
        return (candidate == null || info.schema().validate(candidate).valid()) && size(values) <= MAX_DOCUMENT;
    }

    Json validation(Json.Object values, List<String> path) {
        var info = contributions.get(path.getFirst());
        var candidate = values.get(path.getFirst());
        var output = candidate == null ? Output.ok() : info.schema().validate(candidate);
        var text = new StringWriter();
        try {
            output.basic(text);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return Json.parse(text.toString());
    }

    private void checkSecrets(ContributionInfo info, Json value, List<String> prefix) {
        for (var secret : info.secrets()) {
            var path = Pointer.absolute(secret);
            var relative = path.subList(1, path.size());
            var found = locate(value, relative);
            if (found.present() && !Secret.isReference(found.value())) {
                throw new IllegalArgumentException("secret value must be a reference at " + Pointer.render(prefix) + secret);
            }
        }
    }

    static Located locate(Json value, List<String> path) {
        var pointer = json.Pointer.root();
        for (var token : path) pointer = pointer.append(token);
        var found = pointer.find(value);
        return found.map(json -> new Located(true, json)).orElseGet(() -> new Located(false, null));
    }

    private static long size(Json value) {
        var writer = new CountingWriter();
        try {
            value.write(writer);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return writer.bytes;
    }

    private static final class CountingWriter extends java.io.Writer {
        private long bytes;

        @Override public void write(char[] value, int offset, int length) {
            bytes += new String(value, offset, length).getBytes(StandardCharsets.UTF_8).length;
        }

        @Override public void write(String value, int offset, int length) {
            bytes += value.substring(offset, offset + length).getBytes(StandardCharsets.UTF_8).length;
        }

        @Override public void write(int value) {
            bytes += String.valueOf((char) value).getBytes(StandardCharsets.UTF_8).length;
        }

        @Override public void flush() {}

        @Override public void close() {}
    }
}
