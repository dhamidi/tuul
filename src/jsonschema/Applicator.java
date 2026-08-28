package jsonschema;

import static jsonschema.Keyword.Shape.SCHEMA;
import static jsonschema.Keyword.Shape.SCHEMA_ARRAY;
import static jsonschema.Keyword.Shape.SCHEMA_MAP;
import static jsonschema.Keyword.Stage.ADJACENT;

import java.net.URI;
import java.util.ArrayList;
import java.util.Set;
import json.Json;

/// The applicator vocabulary: the keywords that apply other schemas.
///
/// An applicator says nothing about an instance itself. It picks a subschema
/// and a part of the instance, and the verdict comes from there. Two kinds
/// exist and the difference matters for annotations. An *in-place* applicator
/// such as `allOf` applies a subschema to the same instance, so its annotations
/// land at the same instance location and `unevaluatedProperties` can see them.
/// A *child* applicator such as `properties` applies a subschema to a part of
/// the instance, and its annotations land there instead.
public final class Applicator {

    static final URI URI_ = URI.create("https://json-schema.org/draft/2020-12/vocab/applicator");

    private Applicator() {}

    public static Vocabulary vocabulary() {
        return Vocabulary.of(URI_,
                Keyword.of("allOf", SCHEMA_ARRAY, Applicator::allOf),
                Keyword.of("anyOf", SCHEMA_ARRAY, Applicator::anyOf),
                Keyword.of("oneOf", SCHEMA_ARRAY, Applicator::oneOf),
                Keyword.of("not", SCHEMA, Applicator::not),
                Keyword.of("if", SCHEMA, Applicator::conditional),
                Keyword.inert("then", SCHEMA),
                Keyword.inert("else", SCHEMA),
                Keyword.of("dependentSchemas", SCHEMA_MAP, Applicator::dependentSchemas),
                Keyword.of("prefixItems", SCHEMA_ARRAY, Applicator::prefixItems),
                Keyword.of("items", ADJACENT, SCHEMA, Applicator::items),
                Keyword.of("contains", SCHEMA, Applicator::contains),
                Keyword.of("properties", SCHEMA_MAP, Applicator::properties),
                Keyword.of("patternProperties", SCHEMA_MAP, Applicator::patternProperties),
                Keyword.of("additionalProperties", ADJACENT, SCHEMA, Applicator::additionalProperties),
                Keyword.of("propertyNames", SCHEMA, Applicator::propertyNames));
    }

    private static void allOf(Json value, Json instance, Context context) {
        if (!(value instanceof Json.Array(var branches))) return;
        for (var i = 0; i < branches.size(); i++) context.apply(branches.get(i), "/allOf/" + i);
    }

    /// Every branch runs, even after one has passed.
    ///
    /// Stopping early would be faster and wrong. A later branch that also
    /// passes produces annotations, and `unevaluatedProperties` needs them.
    private static void anyOf(Json value, Json instance, Context context) {
        if (!(value instanceof Json.Array(var branches))) return;
        var passed = 0;
        for (var i = 0; i < branches.size(); i++) {
            var result = context.probe(branches.get(i), "/anyOf/" + i);
            if (!result.valid()) continue;
            passed++;
            context.keep(result);
        }
        if (passed > 0) return;
        context.error("the value matches none of the " + branches.size() + " schemas in anyOf");
    }

    private static void oneOf(Json value, Json instance, Context context) {
        if (!(value instanceof Json.Array(var branches))) return;
        var passed = new ArrayList<Output>();
        for (var i = 0; i < branches.size(); i++) {
            var result = context.probe(branches.get(i), "/oneOf/" + i);
            if (result.valid()) passed.add(result);
        }
        if (passed.size() == 1) {
            context.keep(passed.getFirst());
            return;
        }
        if (passed.isEmpty()) {
            context.error("the value matches none of the " + branches.size() + " schemas in oneOf");
            return;
        }
        context.error("the value matches " + passed.size() + " of the " + branches.size()
                + " schemas in oneOf, and exactly one is allowed");
    }

    /// The subschema must fail. Nothing it produced is kept: the annotations of
    /// a schema that failed are not annotations, and its errors describe the
    /// case that was supposed to happen.
    private static void not(Json value, Json instance, Context context) {
        if (context.probe(value, "/not").valid()) context.error("the value matches the schema under not");
    }

    /// `if` runs `then` and `else` itself.
    ///
    /// The specification gives `then` and `else` no meaning without `if`, so
    /// there is nothing for them to do on their own. Running them from here
    /// also means the verdict of `if` never has to travel through an
    /// annotation. The annotations of `if` are kept only when it passed.
    private static void conditional(Json value, Json instance, Context context) {
        var result = context.probe(value, "/if");
        if (result.valid()) {
            context.keep(result);
            var then = context.schema().get("then");
            if (then != null) context.apply(then, "/then");
            return;
        }
        var otherwise = context.schema().get("else");
        if (otherwise != null) context.apply(otherwise, "/else");
    }

