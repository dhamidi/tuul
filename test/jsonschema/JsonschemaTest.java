package jsonschema;

import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import json.Json;

public final class JsonschemaTest {

    private static final Store SHARED = Store.of();

    private JsonschemaTest() {}

    public static void run() throws IOException {
        types();
        numbers();
        strings();
        arrays();
        objects();
        booleans();
        applicators();
        explanations();
        references();
        dynamic();
        unevaluated();
        refusals();
        vocabularies();
        formats();
        output();
    }

    private static void types() {
        Check.that("type names the one type an instance may have", valid("{\"type\":\"string\"}", "\"a\""));
        Check.that("type rejects every other type", !valid("{\"type\":\"string\"}", "1"));
        Check.that("type accepts any of the types in an array",
                valid("{\"type\":[\"string\",\"null\"]}", "null"));
        Check.that("integer is a number with no fractional part", valid("{\"type\":\"integer\"}", "1.0"));
        Check.that("a number with a fractional part is not an integer",
                !valid("{\"type\":\"integer\"}", "1.5"));
        Check.that("an integer is also a number", valid("{\"type\":\"number\"}", "3"));
        Check.that("null is its own type", valid("{\"type\":\"null\"}", "null"));
        Check.that("a boolean is not a string", !valid("{\"type\":\"string\"}", "true"));
        Check.that("const asks for one exact value", valid("{\"const\":{\"a\":[1]}}", "{\"a\":[1]}"));
        Check.that("const compares the whole value", !valid("{\"const\":{\"a\":[1]}}", "{\"a\":[2]}"));
        Check.that("enum asks for one of a list", valid("{\"enum\":[1,\"a\",null]}", "\"a\""));
        Check.that("a value outside enum fails", !valid("{\"enum\":[1,\"a\",null]}", "2"));
    }

    private static void numbers() {
        Check.that("multipleOf divides with nothing left over", valid("{\"multipleOf\":1.5}", "4.5"));
        Check.that("multipleOf survives decimals a double cannot hold exactly",
                valid("{\"multipleOf\":0.0001}", "0.0075"));
        Check.that("a value that does not divide fails multipleOf", !valid("{\"multipleOf\":2}", "7"));
        Check.that("maximum is inclusive", valid("{\"maximum\":3}", "3"));
        Check.that("a value above maximum fails", !valid("{\"maximum\":3}", "3.5"));
        Check.that("exclusiveMaximum is not inclusive", !valid("{\"exclusiveMaximum\":3}", "3"));
        Check.that("minimum is inclusive", valid("{\"minimum\":3}", "3"));
        Check.that("a value below minimum fails", !valid("{\"minimum\":3}", "2"));
        Check.that("exclusiveMinimum is not inclusive", !valid("{\"exclusiveMinimum\":3}", "3"));
        Check.that("a numeric keyword says nothing about a string", valid("{\"maximum\":3}", "\"9\""));
    }

    private static void strings() {
        Check.that("maxLength counts characters", valid("{\"maxLength\":3}", "\"abc\""));
        Check.that("a longer string fails maxLength", !valid("{\"maxLength\":3}", "\"abcd\""));
        Check.that("maxLength counts code points and not UTF-16 units",
                valid("{\"maxLength\":2}", "\"a\\ud83d\\ude00\""));
        Check.that("minLength counts characters", valid("{\"minLength\":3}", "\"abc\""));
        Check.that("a shorter string fails minLength", !valid("{\"minLength\":3}", "\"ab\""));
        Check.that("pattern looks anywhere in the string", valid("{\"pattern\":\"b\"}", "\"abc\""));
        Check.that("a string without the pattern fails", !valid("{\"pattern\":\"^b\"}", "\"abc\""));
    }

