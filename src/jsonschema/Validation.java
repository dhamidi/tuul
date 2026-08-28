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
        context.error("the value is " + name(instance) + " and the schema allows " + String.join(", ", allowed));
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

    static String name(Json instance) {
        return switch (instance) {
            case Json.Null ignored -> "null";
            case Json.Bool ignored -> "a boolean";
            case Json.Num ignored -> "a number";
            case Json.Str ignored -> "a string";
            case Json.Array ignored -> "an array";
            case Json.Object ignored -> "an object";
        };
    }

    private static void enumeration(Json value, Json instance, Context context) {
        if (!(value instanceof Json.Array(var allowed))) return;
        if (allowed.contains(instance)) return;
        context.error("the value is not one of the " + allowed.size() + " values in enum");
    }

    private static void constant(Json value, Json instance, Context context) {
        if (value.equals(instance)) return;
        context.error("the value is not the one value in const");
    }

    private static void multipleOf(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Num(var number)) || !(value instanceof Json.Num(var factor))) return;
        if (Numbers.multiple(number, factor)) return;
        context.error(Numbers.text(number) + " is not a multiple of " + Numbers.text(factor));
    }

    private static void maximum(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Num(var number)) || !(value instanceof Json.Num(var limit))) return;
        if (number <= limit) return;
        context.error(Numbers.text(number) + " is more than the maximum " + Numbers.text(limit));
    }

    private static void exclusiveMaximum(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Num(var number)) || !(value instanceof Json.Num(var limit))) return;
        if (number < limit) return;
        context.error(Numbers.text(number) + " is not less than the exclusiveMaximum " + Numbers.text(limit));
    }

    private static void minimum(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Num(var number)) || !(value instanceof Json.Num(var limit))) return;
        if (number >= limit) return;
        context.error(Numbers.text(number) + " is less than the minimum " + Numbers.text(limit));
    }

    private static void exclusiveMinimum(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Num(var number)) || !(value instanceof Json.Num(var limit))) return;
        if (number > limit) return;
        context.error(Numbers.text(number) + " is not more than the exclusiveMinimum " + Numbers.text(limit));
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
        context.error("the string is " + length(text) + " characters long and maxLength is "
                + Numbers.text(limit));
    }

    private static void minLength(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Str(var text)) || !(value instanceof Json.Num(var limit))) return;
        if (length(text) >= limit) return;
        context.error("the string is " + length(text) + " characters long and minLength is "
                + Numbers.text(limit));
    }

    private static void pattern(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Str(var text)) || !(value instanceof Json.Str(var expression))) return;
        if (Patterns.found(expression, text)) return;
        context.error("the string does not match the pattern " + expression);
    }

    private static void maxItems(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Array(var items)) || !(value instanceof Json.Num(var limit))) return;
        if (items.size() <= limit) return;
        context.error("the array has " + items.size() + " items and maxItems is " + Numbers.text(limit));
    }

    private static void minItems(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Array(var items)) || !(value instanceof Json.Num(var limit))) return;
        if (items.size() >= limit) return;
        context.error("the array has " + items.size() + " items and minItems is " + Numbers.text(limit));
    }

    /// Two items are the same when they are the same JSON value. `1` and `1.0`
    /// are the same number, and `{"a":1,"b":2}` and `{"b":2,"a":1}` are the same
    /// object, because field order is not part of a JSON value.
    private static void uniqueItems(Json value, Json instance, Context context) {
        if (!(value instanceof Json.Bool(var required)) || !required) return;
        if (!(instance instanceof Json.Array(var items))) return;
        var seen = new HashSet<Json>();
        for (var item : items) {
            if (seen.add(item)) continue;
            context.error("the array has the same item more than once and uniqueItems is true");
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
        var count = contained(context);
        if (count <= limit) return;
        context.error(count + " items match contains and maxContains is " + Numbers.text(limit));
    }

    private static void minContains(Json value, Json instance, Context context) {
        if (context.schema().get("contains") == null || !(instance instanceof Json.Array)) return;
        if (!(value instanceof Json.Num(var limit))) return;
        var count = contained(context);
        if (count >= limit) return;
        context.error(count + " items match contains and minContains is " + Numbers.text(limit));
    }

    private static void required(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields)) || !(value instanceof Json.Array(var names))) return;
        for (var name : names) {
            if (!(name instanceof Json.Str(var text)) || fields.containsKey(text)) continue;
            context.error("the object has no property \"" + text + "\" and required asks for it");
        }
    }

    private static void dependentRequired(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields)) || !(value instanceof Json.Object(var members))) return;
        members.forEach((name, needed) -> {
            if (!fields.containsKey(name) || !(needed instanceof Json.Array(var names))) return;
            for (var other : names) {
                if (!(other instanceof Json.Str(var text)) || fields.containsKey(text)) continue;
                context.error("the object has \"" + name + "\", which requires \"" + text + "\"");
            }
        });
    }

    private static void maxProperties(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields)) || !(value instanceof Json.Num(var limit))) return;
        if (fields.size() <= limit) return;
        context.error("the object has " + fields.size() + " properties and maxProperties is "
                + Numbers.text(limit));
    }

    private static void minProperties(Json value, Json instance, Context context) {
        if (!(instance instanceof Json.Object(var fields)) || !(value instanceof Json.Num(var limit))) return;
        if (fields.size() >= limit) return;
        context.error("the object has " + fields.size() + " properties and minProperties is "
                + Numbers.text(limit));
    }
}
