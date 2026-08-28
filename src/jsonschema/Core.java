package jsonschema;

import static jsonschema.Keyword.Shape.SCHEMA_MAP;
import static jsonschema.Keyword.Stage.REFERENCE;

import java.net.URI;
import json.Json;

/// The core vocabulary: identity, references, and the dynamic scope.
///
/// Most of it does nothing at evaluation time. `$id`, `$anchor`,
/// `$dynamicAnchor`, `$defs`, `$comment` and `$vocabulary` are read while the
/// store indexes a document, and by the time an instance arrives they have
/// already done their work. Only the two references act.
public final class Core {

    static final URI URI_ = URI.create("https://json-schema.org/draft/2020-12/vocab/core");

    private Core() {}

    public static Vocabulary vocabulary() {
        return Vocabulary.of(URI_,
                Keyword.inert("$schema"),
                Keyword.inert("$id"),
                Keyword.inert("$anchor"),
                Keyword.inert("$dynamicAnchor"),
                Keyword.inert("$comment"),
                Keyword.inert("$vocabulary"),
                Keyword.inert("$defs", SCHEMA_MAP),
                Keyword.of("$ref", REFERENCE, Core::reference),
                Keyword.of("$dynamicRef", REFERENCE, Core::dynamicReference));
    }

    private static void reference(Json value, Json instance, Context context) {
        if (!(value instanceof Json.Str(var reference))) return;
        context.keep(context.follow(context.resolve(reference)));
    }

    /// `$dynamicRef` resolves like `$ref` and then asks one more question.
    ///
    /// If the schema it landed on carries a `$dynamicAnchor` of the same name,
    /// the answer is not that schema. It is the schema in the **outermost**
    /// resource of the dynamic scope that carries the same `$dynamicAnchor`.
    /// That is the whole difference from `$ref`, and it is what lets a schema
    /// extend a recursive schema: `strict-tree` declares `$dynamicAnchor:
    /// "node"`, refers to `tree`, and every `$dynamicRef: "#node"` inside
    /// `tree` comes back to `strict-tree` instead of to `tree`.
    ///
    /// When the initial target carries no such anchor, `$dynamicRef` is `$ref`.
    /// A reference whose fragment is a pointer rather than a plain name is
    /// `$ref` as well.
    private static void dynamicReference(Json value, Json instance, Context context) {
        if (!(value instanceof Json.Str(var reference))) return;
        var initial = context.resolve(reference);
        var anchor = plainName(reference);
        var target = anchor != null && carries(initial.schema(), anchor)
                ? context.dynamic(anchor).orElse(initial)
                : initial;
        context.keep(context.follow(target));
    }

    private static String plainName(String reference) {
        var hash = reference.indexOf('#');
        if (hash < 0) return null;
        var fragment = reference.substring(hash + 1);
        return fragment.isEmpty() || fragment.startsWith("/") ? null : fragment;
    }

    private static boolean carries(Json node, String anchor) {
        return node instanceof Json.Object object
                && object.get("$dynamicAnchor") instanceof Json.Str(var name)
                && name.equals(anchor);
    }
}