    private static void arrays() {
        Check.that("maxItems counts items", valid("{\"maxItems\":2}", "[1,2]"));
        Check.that("a longer array fails maxItems", !valid("{\"maxItems\":2}", "[1,2,3]"));
        Check.that("minItems counts items", valid("{\"minItems\":2}", "[1,2]"));
        Check.that("a shorter array fails minItems", !valid("{\"minItems\":2}", "[1]"));
        Check.that("uniqueItems refuses a repeated value", !valid("{\"uniqueItems\":true}", "[1,2,1]"));
        Check.that("uniqueItems compares whole values",
                !valid("{\"uniqueItems\":true}", "[{\"a\":1,\"b\":2},{\"b\":2,\"a\":1}]"));
        Check.that("uniqueItems false allows anything", valid("{\"uniqueItems\":false}", "[1,1]"));
        Check.that("contains asks for at least one matching item",
                valid("{\"contains\":{\"type\":\"number\"}}", "[\"a\",1]"));
        Check.that("contains fails when no item matches",
                !valid("{\"contains\":{\"type\":\"number\"}}", "[\"a\"]"));
        Check.that("minContains counts the matching items",
                !valid("{\"contains\":{\"type\":\"number\"},\"minContains\":2}", "[\"a\",1]"));
        Check.that("minContains zero makes contains match anything",
                valid("{\"contains\":{\"type\":\"number\"},\"minContains\":0}", "[\"a\"]"));
        Check.that("maxContains puts a ceiling on the matching items",
                !valid("{\"contains\":{\"type\":\"number\"},\"maxContains\":1}", "[1,2]"));
        Check.that("prefixItems checks each item against the schema at its index",
                valid("{\"prefixItems\":[{\"type\":\"string\"},{\"type\":\"number\"}]}", "[\"a\",1,true]"));
        Check.that("an item that does not match its prefixItems schema fails",
                !valid("{\"prefixItems\":[{\"type\":\"string\"}]}", "[1]"));
        Check.that("items covers what prefixItems did not",
                !valid("{\"prefixItems\":[{\"type\":\"string\"}],\"items\":{\"type\":\"number\"}}",
                        "[\"a\",\"b\"]"));
    }

    private static void objects() {
        Check.that("required names properties an object must carry",
                valid("{\"required\":[\"a\"]}", "{\"a\":1}"));
        Check.that("a missing required property fails", !valid("{\"required\":[\"a\"]}", "{\"b\":1}"));
        Check.that("dependentRequired asks for one property only when another is there",
                valid("{\"dependentRequired\":{\"a\":[\"b\"]}}", "{\"c\":1}"));
        Check.that("a property whose dependent is missing fails",
                !valid("{\"dependentRequired\":{\"a\":[\"b\"]}}", "{\"a\":1}"));
        Check.that("maxProperties counts properties", !valid("{\"maxProperties\":1}", "{\"a\":1,\"b\":2}"));
        Check.that("minProperties counts properties", !valid("{\"minProperties\":2}", "{\"a\":1}"));
        Check.that("properties checks the value of each named property",
                !valid("{\"properties\":{\"a\":{\"type\":\"string\"}}}", "{\"a\":1}"));
        Check.that("patternProperties checks every property whose name matches",
                !valid("{\"patternProperties\":{\"^x\":{\"type\":\"string\"}}}", "{\"xa\":1}"));
        Check.that("additionalProperties covers the names properties and patternProperties left",
                !valid("{\"properties\":{\"a\":true},\"additionalProperties\":false}", "{\"a\":1,\"b\":2}"));
        Check.that("additionalProperties leaves the named properties alone",
                valid("{\"properties\":{\"a\":true},\"additionalProperties\":false}", "{\"a\":1}"));
        Check.that("propertyNames checks each name as a string",
                !valid("{\"propertyNames\":{\"maxLength\":1}}", "{\"ab\":1}"));
        Check.that("dependentSchemas applies a schema only when a property is there",
                !valid("{\"dependentSchemas\":{\"a\":{\"required\":[\"b\"]}}}", "{\"a\":1}"));
    }

    private static void booleans() {
        Check.that("the schema true accepts every instance", valid("true", "{\"a\":[1,2]}"));
        Check.that("the schema false rejects every instance", !valid("false", "null"));
        Check.that("a boolean works where a subschema does",
                !valid("{\"properties\":{\"a\":false}}", "{\"a\":1}"));
        Check.that("a boolean subschema that is true accepts anything",
                valid("{\"properties\":{\"a\":true}}", "{\"a\":1}"));
        Check.that("false as additionalProperties forbids every other name",
                !valid("{\"additionalProperties\":false}", "{\"a\":1}"));
    }

