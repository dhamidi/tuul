package jsonschema;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import json.Json;

/// The engine: it applies the keywords of a schema object to an instance, and
/// it carries the two things that no single keyword can carry on its own.
///
/// The first is the **dynamic scope**. Evaluation walks into schema resources
/// and back out again, and `$dynamicRef` needs the list of resources it walked
/// through, outermost first. The list lives in [Place] and is immutable, so
/// coming back out of a resource is a return and never an explicit pop.
///
/// The second is **annotations**. Every keyword reports through a [Frame], and
/// a frame keeps every error and annotation that its keywords produced. A
/// keyword that applies a subschema gets that subschema's frame back as an
/// [Output] and decides whether to keep it. Keeping an annotation is what makes
/// it visible to `unevaluatedItems` and `unevaluatedProperties`, and dropping
/// the annotations of a branch that failed is what keeps them honest.
final class Evaluator {

    /// How deep evaluation may go before it is a cycle. Schema depth times
    /// instance depth stays far below this for any schema written by a person.
    static final int LIMIT = 400;

    private final Store store;
    private final Schema schema;
    private int depth;

    Evaluator(Store store, Schema schema) {
        this.store = store;
        this.schema = schema;
    }

    Output run(Store.Located root, Json instance) {
        return evaluate(root.schema(), new Place(root.base(), root.pointer(), "", "", List.of(root.base())), instance);
    }

    Store store() {
        return store;
    }

    /// Where evaluation is, in all four ways it has to know.
    ///
    /// `base` and `local` say where the schema is, and together they make the
    /// absolute keyword location. `keywords` says how the evaluation got here
    /// and still holds every `$ref` step. `instance` says which part of the
    /// instance is under the schema. `scope` is the dynamic scope.
    record Place(URI base, String local, String keywords, String instance, List<URI> scope) {

        /// One step further into the same schema, at the same instance.
        Place in(String path) {
            return new Place(base, local + path, keywords + path, instance, scope);
        }

        /// The same schema step, one step further into the instance.
        Place on(String path) {
            return new Place(base, local, keywords, instance + path, scope);
        }

        /// Across a reference: another resource, the same instance, and one
        /// more step in the keyword location.
        Place jump(URI target, String pointer, String step) {
            return new Place(target, pointer, keywords + step, instance, scope);
        }

        Place entering(URI target, String pointer) {
            if (!scope.isEmpty() && scope.getLast().equals(target)) {
                return new Place(target, pointer, keywords, instance, scope);
            }
            var next = new ArrayList<>(scope);
            next.add(target);
            return new Place(target, pointer, keywords, instance, List.copyOf(next));
        }
    }

    Output evaluate(Json node, Place at, Json instance) {
        if (node instanceof Json.Bool(var allowed)) {
            if (allowed) return Output.ok();
            var where = new Location(at.keywords(), Pointer.absolute(at.base(), at.local()), at.instance());
            var reason = "must NOT be present, because the schema here is false";
            return new Output(false, List.of(Unit.Error.of(where, reason)), List.of());
        }
        if (!(node instanceof Json.Object object)) throw SchemaException.notASchema(at.keywords());
        var place = at;
        if (object.get("$id") instanceof Json.Str(var id)) {
            place = place.entering(Pointer.document(Pointer.resolve(at.base(), id)), "");
        } else {
            place = place.entering(at.base(), at.local());
        }
        if (++depth > LIMIT) {
            depth--;
            throw SchemaException.tooDeep(LIMIT, at.keywords());
        }
        try {
            var frame = new Frame(this, object, place, instance);
            var handlers = schema.keywords(place.base());
            for (var stage : Keyword.Stage.values()) {
                for (var name : object.fields().keySet()) {
                    var keyword = handlers.get(name);
                    if (keyword == null || keyword.stage() != stage) continue;
                    frame.run(keyword, object.get(name));
                }
            }
            return frame.output();
        } finally {
            depth--;
        }
    }

    /// One schema object, at one instance location, while its keywords run.
    static final class Frame implements Context {

        private final Evaluator engine;
        private final Json.Object schema;
        private final Place at;
        private final Json instance;
        private final List<Unit> errors = new ArrayList<>();
        private final List<Unit> annotations = new ArrayList<>();
        private String keyword = "";
        private boolean valid = true;

        Frame(Evaluator engine, Json.Object schema, Place at, Json instance) {
            this.engine = engine;
            this.schema = schema;
            this.at = at;
            this.instance = instance;
        }

        void run(Keyword handler, Json value) {
            keyword = handler.name();
            handler.apply(value, instance, this);
        }

        Output output() {
            return new Output(valid, errors, annotations);
        }

        @Override
        public Store store() {
            return engine.store();
        }

        @Override
        public Json.Object schema() {
            return schema;
        }

        @Override
        public URI base() {
            return at.base();
        }

        @Override
        public String keyword() {
            return keyword;
        }

        @Override
        public Output probe(Json subschema, String path) {
            return engine.evaluate(subschema, at.in(path), instance);
        }

        @Override
        public Output probe(Json subschema, String path, Json child, String instancePath) {
            return engine.evaluate(subschema, at.in(path).on(instancePath), child);
        }

        @Override
        public void keep(Output output) {
            if (output.valid()) {
                annotations.addAll(output.annotations());
                return;
            }
            errors.addAll(output.errors());
            valid = false;
        }

        @Override
        public void explain(Output failed) {
            if (failed.valid()) return;
            errors.addAll(failed.errors());
        }

        @Override
        public void error(String message, Json params) {
            valid = false;
            errors.add(new Unit.Error(here(), message, params));
        }

        @Override
        public void annotate(Json value) {
            annotations.add(new Unit.Annotation(here(), value));
        }

        @Override
        public List<Json> annotations(String name) {
            var found = new ArrayList<Json>();
            for (var unit : annotations) {
                if (!(unit instanceof Unit.Annotation(var where, var value))) continue;
                if (!where.instanceLocation().equals(at.instance())) continue;
                if (!last(where.keywordLocation()).equals(name)) continue;
                found.add(value);
            }
            return found;
        }

        @Override
        public Store.Located resolve(String reference) {
            var target = Pointer.resolve(at.base(), reference);
            var from = Pointer.absolute(at.base(), at.local() + "/" + Pointer.escape(keyword)).toString();
            return engine.store().resolve(target)
                    .orElseThrow(() -> SchemaException.unresolved(reference, target, from));
        }

        @Override
        public Optional<Store.Located> dynamic(String anchor) {
            for (var uri : at.scope()) {
                var resource = engine.store().resource(uri);
                if (resource == null) continue;
                var found = resource.dynamicAnchors().get(anchor);
                if (found != null) return Optional.of(found);
            }
            return Optional.empty();
        }

        @Override
        public Output follow(Store.Located target) {
            return engine.evaluate(target.schema(),
                    at.jump(target.base(), target.pointer(), "/" + Pointer.escape(keyword)), instance);
        }

        private Location here() {
            return new Location(at.keywords() + "/" + keyword,
                    Pointer.absolute(at.base(), at.local() + "/" + Pointer.escape(keyword)), at.instance());
        }

        private static String last(String pointer) {
            var slash = pointer.lastIndexOf('/');
            return slash < 0 ? pointer : Pointer.unescape(pointer.substring(slash + 1));
        }
    }
}
