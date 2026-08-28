package jsonschema;

import static jsonschema.Keyword.Shape.SCHEMA;
import static jsonschema.Keyword.Stage.UNEVALUATED;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import json.Json;

/// `unevaluatedItems` and `unevaluatedProperties`: the two keywords that read
/// what everything else produced.
///
/// These work only because evaluation collects annotations rather than
/// booleans. "Unevaluated" means: not covered by an annotation at this instance
/// location, from this schema object or from any in-place applicator under it
/// whose result was kept. `allOf` is an in-place applicator, so
/// `unevaluatedProperties` sees the properties that a branch of an `allOf`
/// matched, which is the thing `additionalProperties` cannot do.
///
/// They run in the last stage, so every other keyword of the schema object has
/// already reported. A branch that failed contributed nothing, because the
/// [Context] drops the annotations of a failed result.
public final class Unevaluated {

    static final URI URI_ = URI.create("https://json-schema.org/draft/2020-12/vocab/unevaluated");

    /// The keywords whose annotations say a property was evaluated.
    private static final List<String> PROPERTY_SOURCES =
            List.of("properties", "patternProperties", "additionalProperties", "unevaluatedProperties");

    private Unevaluated() {}

    public static Vocabulary vocabulary() {
        return Vocabulary.of(URI_,
                Keyword.of("unevaluatedItems", UNEVALUATED, SCHEMA, Unevaluated::items),
                Keyword.of("unevaluatedProperties", UNEVALUATED, SCHEMA, Unevaluated::properties));
    }

    private static void properties(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields))) return;
        var seen = evaluatedNames(context);
        var matched = new ArrayList<Json>();
        fields.forEach((name, held) -> {
            if (seen.contains(name)) return;
            matched.add(Json.of(name));
            context.apply(value, "/unevaluatedProperties", held, "/" + Pointer.escape(name));
        });
        context.annotate(Json.Array.of(matched));
    }

    private static Set<String> evaluatedNames(Context context) {
        var seen = new HashSet<String>();
        for (var keyword : PROPERTY_SOURCES) {
            for (var annotation : context.annotations(keyword)) {
                if (!(annotation instanceof Json.Array(var names))) continue;
                for (var name : names) {
                    if (name instanceof Json.Str(var text)) seen.add(text);
                }
            }
        }
        return seen;
    }

    /// An item counts as evaluated when `prefixItems`, `items`, `contains` or an
    /// `unevaluatedItems` further in said so.
    ///
    /// The three annotations do not have one shape. `prefixItems` reports the
    /// largest index it reached, or `true` for all of them. `items` and
    /// `unevaluatedItems` report `true`, and `true` always means every item.
    /// `contains` reports the list of indices that matched, and only those
    /// count.
    private static void items(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Array(var items))) return;
        var seen = new boolean[items.size()];
        cover(context, "prefixItems", seen);
        cover(context, "items", seen);
        cover(context, "unevaluatedItems", seen);
        cover(context, "contains", seen);
        var applied = false;
        for (var i = 0; i < items.size(); i++) {
            if (seen[i]) continue;
            applied = true;
            context.apply(value, "/unevaluatedItems", items.get(i), "/" + i);
        }
        if (applied) context.annotate(Json.TRUE);
    }

    private static void cover(Context context, String keyword, boolean[] seen) {
        for (var annotation : context.annotations(keyword)) {
            switch (annotation) {
                case Json.Bool(var all) -> {
                    if (all) java.util.Arrays.fill(seen, true);
                }
                case Json.Num(var largest) -> {
                    for (var i = 0; i <= largest && i < seen.length; i++) seen[i] = true;
                }
                case Json.Array(var indices) -> {
                    for (var index : indices) {
                        if (index instanceof Json.Num(var at) && at < seen.length) seen[(int) at] = true;
                    }
                }
                default -> {}
            }
        }
    }
}