    private static void applicators() {
        Check.that("allOf asks for every branch",
                !valid("{\"allOf\":[{\"type\":\"string\"},{\"maxLength\":1}]}", "\"ab\""));
        Check.that("anyOf asks for at least one branch",
                valid("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}", "1"));
        Check.that("anyOf fails when no branch matches",
                !valid("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}", "null"));
        Check.that("oneOf asks for exactly one branch",
                !valid("{\"oneOf\":[{\"type\":\"number\"},{\"type\":\"integer\"}]}", "1"));
        Check.that("oneOf passes when a single branch matches",
                valid("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}", "1"));
        Check.that("not inverts its subschema", valid("{\"not\":{\"type\":\"string\"}}", "1"));
        Check.that("not fails when the subschema matches", !valid("{\"not\":{\"type\":\"string\"}}", "\"a\""));
        Check.that("then applies when if matches",
                !valid("{\"if\":{\"type\":\"string\"},\"then\":{\"maxLength\":1}}", "\"ab\""));
        Check.that("else applies when if does not match",
                !valid("{\"if\":{\"type\":\"string\"},\"else\":{\"type\":\"number\"}}", "null"));
        Check.that("if on its own never fails an instance",
                valid("{\"if\":{\"type\":\"string\"}}", "null"));
    }

    /// What a failing applicator leaves in the output, and where it points.
    private static void explanations() {
        var choice = result("""
                {"anyOf":[{"required":["card"]},
                          {"required":["bank"]},
                          {"properties":{"cash":{"const":true}},"required":["cash"]}]}""",
                "{\"paypal\":\"a@b.c\",\"cash\":\"maybe\"}");
        Check.equal("a failing anyOf reports the summary and one unit for every branch",
                4, choice.errors().size());
        Check.that("the summary counts the schemas the value matched none of",
                messageAt(choice, "/anyOf").contains("none of the 3 schemas in anyOf"));
        Check.that("the first branch says what it wanted",
                messageAt(choice, "/anyOf/0/required").contains("\"card\""));
        Check.that("the second branch says what it wanted",
                messageAt(choice, "/anyOf/1/required").contains("\"bank\""));
        Check.that("a branch reports the keyword that failed inside it, however deep it sits",
                errorAt(choice, "/anyOf/2/properties/cash/const", "/cash"));

        var overlap = result(
                "{\"oneOf\":[{\"type\":\"number\"},{\"type\":\"integer\"},{\"type\":\"string\"}]}", "1");
        Check.equal("a oneOf that matched too much reports the summary alone", 1, overlap.errors().size());
        Check.that("the summary names every branch that matched",
                messageAt(overlap, "/oneOf").contains("/oneOf/0")
                        && messageAt(overlap, "/oneOf").contains("/oneOf/1"));

        var neither = result("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"boolean\"}]}", "1");
        Check.equal("a oneOf that matched nothing reports every branch", 3, neither.errors().size());
        Check.that("each branch says which type it allowed",
                messageAt(neither, "/oneOf/0/type").contains("string")
                        && messageAt(neither, "/oneOf/1/type").contains("boolean"));

        var missed = result("{\"contains\":{\"type\":\"number\",\"minimum\":10}}", "[\"a\",3]");
        Check.equal("a failing contains reports the summary and one unit for every item",
                3, missed.errors().size());
        Check.that("the first item says it was the wrong type", errorAt(missed, "/contains/type", "/0"));
        Check.that("the second item says it was too small", errorAt(missed, "/contains/minimum", "/1"));

        var hidden = result("""
                {"anyOf":[{"properties":{"a":{"type":"string"}},"required":["z"]}],
                 "unevaluatedProperties":false}""",
                "{\"a\":\"x\"}");
        Check.that("a property that only a failed branch matched is still unevaluated",
                errorAt(hidden, "/unevaluatedProperties", "/a"));
        Check.that("the failed branch still says why it failed", errorAt(hidden, "/anyOf/0/required", ""));
        Check.that("no annotation of a failed branch reaches the output",
                hidden.annotations().stream()
                        .noneMatch(unit -> unit.at().keywordLocation().startsWith("/anyOf")));

        var tooLong = result("{\"if\":{\"type\":\"string\"},\"then\":{\"maxLength\":1}}", "\"ab\"");
        Check.equal("a conditional reports what then found and nothing else", 1, tooLong.errors().size());
        Check.that("the error points at the keyword inside then", errorAt(tooLong, "/then/maxLength", ""));

        var otherwise = result("{\"if\":{\"type\":\"string\"},\"else\":{\"type\":\"number\"}}", "null");
        Check.equal("a conditional reports what else found and nothing else", 1, otherwise.errors().size());
        Check.that("the error points at the keyword inside else", errorAt(otherwise, "/else/type", ""));

        var untaken = result("{\"if\":{\"type\":\"string\"},\"then\":true}", "1");
        Check.that("an if that fails is not an error and reports none",
                untaken.valid() && untaken.errors().isEmpty());
    }

