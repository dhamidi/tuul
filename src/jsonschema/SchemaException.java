package jsonschema;

import java.net.URI;

/// A schema that this package refuses to use.
///
/// Every case here is a fault in the schema or in the store, and never a fault
/// in the instance. An instance that does not match produces an [Output] with
/// `valid` false. A schema that cannot be evaluated at all stops the caller,
/// because a silent pass would hide the fault until much later.
///
/// The factory methods below are the complete list of refusals. Each message
/// names what was missing and where the search started.
public final class SchemaException extends RuntimeException {

    private SchemaException(String message) {
        super(message);
    }

    /// A `$ref` or a `$dynamicRef` that points at nothing in the store.
    ///
    /// This package never fetches a URI. The store holds what the caller put in
    /// it, so a reference that misses is either a spelling fault or a schema
    /// that the caller forgot to add.
    public static SchemaException unresolved(String reference, URI target, String from) {
        return new SchemaException("no schema at " + target + " — the reference \"" + reference
                + "\" at " + from + " resolves to it. Nothing in the store has that URI."
                + " Add the schema to the store; this package never fetches a URI.");
    }

    /// A URI that names no schema in the store.
    public static SchemaException unknown(URI uri) {
        return new SchemaException("no schema at " + uri + " — add it to the store first");
    }

    /// A meta-schema that requires a vocabulary this store does not know.
    ///
    /// The specification says a required vocabulary that the implementation
    /// cannot process makes the schema unusable. Refusing is the whole point:
    /// the keywords of that vocabulary would otherwise be skipped, and a schema
    /// that skips its assertions accepts everything.
    public static SchemaException unknownVocabulary(URI vocabulary, URI metaschema) {
        return new SchemaException("the meta-schema " + metaschema + " requires the vocabulary " + vocabulary
                + ". This store has no handler for it. Register a Vocabulary with that URI,"
                + " or declare it false in $vocabulary to make it optional.");
    }

    /// A `$schema` that names a meta-schema the store does not hold.
    public static SchemaException unknownMetaSchema(URI metaschema) {
        return new SchemaException("no meta-schema at " + metaschema
                + " — add it to the store before a schema declares it in $schema");
    }

    /// A value in a schema position that is neither an object nor a boolean.
    public static SchemaException notASchema(String at) {
        return new SchemaException("the value at " + at + " is not a schema — a schema is an object or a boolean");
    }

    /// A document added without an `$id` and without a URI.
    public static SchemaException noId() {
        return new SchemaException("this schema has no $id — add it with an explicit URI instead");
    }

    /// References that lead back to themselves without the instance moving.
    ///
    /// A schema may recurse as deep as the instance is deep. A schema whose
    /// `$ref` returns to its own start with the same instance recurses without
    /// end, and no walk of the schema can prove that in general. The limit is
    /// the honest answer.
    public static SchemaException tooDeep(int limit, String at) {
        return new SchemaException("evaluation went deeper than " + limit + " schemas at " + at
                + " — a reference cycle that never reaches the instance is the usual cause");
    }

    /// A meta-schema resource that is not beside the compiled classes.
    public static SchemaException missing(String resource) {
        return new SchemaException("no resource at " + resource + " beside " + Meta.class.getName()
                + " — the build must copy it into the class output, not only leave it in the source tree");
    }
}
