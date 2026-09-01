package jsonschema;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import json.Json;
import json.Pointer;

/// A schema from a [Store], ready to look at instances.
///
/// A schema is compiled once and used many times. Compiling reads the keyword
/// set of every resource it can reach and resolves every reference in them, so
/// a schema that exists is a schema that runs. Validating never adds anything
/// to the store and never fetches anything.
public final class Schema {

    private final Store store;
    private final URI uri;
    private final Store.Located root;
    private final URI metaschema;
    private final Map<URI, Map<String, Keyword>> byResource = new HashMap<>();

    Schema(Store store, URI uri, Store.Located root) {
        this.store = store;
        this.uri = uri;
        this.root = root;
        var resource = store.resource(root.base());
        this.metaschema = resource != null && resource.metaschema() != null
                ? resource.metaschema()
                : Store.DRAFT_2020_12;
    }

    /// The URI this schema was compiled from.
    public URI uri() {
        return uri;
    }

    /// The meta-schema that decides which keywords this schema uses.
    public URI metaschema() {
        return metaschema;
    }

    public Output validate(Json instance) {
        return new Evaluator(store, this).run(root, instance);
    }

    /// The keywords a resource is evaluated with.
    ///
    /// A resource that declares no `$schema` uses the one the compiled root
    /// declared. That is what the specification means by a resource inheriting
    /// its parent's dialect, and it is why an embedded `$id` does not silently
    /// fall back to draft 2020-12.
    Map<String, Keyword> keywords(URI base) {
        return byResource.computeIfAbsent(base, at -> {
            var resource = store.resource(at);
            var declared = resource != null && resource.metaschema() != null ? resource.metaschema() : metaschema;
            return store.keywords(declared);
        });
    }

    /// Reads every reachable resource and refuses the schema when something is
    /// missing.
    ///
    /// The walk follows `$ref` and `$dynamicRef` across resources and stops at
    /// nodes it already saw, so a recursive schema is checked once. It also
    /// touches the keyword set of every resource, which is where a required
    /// vocabulary that this store does not know is refused.
    void check() {
        check(root.schema(), root.base(), root.pointer(), new HashSet<>());
    }

    private void check(Json node, URI at, String pointer, Set<URI> seen) {
        if (!(node instanceof Json.Object object)) return;
        var base = at;
        var local = pointer;
        if (object.get("$id") instanceof Json.Str(var id)) {
            base = Pointer.document(Pointer.resolve(base, id));
            local = "";
        }
        if (!seen.add(Pointer.absolute(base, local))) return;
        keywords(base);
        for (var name : List.of("$ref", "$dynamicRef")) {
            if (!(object.get(name) instanceof Json.Str(var reference))) continue;
            var target = Pointer.resolve(base, reference);
            var from = Pointer.absolute(base, Pointer.parse(local).append(name).toString()).toString();
            var found = store.resolve(target)
                    .orElseThrow(() -> SchemaException.unresolved(reference, target, from));
            check(found.schema(), found.base(), found.pointer(), seen);
        }
        var shapes = store.shapes();
        for (var field : object.fields().entrySet()) {
            var shape = shapes.get(field.getKey());
            if (shape == null) continue;
            var step = Pointer.parse(local).append(field.getKey()).toString();
            switch (shape) {
                case NONE -> {}
                case SCHEMA -> check(field.getValue(), base, step, seen);
                case SCHEMA_ARRAY -> {
                    if (field.getValue() instanceof Json.Array(var items)) {
                        for (var i = 0; i < items.size(); i++) check(items.get(i), base, step + "/" + i, seen);
                    }
                }
                case SCHEMA_MAP -> {
                    if (field.getValue() instanceof Json.Object(var members)) {
                        for (var member : members.entrySet()) {
                            check(member.getValue(), base, Pointer.parse(step).append(member.getKey()).toString(), seen);
                        }
                    }
                }
            }
        }
    }
}