    private static void references() {
        Check.that("a $ref to $defs applies the schema it names",
                !valid("{\"$defs\":{\"short\":{\"maxLength\":1}},\"$ref\":\"#/$defs/short\"}", "\"ab\""));
        var anchored = SHARED.compile(Json.parse("""
                {"$id":"https://example.com/anchored",
                 "$defs":{"positive":{"$anchor":"positive","type":"number","exclusiveMinimum":0}},
                 "$ref":"#positive"}"""));
        Check.that("a $ref to an $anchor applies the schema that carries it",
                anchored.validate(Json.parse("1")).valid());
        Check.that("a $ref to an $anchor still asserts",
                !anchored.validate(Json.parse("-1")).valid());

        var store = Store.of();
        store.add(Json.parse("{\"$id\":\"https://example.com/name\",\"type\":\"string\",\"minLength\":1}"));
        store.add(Json.parse("""
                {"$id":"https://example.com/person",
                 "type":"object",
                 "properties":{"name":{"$ref":"name"}},
                 "required":["name"]}"""));
        var person = store.compile(URI.create("https://example.com/person"));
        Check.that("a $ref resolves against another schema registered in the store",
                person.validate(Json.parse("{\"name\":\"ada\"}")).valid());
        Check.that("the referenced schema asserts on the instance it reaches",
                !person.validate(Json.parse("{\"name\":\"\"}")).valid());
        Check.that("a relative $ref resolves against the base URI of its own resource",
                !person.validate(Json.parse("{\"name\":1}")).valid());
    }

    /// The list-of-strings case from the specification. It is the case that
    /// shows what `$dynamicRef` does and `$ref` cannot: the reference inside
    /// `list` comes back to the anchor of `string-list`, which `list` has never
    /// heard of.
    private static void dynamic() {
        var store = Store.of();
        store.add(Json.parse("""
                {"$id":"https://example.com/list",
                 "$defs":{"items":{"$dynamicAnchor":"items"}},
                 "type":"array",
                 "items":{"$dynamicRef":"#items"}}"""));
        store.add(Json.parse("""
                {"$id":"https://example.com/string-list",
                 "$ref":"list",
                 "$defs":{"items":{"$dynamicAnchor":"items","type":"string"}}}"""));
        var list = store.compile(URI.create("https://example.com/list"));
        var strings = store.compile(URI.create("https://example.com/string-list"));
        Check.that("on its own the recursive list accepts items of any type",
                list.validate(Json.parse("[\"a\",1]")).valid());
        Check.that("through string-list the same reference lands on the string schema",
                !strings.validate(Json.parse("[\"a\",1]")).valid());
        Check.that("string-list accepts a list of strings",
                strings.validate(Json.parse("[\"a\",\"b\"]")).valid());

        var plain = Store.of();
        plain.add(Json.parse("""
                {"$id":"https://example.com/plain-list",
                 "$defs":{"items":{"$anchor":"items"}},
                 "type":"array",
                 "items":{"$ref":"#items"}}"""));
        plain.add(Json.parse("""
                {"$id":"https://example.com/plain-string-list",
                 "$ref":"plain-list",
                 "$defs":{"items":{"$anchor":"items","type":"string"}}}"""));
        Check.that("a plain $ref never leaves the resource it was written in, so the same shape accepts a number",
                plain.compile(URI.create("https://example.com/plain-string-list"))
                        .validate(Json.parse("[\"a\",1]")).valid());

        var tree = Store.of();
        tree.add(Json.parse("""
                {"$id":"https://example.com/tree",
                 "$dynamicAnchor":"node",
                 "type":"object",
                 "properties":{"data":true,
                               "children":{"type":"array","items":{"$dynamicRef":"#node"}}}}"""));
        tree.add(Json.parse("""
                {"$id":"https://example.com/strict-tree",
                 "$dynamicAnchor":"node",
                 "$ref":"tree",
                 "unevaluatedProperties":false}"""));
        var strict = tree.compile(URI.create("https://example.com/strict-tree"));
        Check.that("a strict tree accepts a tree with only the properties the tree defines",
                strict.validate(Json.parse("{\"data\":1,\"children\":[{\"data\":2}]}")).valid());
        Check.that("a strict tree refuses an extra property in a child, many levels down",
                !strict.validate(Json.parse("{\"data\":1,\"children\":[{\"daat\":2}]}")).valid());
    }

