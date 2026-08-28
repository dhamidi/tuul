package jsonschema;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import json.Json;

/// Every schema, vocabulary and format that a compilation is allowed to see.
///
/// A store is filled by hand and by nothing else. It never opens a socket, and
/// no flag in this package makes it open one. A `$ref` resolves against what
/// the caller put in the store, and a `$ref` that finds nothing throws
/// [SchemaException] naming the URI it wanted and the place it came from. That
/// is the whole rule, and it is the reason a schema that compiles here compiles
/// the same way on a machine with no network.
///
/// ```java
/// var store = Store.of();                          // the 2020-12 meta-schemas are already in it
/// store.add(Json.parse(text));                     // takes its URI from $id
/// store.add(URI.create("https://x/person"), node); // or takes the one you give it
/// var schema = store.compile(URI.create("https://x/person"));
/// ```
///
/// A store is mutable and the adding methods return it, so registration reads
/// as one chain. Register vocabularies before schemas: the walk that indexes a
/// document asks the registered vocabularies which values are schemas.
public final class Store {

    /// The meta-schema every schema in this package uses unless it says otherwise.
    public static final URI DRAFT_2020_12 = URI.create("https://json-schema.org/draft/2020-12/schema");

    /// The base URI a schema gets when it has no `$id`.
    ///
    /// It has to be a real hierarchical URI, because a relative `$ref` inside
    /// the schema resolves against it. RFC 2606 reserves `.invalid`, so this
    /// host can never name a machine, and nothing here would fetch it anyway.
    private static final String ANONYMOUS = "https://tuul.invalid/anonymous/";

    /// Where a URI reference landed: the resource that holds the schema, the
    /// pointer to it inside that resource, and the schema itself.
    ///
    /// The base and the pointer travel with the node because the output needs
    /// them. Following a `$ref` changes both, and an `absoluteKeywordLocation`
    /// that still named the old resource would point at nothing.
    public record Located(URI base, String pointer, Json schema) {}

    private final Map<URI, Resource> resources = new LinkedHashMap<>();
    private final Map<URI, Vocabulary> vocabularies = new LinkedHashMap<>();
    private final Map<String, Format> formats = new LinkedHashMap<>();
    private final Map<URI, Map<String, Keyword>> byMetaschema = new HashMap<>();
    private final Map<String, Keyword.Shape> shapes = new HashMap<>();
    private int anonymous;

    private Store() {}

    /// A store that knows draft 2020-12: its seven vocabularies, its
    /// meta-schemas, and the formats of the format-assertion vocabulary.
    public static Store of() {
        var store = empty();
        Vocabularies.all().forEach(store::vocabulary);
        Formats.all().forEach(store::format);
        Meta.load(store);
        return store;
    }

    /// A store that knows nothing at all. For a caller who replaces draft
    /// 2020-12 rather than extends it.
    public static Store empty() {
        return new Store();
    }

    /// Registers a vocabulary, replacing any vocabulary with the same URI.
    public Store vocabulary(Vocabulary vocabulary) {
        vocabularies.put(vocabulary.uri(), vocabulary);
        vocabulary.keywords().forEach(keyword -> shapes.put(keyword.name(), keyword.shape()));
        byMetaschema.clear();
        return this;
    }

    /// Registers a format, replacing any format with the same name.
    public Store format(Format format) {
        formats.put(format.name(), format);
        return this;
    }

    /// The format of that name, or null. Unknown formats never fail an
    /// instance, so the caller of this is expected to accept a null.
    public Format format(String name) {
        return formats.get(name);
    }

    /// Makes `format` assert instead of only annotate.
    ///
    /// The specification puts assertion in its own vocabulary, and this store
    /// knows that one as well, so a caller with their own meta-schema can name
    /// `.../vocab/format-assertion` and get the same behaviour. This method is
    /// the short way for a caller who keeps the standard meta-schema: it
    /// replaces the handler registered under the format-annotation URI with the
    /// one that asserts.
    public Store asserting() {
        return vocabulary(Formats.asserting());
    }

    /// Adds a document under the URI in its `$id`.
    public Store add(Json schema) {
        if (!(schema instanceof Json.Object object) || !(object.get("$id") instanceof Json.Str(var id))) {
            throw SchemaException.noId();
        }
        return add(URI.create(id), schema);
    }

    /// Adds a document under a URI the caller chooses. An `$id` inside the
    /// document still starts a resource of its own.
    public Store add(URI uri, Json schema) {
        collect(Pointer.document(uri), schema);
        return this;
    }

    /// The schema registered under this URI, ready to validate instances.
    ///
    /// Compiling reads the vocabularies of every resource it can reach and
    /// resolves every `$ref` and `$dynamicRef` in them. Both faults are
    /// reported here rather than during a validation, because a schema that is
    /// wrong is wrong before any instance arrives.
    public Schema compile(URI uri) {
        var target = resolve(uri).orElseThrow(() -> SchemaException.unknown(uri));
        var schema = new Schema(this, uri, target);
        schema.check();
        return schema;
    }

    /// Adds a document and compiles it in one step. It keeps its `$id` when it
    /// has one, and gets a `urn:` of its own when it has none.
    public Schema compile(Json schema) {
        if (schema instanceof Json.Object object && object.get("$id") instanceof Json.Str(var id)) {
            return add(schema).compile(URI.create(id));
        }
        var uri = URI.create(ANONYMOUS + (++anonymous));
        return add(uri, schema).compile(uri);
    }

