package jsonschema;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import json.Json;

/// What one [Keyword] is given while it runs.
///
/// One context belongs to one schema object at one instance location. It holds
/// the errors and the annotations that the keywords of that schema object
/// produce, and it is how a keyword reaches everything outside itself: the
/// schema around it, the store, the subschemas under it, and the annotations
/// that ran before it.
///
/// The split between [#probe(Json,String)] and [#keep(Output)] is the reason
/// `anyOf` and `not` work. A probe evaluates a subschema and hands back what it
/// produced without keeping any of it. The keyword then decides. `allOf` keeps
/// every branch. `anyOf` keeps the branches that passed and drops the rest.
/// `not` keeps nothing. Annotations from a branch that failed must not survive,
/// because `unevaluatedProperties` would then see properties that nothing
/// really evaluated.
///
/// Dropping a branch does not have to mean losing what it said.
/// [#explain(Output)] takes the errors of a failed result and leaves its
/// annotations behind, so a keyword can report why each branch failed without
/// breaking the rule above.
public interface Context {

    /// The store the schema was compiled from.
    Store store();

    /// The schema object this keyword sits in, so that a keyword can read the
    /// keywords beside it.
    Json.Object schema();

    /// The base URI in force here.
    URI base();

    /// The name of the keyword that is running.
    String keyword();

    /// Evaluates a subschema against the same instance, and keeps nothing.
    ///
    /// `path` is appended to the keyword location and to the pointer inside the
    /// resource. It starts with a `/` and it carries the keyword name, so
    /// `allOf` passes `"/allOf/0"`. The keyword writes its own name because a
    /// keyword may evaluate a subschema that belongs to another one: `if` runs
    /// `then`.
    Output probe(Json subschema, String path);

    /// Evaluates a subschema against a part of the instance, and keeps nothing.
    ///
    /// `instancePath` is appended to the instance location the same way.
    Output probe(Json subschema, String path, Json child, String instancePath);

    /// Takes what a probe produced: the errors when it failed, and the
    /// annotations when it passed. A failure taken here makes this schema
    /// object fail.
    void keep(Output output);

    /// Takes the errors of a result that failed, and none of its annotations.
    ///
    /// A keyword that decides for itself what a failed subschema means uses
    /// this to pass on why that subschema failed. `anyOf` calls it for every
    /// branch once it knows that no branch passed, so the output carries both
    /// the summary and what each branch wanted. The verdict is untouched, so a
    /// keyword that means to fail must still call [#error(String)]. A result
    /// that passed contributes nothing here.
    void explain(Output failed);

    /// This keyword failed, and this is why.
    void error(String message);

    /// This keyword produced a value. Later keywords at the same instance
    /// location can read it with [#annotations(String)].
    void annotate(Json value);

    /// The values that a named keyword produced at this instance location.
    ///
    /// The list covers this schema object and every in-place applicator under
    /// it whose result was kept, which is exactly the set the specification
    /// gives to `unevaluatedProperties`. A subschema that applied to a *child*
    /// of the instance produced its annotations at the child location, and this
    /// method does not return those.
    ///
    /// A keyword is matched by the last step of its keyword location, so
    /// `properties` also finds `/allOf/0/$ref/properties`.
    List<Json> annotations(String keyword);

    /// Resolves a URI reference against the base URI in force.
    ///
    /// Throws [SchemaException] when the store holds nothing at that URI. This
    /// package never fetches a URI, so a miss is final.
    Store.Located resolve(String reference);

    /// The outermost schema resource in the dynamic scope that declares
    /// `$dynamicAnchor` with this name. This is what makes `$dynamicRef`
    /// different from `$ref`.
    Optional<Store.Located> dynamic(String anchor);

    /// Evaluates the schema a reference points at, against the same instance.
    ///
    /// The base URI, the pointer inside the resource and the dynamic scope all
    /// become the target's. The keyword location gains this keyword's name, so
    /// the output still shows the way the evaluation went.
    Output follow(Store.Located target);

    /// [#probe(Json,String)] and [#keep(Output)] in one call, which is what
    /// most keywords want.
    default boolean apply(Json subschema, String path) {
        var output = probe(subschema, path);
        keep(output);
        return output.valid();
    }

    default boolean apply(Json subschema, String path, Json child, String instancePath) {
        var output = probe(subschema, path, child, instancePath);
        keep(output);
        return output.valid();
    }
}