    private static void unevaluated() {
        Check.that("unevaluatedProperties sees the properties an allOf branch matched",
                valid("""
                        {"allOf":[{"properties":{"a":{"type":"string"}}}],
                         "properties":{"b":{"type":"string"}},
                         "unevaluatedProperties":false}""",
                        "{\"a\":\"x\",\"b\":\"y\"}"));
        Check.that("unevaluatedProperties refuses a property that nothing evaluated",
                !valid("""
                        {"allOf":[{"properties":{"a":{"type":"string"}}}],
                         "unevaluatedProperties":false}""",
                        "{\"a\":\"x\",\"c\":\"z\"}"));
        Check.that("additionalProperties cannot see into an allOf branch, which is why unevaluated exists",
                !valid("{\"allOf\":[{\"properties\":{\"a\":true}}],\"additionalProperties\":false}",
                        "{\"a\":1}"));
        Check.that("unevaluatedProperties applies its own schema to what is left",
                !valid("{\"properties\":{\"a\":true},\"unevaluatedProperties\":{\"type\":\"string\"}}",
                        "{\"a\":1,\"b\":2}"));
        Check.that("the annotations of a failed branch do not count as evaluated",
                !valid("""
                        {"anyOf":[{"properties":{"a":{"type":"string"}},"required":["z"]},
                                  {"required":["a"]}],
                         "unevaluatedProperties":false}""",
                        "{\"a\":1}"));
        Check.that("unevaluatedItems counts what prefixItems and contains covered",
                valid("""
                        {"prefixItems":[{"type":"string"}],
                         "contains":{"type":"number"},
                         "unevaluatedItems":false}""",
                        "[\"a\",1,2]"));
        Check.that("unevaluatedItems refuses an item that neither prefixItems nor contains reached",
                !valid("""
                        {"prefixItems":[{"type":"string"}],
                         "contains":{"type":"number"},
                         "unevaluatedItems":false}""",
                        "[\"a\",1,true]"));
        Check.that("items true covers every item past prefixItems",
                valid("{\"prefixItems\":[{\"type\":\"string\"}],\"items\":true,\"unevaluatedItems\":false}",
                        "[\"a\",1,true]"));
        Check.that("unevaluatedItems applies its own schema to the items that are left",
                !valid("{\"prefixItems\":[true],\"unevaluatedItems\":{\"type\":\"string\"}}", "[1,2]"));
    }

    private static void refusals() {
        var store = Store.of();
        var missing = failure(() -> store.compile(Json.parse("{\"$ref\":\"https://example.com/gone\"}")));
        Check.that("a $ref to a URI the store does not hold refuses the schema",
                missing.contains("no schema at https://example.com/gone"));
        Check.that("the refusal says the schema has to be added rather than fetched",
                missing.contains("never fetches"));
        Check.that("the refusal names the reference that could not be resolved",
                missing.contains("$ref"));

        store.add(Json.parse("""
                {"$id":"https://tuul.example/meta/mystery",
                 "$schema":"https://json-schema.org/draft/2020-12/schema",
                 "$vocabulary":{"https://tuul.example/vocab/mystery":true}}"""));
        var unknown = failure(() -> store.compile(Json.parse(
                "{\"$schema\":\"https://tuul.example/meta/mystery\",\"type\":\"string\"}")));
        Check.that("a meta-schema that requires an unknown vocabulary refuses the schema",
                unknown.contains("requires the vocabulary https://tuul.example/vocab/mystery"));

        store.add(Json.parse("""
                {"$id":"https://tuul.example/meta/optional",
                 "$schema":"https://json-schema.org/draft/2020-12/schema",
                 "$vocabulary":{"https://json-schema.org/draft/2020-12/vocab/validation":true,
                                "https://tuul.example/vocab/mystery":false}}"""));
        var lenient = store.compile(Json.parse(
                "{\"$schema\":\"https://tuul.example/meta/optional\",\"type\":\"string\"}"));
        Check.that("an unknown vocabulary declared false is ignored",
                !lenient.validate(Json.parse("1")).valid());

        Check.throwing("a URI that names no schema in the store cannot be compiled",
                () -> Store.of().compile(URI.create("https://example.com/nothing")));
    }