    private static void dependentSchemas(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields))) return;
        if (!(value instanceof Json.Object(var members))) return;
        members.forEach((name, subschema) -> {
            if (!fields.containsKey(name)) return;
            context.apply(subschema, "/dependentSchemas/" + Pointer.escape(name));
        });
    }

    /// The annotation is the largest index this keyword applied to, or `true`
    /// when it applied to every item. `items` and `unevaluatedItems` read it.
    private static void prefixItems(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Array(var items))) return;
        if (!(value instanceof Json.Array(var schemas))) return;
        var count = Math.min(items.size(), schemas.size());
        for (var i = 0; i < count; i++) {
            context.apply(schemas.get(i), "/prefixItems/" + i, items.get(i), "/" + i);
        }
        if (count == 0) return;
        context.annotate(count == items.size() ? Json.TRUE : Json.of(count - 1));
    }

    /// `items` covers the items that `prefixItems` did not.
    ///
    /// The count comes from the `prefixItems` array beside it and not from its
    /// annotation, because `prefixItems` produces no annotation when the
    /// instance is shorter than it, and `items` must still start after it.
    private static void items(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Array(var items))) return;
        var start = context.schema().get("prefixItems") instanceof Json.Array(var prefix) ? prefix.size() : 0;
        if (start >= items.size()) return;
        for (var i = start; i < items.size(); i++) context.apply(value, "/items", items.get(i), "/" + i);
        context.annotate(Json.TRUE);
    }

    /// The annotation is the list of indices that matched. `minContains`,
    /// `maxContains` and `unevaluatedItems` all read it.
    ///
    /// `contains` on its own asks for at least one match. `minContains: 0`
    /// takes that rule away, so the assertion here looks at its neighbour.
    private static void contains(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Array(var items))) return;
        var matched = new ArrayList<Json>();
        for (var i = 0; i < items.size(); i++) {
            var result = context.probe(value, "/contains", items.get(i), "/" + i);
            if (!result.valid()) continue;
            matched.add(Json.of(i));
            context.keep(result);
        }
        context.annotate(Json.Array.of(matched));
        var least = context.schema().get("minContains") instanceof Json.Num(var minimum) ? minimum : 1;
        if (least == 0 || !matched.isEmpty()) return;
        context.error("no item of the array matches the schema in contains");
    }

    private static void properties(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields))) return;
        if (!(value instanceof Json.Object(var members))) return;
        var matched = new ArrayList<Json>();
        fields.forEach((name, held) -> {
            var subschema = members.get(name);
            if (subschema == null) return;
            matched.add(Json.of(name));
            var step = "/" + Pointer.escape(name);
            context.apply(subschema, "/properties" + step, held, step);
        });
        context.annotate(Json.Array.of(matched));
    }

    private static void patternProperties(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields))) return;
        if (!(value instanceof Json.Object(var members))) return;
        var matched = new ArrayList<Json>();
        members.forEach((pattern, subschema) -> fields.forEach((name, held) -> {
            if (!Patterns.found(pattern, name)) return;
            matched.add(Json.of(name));
            context.apply(subschema, "/patternProperties/" + Pointer.escape(pattern), held,
                    "/" + Pointer.escape(name));
        }));
        context.annotate(Json.Array.of(matched));
    }

    /// The names that `properties` and `patternProperties` in the **same schema
    /// object** did not match.
    ///
    /// This reads the two keywords beside it rather than their annotations. The
    /// specification scopes `additionalProperties` to its own schema object,
    /// while an annotation would also carry names matched inside an `allOf`
    /// branch, and those names are not the ones this keyword skips.
    private static void additionalProperties(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields))) return;
        var named = context.schema().get("properties") instanceof Json.Object(var members)
                ? members.keySet()
                : Set.<String>of();
        var patterns = context.schema().get("patternProperties") instanceof Json.Object(var members)
                ? members.keySet()
                : Set.<String>of();
        var matched = new ArrayList<Json>();
        fields.forEach((name, held) -> {
            if (named.contains(name)) return;
            if (patterns.stream().anyMatch(pattern -> Patterns.found(pattern, name))) return;
            matched.add(Json.of(name));
            context.apply(value, "/additionalProperties", held, "/" + Pointer.escape(name));
        });
        context.annotate(Json.Array.of(matched));
    }

    /// The subschema sees each property name as a string.
    ///
    /// A name is not a value inside the instance, so it has no instance
    /// location of its own. The property it names is the closest honest answer,
    /// and it is what makes an error point at something a person can find.
    private static void propertyNames(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields))) return;
        for (var name : fields.keySet()) {
            context.apply(value, "/propertyNames", Json.of(name), "/" + Pointer.escape(name));
        }
    }
}
