package jsonschema;

import json.Json;

/// One keyword of one [Vocabulary], and what it does to an instance.
///
/// A handler reads its own value out of the schema, looks at the instance, and
/// reports through the [Context]: `error` for a failure, `annotate` for a
/// value that later keywords may read, `apply` and `probe` for subschemas.
///
/// A handler returns nothing. The [Context] collects the verdict, so a handler
/// that has nothing to say about an instance simply returns. That is how every
/// keyword ignores the instance types it does not describe: `maxLength` returns
/// at once when the instance is not a string.
public interface Keyword {

    /// The name of the keyword, as it appears in a schema object.
    String name();

    /// When this keyword runs, next to the other keywords of the same schema
    /// object.
    default Stage stage() {
        return Stage.ASSERT;
    }

    /// Where this keyword holds subschemas.
    ///
    /// The store walks a document to find `$anchor`, `$dynamicAnchor` and every
    /// embedded `$id`, and the walk must know which values are schemas and
    /// which are plain data. `const` holds a value that can look exactly like a
    /// schema, so guessing is not an option. A keyword says it here, and a
    /// vocabulary a caller writes says it the same way.
    default Shape shape() {
        return Shape.NONE;
    }

    void apply(Json value, Json instance, Context context);

    /// The order the keywords of one schema object run in.
    ///
    /// The order is not a style choice. `additionalProperties` needs to know
    /// which names `properties` matched, `then` needs the verdict of `if`, and
    /// `unevaluatedItems` needs the annotations of every applicator beside it.
    /// A keyword that reads what another keyword produced declares a later
    /// stage, and the engine runs the stages in this order.
    enum Stage {

        /// `$ref` and `$dynamicRef`. They run first, so that the annotations of
        /// the schema they point at are already there for everything else.
        REFERENCE,

        /// The normal case: the keyword reads the schema and the instance only.
        ASSERT,

        /// The keyword reads what an adjacent keyword produced.
        ADJACENT,

        /// `unevaluatedItems` and `unevaluatedProperties`. They run last,
        /// because they read the annotations of every other keyword.
        UNEVALUATED
    }

    /// How a keyword holds subschemas, for the walk that indexes a document.
    enum Shape {

        /// The value is data, not a schema.
        NONE,

        /// The value is one schema.
        SCHEMA,

        /// The value is an array of schemas.
        SCHEMA_ARRAY,

        /// The value is an object whose values are schemas.
        SCHEMA_MAP
    }

    /// What a handler does. Separate from [Keyword] so that a vocabulary reads
    /// as a list of one-line lambdas.
    @FunctionalInterface
    interface Body {
        void apply(Json value, Json instance, Context context);
    }

    static Keyword of(String name, Body body) {
        return new Handler(name, Stage.ASSERT, Shape.NONE, body);
    }

    static Keyword of(String name, Stage stage, Body body) {
        return new Handler(name, stage, Shape.NONE, body);
    }

    static Keyword of(String name, Shape shape, Body body) {
        return new Handler(name, Stage.ASSERT, shape, body);
    }

    static Keyword of(String name, Stage stage, Shape shape, Body body) {
        return new Handler(name, stage, shape, body);
    }

    /// A keyword that does nothing when it runs.
    ///
    /// `$id`, `$anchor` and `$defs` are read while the store indexes a document
    /// and mean nothing at evaluation time. They still have to be declared,
    /// because a name with no handler is an unknown keyword, and because the
    /// walk needs their shape.
    static Keyword inert(String name) {
        return of(name, (value, instance, context) -> {});
    }

    static Keyword inert(String name, Shape shape) {
        return of(name, Stage.ASSERT, shape, (value, instance, context) -> {});
    }

    record Handler(String name, Stage stage, Shape shape, Body body) implements Keyword {

        @Override
        public void apply(Json value, Json instance, Context context) {
            body.apply(value, instance, context);
        }
    }
}