    private static void vocabularies() {
        var store = Store.of();
        store.vocabulary(Vocabulary.of(URI.create("https://tuul.example/vocab/even"),
                Keyword.of("evenLength", (value, instance, context) -> {
                    if (!(value instanceof Json.Bool(var wanted)) || !wanted) return;
                    if (!(instance instanceof Json.Str(var text))) return;
                    if (text.length() % 2 == 0) return;
                    context.error("the string has an odd number of characters");
                })));
        store.add(Json.parse("""
                {"$id":"https://tuul.example/meta/even",
                 "$schema":"https://json-schema.org/draft/2020-12/schema",
                 "$vocabulary":{"https://json-schema.org/draft/2020-12/vocab/core":true,
                                "https://json-schema.org/draft/2020-12/vocab/validation":true,
                                "https://tuul.example/vocab/even":true},
                 "$dynamicAnchor":"meta"}"""));
        var schema = store.compile(Json.parse("""
                {"$schema":"https://tuul.example/meta/even","type":"string","evenLength":true}"""));
        Check.that("a keyword from a caller's vocabulary runs", !schema.validate(Json.parse("\"abc\"")).valid());
        Check.that("the same keyword passes what it accepts", schema.validate(Json.parse("\"ab\"")).valid());
        Check.that("the vocabularies the meta-schema also names keep working",
                !schema.validate(Json.parse("12")).valid());
        Check.that("a schema on the standard meta-schema never sees the caller's keyword",
                valid("{\"type\":\"string\",\"evenLength\":true}", "\"abc\""));
    }

    private static void formats() {
        Check.that("format does not assert by default", valid("{\"format\":\"ipv4\"}", "\"not-an-address\""));
        var annotated = SHARED.compile(Json.parse("{\"format\":\"ipv4\"}")).validate(Json.parse("\"nope\""));
        Check.equal("format still records what the author said", 1, annotated.annotations().size());

        var strict = Store.of().asserting();
        Check.that("format asserts once the caller opts in",
                !strict.compile(Json.parse("{\"format\":\"ipv4\"}")).validate(Json.parse("\"nope\"")).valid());
        Check.that("a value the format accepts still passes",
                strict.compile(Json.parse("{\"format\":\"ipv4\"}")).validate(Json.parse("\"10.0.0.1\"")).valid());
        Check.that("date-time follows RFC 3339",
                strict.compile(Json.parse("{\"format\":\"date-time\"}"))
                        .validate(Json.parse("\"2026-08-28T05:00:00Z\"")).valid());
        Check.that("a date without an offset is not a date-time",
                !strict.compile(Json.parse("{\"format\":\"date-time\"}"))
                        .validate(Json.parse("\"2026-08-28\"")).valid());
        Check.that("uuid asks for the 8-4-4-4-12 shape",
                !strict.compile(Json.parse("{\"format\":\"uuid\"}")).validate(Json.parse("\"abc\"")).valid());
        Check.that("ipv6 accepts a shortened address",
                strict.compile(Json.parse("{\"format\":\"ipv6\"}")).validate(Json.parse("\"::1\"")).valid());
        Check.that("json-pointer refuses a lone tilde",
                !strict.compile(Json.parse("{\"format\":\"json-pointer\"}"))
                        .validate(Json.parse("\"/a~\"")).valid());
        Check.that("a format the store does not know never fails an instance",
                strict.compile(Json.parse("{\"format\":\"invented\"}")).validate(Json.parse("\"x\"")).valid());

        var mine = Store.of().format(Format.of("shout", text -> text.equals(text.toUpperCase()))).asserting();
        Check.that("a caller's own format asserts like any other",
                !mine.compile(Json.parse("{\"format\":\"shout\"}")).validate(Json.parse("\"quiet\"")).valid());
        Check.that("a caller's own format accepts what it is written for",
                mine.compile(Json.parse("{\"format\":\"shout\"}")).validate(Json.parse("\"LOUD\"")).valid());
    }