    /// The resource under this URI, or null.
    public Resource resource(URI id) {
        return resources.get(id);
    }

    /// Where an absolute URI reference lands, if it lands anywhere.
    ///
    /// The part in front of the `#` names a resource. An empty fragment is the
    /// root of it, a fragment that starts with `/` is a JSON pointer into it,
    /// and anything else is a plain-name anchor.
    public Optional<Located> resolve(URI reference) {
        var resource = resources.get(Pointer.document(reference));
        if (resource == null) return Optional.empty();
        var fragment = reference.getFragment();
        if (fragment == null || fragment.isEmpty()) {
            return Optional.of(new Located(resource.id(), "", resource.root()));
        }
        if (fragment.startsWith("/")) return pointer(resource, fragment);
        return Optional.ofNullable(resource.anchors().get(fragment));
    }

    /// The keyword set a meta-schema defines.
    ///
    /// The core vocabulary is always present. The specification requires every
    /// meta-schema to include it, and a schema without `$ref` or `$defs` would
    /// be a different language.
    Map<String, Keyword> keywords(URI metaschema) {
        var cached = byMetaschema.get(metaschema);
        if (cached != null) return cached;
        var resource = resources.get(Pointer.document(metaschema));
        if (resource == null) throw SchemaException.unknownMetaSchema(metaschema);
        var keywords = new LinkedHashMap<String, Keyword>();
        put(keywords, vocabularies.get(Vocabularies.CORE));
        var root = resource.root();
        if (root instanceof Json.Object object && object.get("$vocabulary") instanceof Json.Object(var declared)) {
            declared.forEach((uri, required) -> declare(keywords, metaschema, URI.create(uri), required));
        } else {
            vocabularies.values().forEach(vocabulary -> put(keywords, vocabulary));
        }
        var built = Map.copyOf(keywords);
        byMetaschema.put(metaschema, built);
        return built;
    }

    /// Which keyword names hold subschemas, over every registered vocabulary.
    Map<String, Keyword.Shape> shapes() {
        return shapes;
    }

    private void declare(Map<String, Keyword> into, URI metaschema, URI uri, Json required) {
        var vocabulary = vocabularies.get(uri);
        if (vocabulary != null) {
            put(into, vocabulary);
            return;
        }
        if (required instanceof Json.Bool(var mandatory) && mandatory) {
            throw SchemaException.unknownVocabulary(uri, metaschema);
        }
    }

    private static void put(Map<String, Keyword> into, Vocabulary vocabulary) {
        if (vocabulary == null) return;
        vocabulary.keywords().forEach(keyword -> into.put(keyword.name(), keyword));
    }

    private Optional<Located> pointer(Resource resource, String pointer) {
        var node = resource.root();
        var base = resource.id();
        var local = "";
        for (var step : pointer.substring(1).split("/", -1)) {
            var token = Pointer.unescape(step);
            node = switch (node) {
                case Json.Object(var fields) -> fields.get(token);
                case Json.Array(var items) -> index(items, token);
                case null, default -> null;
            };
            if (node == null) return Optional.empty();
            local = local + "/" + step;
            if (node instanceof Json.Object(var fields) && fields.get("$id") instanceof Json.Str(var id)) {
                base = Pointer.document(Pointer.resolve(base, id));
                local = "";
            }
        }
        return Optional.of(new Located(base, local, node));
    }

    private static Json index(List<Json> items, String token) {
        if (!token.chars().allMatch(Character::isDigit) || token.isEmpty()) return null;
        var at = Integer.parseInt(token);
        return at < items.size() ? items.get(at) : null;
    }

    private void collect(URI id, Json root) {
        var anchors = new LinkedHashMap<String, Located>();
        var dynamic = new LinkedHashMap<String, Located>();
        var metaschema = root instanceof Json.Object object && object.get("$schema") instanceof Json.Str(var declared)
                ? URI.create(declared)
                : null;
        walk(root, id, "", anchors, dynamic, true);
        resources.put(id, new Resource(id, root, metaschema, anchors, dynamic));
    }

    private void walk(Json node, URI base, String local, Map<String, Located> anchors,
            Map<String, Located> dynamic, boolean root) {
        if (!(node instanceof Json.Object object)) return;
        if (!root && object.get("$id") instanceof Json.Str(var id)) {
            collect(Pointer.document(Pointer.resolve(base, id)), object);
            return;
        }
        if (object.get("$anchor") instanceof Json.Str(var name)) {
            anchors.put(name, new Located(base, local, object));
        }
        if (object.get("$dynamicAnchor") instanceof Json.Str(var name)) {
            var here = new Located(base, local, object);
            anchors.put(name, here);
            dynamic.put(name, here);
        }
        object.fields().forEach((name, value) -> {
            var shape = shapes.get(name);
            if (shape == null) return;
            var step = local + "/" + Pointer.escape(name);
            switch (shape) {
                case NONE -> {}
                case SCHEMA -> walk(value, base, step, anchors, dynamic, false);
                case SCHEMA_ARRAY -> {
                    if (value instanceof Json.Array(var items)) {
                        for (var i = 0; i < items.size(); i++) {
                            walk(items.get(i), base, step + "/" + i, anchors, dynamic, false);
                        }
                    }
                }
                case SCHEMA_MAP -> {
                    if (value instanceof Json.Object(var members)) {
                        members.forEach((key, member) ->
                                walk(member, base, step + "/" + Pointer.escape(key), anchors, dynamic, false));
                    }
                }
            }
        });
    }
}
