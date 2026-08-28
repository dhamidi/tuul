package jsonschema;

import static jsonschema.Keyword.Stage.ADJACENT;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import json.Json;

/// The validation vocabulary: the keywords that look at the instance and say
/// yes or no.
///
/// Every one of them ignores an instance of a type it does not describe.
/// `maxLength` says nothing about a number, and saying nothing is a pass. That
/// is not leniency, it is the specification: a schema constrains the types it
/// names and leaves the rest alone.
///
/// The numeric keywords inherit the limits of a `double`. [Numbers] states them.
///
/// [Unit] describes the wording these keywords use and the `params` value each
/// one attaches.
public final class Validation {

    static final URI URI_ = URI.create("https://json-schema.org/draft/2020-12/vocab/validation");

    private Validation() {}

    public static Vocabulary vocabulary() {
        return Vocabulary.of(URI_, List.of(
                Keyword.of("type", Validation::type),
                Keyword.of("enum", Validation::enumeration),
                Keyword.of("const", Validation::constant),
                Keyword.of("multipleOf", Validation::multipleOf),
                Keyword.of("maximum", Validation::maximum),
                Keyword.of("exclusiveMaximum", Validation::exclusiveMaximum),
                Keyword.of("minimum", Validation::minimum),
                Keyword.of("exclusiveMinimum", Validation::exclusiveMinimum),
                Keyword.of("maxLength", Validation::maxLength),
                Keyword.of("minLength", Validation::minLength),
                Keyword.of("pattern", Validation::pattern),
                Keyword.of("maxItems", Validation::maxItems),
                Keyword.of("minItems", Validation::minItems),
                Keyword.of("uniqueItems", Validation::uniqueItems),
                Keyword.of("maxContains", ADJACENT, Validation::maxContains),
                Keyword.of("minContains", ADJACENT, Validation::minContains),
                Keyword.of("required", Validation::required),
                Keyword.of("dependentRequired", Validation::dependentRequired),
                Keyword.of("maxProperties", Validation::maxProperties),
                Keyword.of("minProperties", Validation::minProperties)));
    }

    /// A count and the noun it counts, with the noun in the right number.
    ///
    /// Ajv appends a fixed plural here and writes `1 characters`. This package
    /// diverges on purpose, because a message that a person reads should read
    /// as English and the correct form costs one comparison.
    private static String count(double amount, String singular, String plural) {
        return Numbers.text(amount) + " " + (amount == 1 ? singular : plural);
    }

    /// The `params` shared by every keyword that compares a measurement of the
    /// instance against a number in the schema.
    private static Json bound(double limit, double actual) {
        return Json.Object.of().with("limit", limit).with("actual", actual);
    }

    /// One name, or an array of names, and the instance must be one of them.
    private static void type(Json value, Json instance, Context context) {
        var allowed = switch (value) {
            case Json.Str(var name) -> List.of(name);
            case Json.Array(var names) -> names.stream()
                    .map(name -> name instanceof Json.Str(var text) ? text : "")
                    .toList();
            case null, default -> List.<String>of();
        };
        if (allowed.isEmpty() || allowed.stream().anyMatch(name -> is(instance, name))) return;
        context.error("must be " + either(allowed), Json.Object.of()
                .with("expected", Json.Array.strings(allowed))
                .with("actual", name(instance)));
    }

    /// The allowed types as one phrase: `string`, or `string or number`, or
    /// `string, number or null`.
    private static String either(List<String> names) {
        if (names.size() == 1) return names.getFirst();
        return String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.getLast();
    }

    /// `integer` is a number with no fractional part, and not a type of its
    /// own. `1.0` is an integer. See [Numbers] for what happens above 2^53.
    private static boolean is(Json instance, String type) {
        return switch (type) {
            case "null" -> instance instanceof Json.Null;
            case "boolean" -> instance instanceof Json.Bool;
            case "string" -> instance instanceof Json.Str;
            case "array" -> instance instanceof Json.Array;
            case "object" -> instance instanceof Json.Object;
            case "number" -> instance instanceof Json.Num;
            case "integer" -> instance instanceof Json.Num(var number) && Numbers.integral(number);
            default -> false;
        };
    }

    /// The name the specification gives the type of this instance. `integer` is
    /// never the answer, because the specification does not make it a type.
    static String name(Json instance) {
        return switch (instance) {
            case Json.Null ignored -> "null";
            case Json.Bool ignored -> "boolean";
            case Json.Num ignored -> "number";
            case Json.Str ignored -> "string";
            case Json.Array ignored -> "array";
            case Json.Object ignored -> "object";
        };
    }

    private static void enumeration(Json value, Json instance, Context context) {
        if (!(value instanceof Json.Array(var allowed))) return;
        if (allowed.contains(instance)) return;
        context.error("must be equal to one of the allowed values",
                Json.Object.of().with("allowed", Json.Array.of(allowed)));
    }

    private static void constant(Json value, Json instance, Context context) {
        if (value.equals(instance)) return;
        context.error("must be equal to constant", Json.Object.of().with("allowed", value));
    }