    private static void output() throws IOException {
        var store = Store.of();
        store.add(Json.parse("""
                {"$id":"https://example.com/lengths",
                 "$defs":{"short":{"maxLength":2}},
                 "properties":{"a":{"$ref":"#/$defs/short"}}}"""));
        var result = store.compile(URI.create("https://example.com/lengths"))
                .validate(Json.parse("{\"a\":\"long\"}"));
        Check.equal("a failure produces one unit for each keyword that failed", 1, result.errors().size());
        var unit = (Unit.Error) result.errors().getFirst();
        Check.equal("the keyword location keeps the $ref step that was taken",
                "/properties/a/$ref/maxLength", unit.at().keywordLocation());
        Check.equal("the absolute keyword location names the keyword inside its own resource",
                URI.create("https://example.com/lengths#/$defs/short/maxLength"),
                unit.at().absoluteKeywordLocation());
        Check.equal("the instance location names the part of the instance that failed",
                "/a", unit.at().instanceLocation());

        Check.equal("flag output is the verdict and nothing else", "{\"valid\":false}", flag(result));
        Check.equal("basic output carries the units under errors",
                "{\"valid\":false,\"errors\":[{\"keywordLocation\":\"/properties/a/$ref/maxLength\","
                        + "\"absoluteKeywordLocation\":\"https://example.com/lengths#/$defs/short/maxLength\","
                        + "\"instanceLocation\":\"/a\","
                        + "\"error\":\"the string is 4 characters long and maxLength is 2\"}]}",
                basic(result));

        var passing = SHARED.compile(Json.parse("{\"title\":\"a name\",\"type\":\"string\"}"))
                .validate(Json.parse("\"ada\""));
        Check.equal("a passing instance carries annotations instead of errors",
                "{\"valid\":true,\"annotations\":[{\"keywordLocation\":\"/title\","
                        + "\"absoluteKeywordLocation\":\"" + passing.annotations().getFirst().at()
                                .absoluteKeywordLocation() + "\","
                        + "\"instanceLocation\":\"\",\"annotation\":\"a name\"}]}",
                basic(passing));
        Check.equal("flag output of a passing instance says so", "{\"valid\":true}", flag(passing));
    }

    private static boolean valid(String schema, String instance) {
        return result(schema, instance).valid();
    }

    private static Output result(String schema, String instance) {
        return SHARED.compile(Json.parse(schema)).validate(Json.parse(instance));
    }

    /// True when the output holds an error produced by the keyword at
    /// `keywordLocation` about the part of the instance at `instanceLocation`.
    private static boolean errorAt(Output output, String keywordLocation, String instanceLocation) {
        return output.errors().stream()
                .anyMatch(unit -> unit.at().keywordLocation().equals(keywordLocation)
                        && unit.at().instanceLocation().equals(instanceLocation));
    }

    /// The message of the first error at `keywordLocation`, or an empty string
    /// when there is none, so that a check reads as one expression.
    private static String messageAt(Output output, String keywordLocation) {
        return output.errors().stream()
                .filter(unit -> unit.at().keywordLocation().equals(keywordLocation))
                .findFirst()
                .map(unit -> ((Unit.Error) unit).message())
                .orElse("");
    }

    private static String flag(Output output) throws IOException {
        var out = new StringWriter();
        output.flag(out);
        return out.toString();
    }

    private static String basic(Output output) throws IOException {
        var out = new StringWriter();
        output.basic(out);
        return out.toString();
    }

    private static String failure(Runnable body) {
        try {
            body.run();
            return "";
        } catch (RuntimeException refused) {
            return String.valueOf(refused.getMessage());
        }
    }
}