    private static void multipleOf(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Num(var number)) || !(value instanceof Json.Num(var factor))) return;
        if (Numbers.multiple(number, factor)) return;
        context.error("must be multiple of " + Numbers.text(factor),
                Json.Object.of().with("multipleOf", factor).with("actual", number));
    }

    private static void maximum(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Num(var number)) || !(value instanceof Json.Num(var limit))) return;
        if (number <= limit) return;
        context.error("must be <= " + Numbers.text(limit), bound(limit, number));
    }

    private static void exclusiveMaximum(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Num(var number)) || !(value instanceof Json.Num(var limit))) return;
        if (number < limit) return;
        context.error("must be < " + Numbers.text(limit), bound(limit, number));
    }

    private static void minimum(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Num(var number)) || !(value instanceof Json.Num(var limit))) return;
        if (number >= limit) return;
        context.error("must be >= " + Numbers.text(limit), bound(limit, number));
    }

    private static void exclusiveMinimum(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Num(var number)) || !(value instanceof Json.Num(var limit))) return;
        if (number > limit) return;
        context.error("must be > " + Numbers.text(limit), bound(limit, number));
    }

    /// Length is counted in code points and not in `char` values, because the
    /// specification counts characters and a `String` holds UTF-16 units. The
    /// string "a" plus one emoji is two characters long here and three in Java.
    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static void maxLength(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Str(var text)) || !(value instanceof Json.Num(var limit))) return;
        if (length(text) <= limit) return;
        context.error("must NOT have more than " + count(limit, "character", "characters"),
                bound(limit, length(text)));
    }

    private static void minLength(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Str(var text)) || !(value instanceof Json.Num(var limit))) return;
        if (length(text) >= limit) return;
        context.error("must NOT have fewer than " + count(limit, "character", "characters"),
                bound(limit, length(text)));
    }

    private static void pattern(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Str(var text)) || !(value instanceof Json.Str(var expression))) return;
        if (Patterns.found(expression, text)) return;
        context.error("must match pattern \"" + expression + "\"",
                Json.Object.of().with("pattern", expression));
    }

    private static void maxItems(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Array(var items)) || !(value instanceof Json.Num(var limit))) return;
        if (items.size() <= limit) return;
        context.error("must NOT have more than " + count(limit, "item", "items"), bound(limit, items.size()));
    }

    private static void minItems(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Array(var items)) || !(value instanceof Json.Num(var limit))) return;
        if (items.size() >= limit) return;
        context.error("must NOT have fewer than " + count(limit, "item", "items"), bound(limit, items.size()));
    }

    /// Two items are the same when they are the same JSON value. `1` and `1.0`
    /// are the same number, and `{"a":1,"b":2}` and `{"b":2,"a":1}` are the same
    /// object, because field order is not part of a JSON value.
    ///
    /// The `params` name the index of the first item that repeats one before it,
    /// because that index is what a person needs in order to remove it.
    private static void uniqueItems(Json value, Json instance, Context context) {
        if (!(value instanceof Json.Bool(var required)) || !required) return;
        if (!(instance instanceof Json.Array(var items))) return;
        var seen = new HashSet<Json>();
        for (var i = 0; i < items.size(); i++) {
            if (seen.add(items.get(i))) continue;
            context.error("must NOT have duplicate items", Json.Object.of().with("duplicate", i));
            return;
        }
    }

    /// Counts the indices that `contains` reported. Without `contains` beside
    /// it there is nothing to count and the keyword says nothing.
    private static int contained(Context context) {
        for (var annotation : context.annotations("contains")) {
            if (annotation instanceof Json.Array(var indices)) return indices.size();
        }
        return 0;
    }

    private static void maxContains(Json value, Json instance, Context context) {
        if (context.schema().get("contains") == null || !(instance instanceof Json.Array)) return;
        if (!(value instanceof Json.Num(var limit))) return;
        var found = contained(context);
        if (found <= limit) return;
        context.error("must NOT have more than " + count(limit, "item", "items") + " matching contains",
                bound(limit, found));
    }

    private static void minContains(Json value, Json instance, Context context) {
        if (context.schema().get("contains") == null || !(instance instanceof Json.Array)) return;
        if (!(value instanceof Json.Num(var limit))) return;
        var found = contained(context);
        if (found >= limit) return;
        context.error("must have at least " + count(limit, "item", "items") + " matching contains",
                bound(limit, found));
    }

    private static void required(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields)) || !(value instanceof Json.Array(var names))) return;
        for (var name : names) {
            if (!(name instanceof Json.Str(var text)) || fields.containsKey(text)) continue;
            context.error("must have required property '" + text + "'",
                    Json.Object.of().with("missingProperty", text));
        }
    }

    private static void dependentRequired(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields)) || !(value instanceof Json.Object(var members))) return;
        members.forEach((name, needed) -> {
            if (!fields.containsKey(name) || !(needed instanceof Json.Array(var names))) return;
            for (var other : names) {
                if (!(other instanceof Json.Str(var text)) || fields.containsKey(text)) continue;
                context.error("must have property " + text + " when property " + name + " is present",
                        Json.Object.of().with("property", name).with("missingProperty", text));
            }
        });
    }

    private static void maxProperties(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields)) || !(value instanceof Json.Num(var limit))) return;
        if (fields.size() <= limit) return;
        context.error("must NOT have more than " + count(limit, "property", "properties"),
                bound(limit, fields.size()));
    }

    private static void minProperties(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields)) || !(value instanceof Json.Num(var limit))) return;
        if (fields.size() >= limit) return;
        context.error("must NOT have fewer than " + count(limit, "property", "properties"),
                bound(limit, fields.size()));
    }
}
