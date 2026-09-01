package json;

import harness.Check;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonTest {

    private JsonTest() {}

    public static void run() throws IOException {
        values();
        escapes();
        events();
        streaming();
        pointers();
        pointerUpdates();
        relativePointers();
        malformed();
    }

    private static void values() {
        var parsed = Json.parse("""
                {"name":"tuul","tags":["java","cli"],"deps":0,"fast":true,"extra":null}
                """);
        Check.that("an object parses to an object", parsed instanceof Json.Object);
        var object = (Json.Object) parsed;
        Check.equal("string field", "tuul", object.string("name", ""));
        Check.equal("array field", 2, object.list("tags").size());
        Check.equal("number field", new Json.Num(0), object.get("deps"));
        Check.equal("boolean field", true, object.flag("fast"));
        Check.equal("null field", Json.NULL, object.get("extra"));
        Check.equal("round trip",
                "{\"name\":\"tuul\",\"tags\":[\"java\",\"cli\"],\"deps\":0,\"fast\":true,\"extra\":null}",
                parsed.text());
        Check.equal("nesting survives",
                "[[1,[2]],{\"a\":{\"b\":[]}}]",
                Json.parse("[[1,[2]],{\"a\":{\"b\":[]}}]").text());
        Check.equal("integral numbers stay integral", "1000", Json.parse("1e3").text());
        Check.equal("fractions do not", "-2.5", Json.parse("-2.5").text());
        Check.equal("field order is insertion order",
                "{\"b\":1,\"a\":2}",
                Json.Object.of().with("b", 1).with("a", 2).text());
        Check.equal("an object can start with one field",
                "{\"kind\":\"missing\"}",
                Json.Object.of("kind", "missing").text());
    }

    private static void escapes() {
        var text = "quote \" backslash \\ newline \n tab \t unicode \u00e4";
        var encoded = Json.of(text).text();
        Check.that("control characters are escaped", !encoded.contains("\n"));
        Check.equal("escapes round trip", new Json.Str(text), Json.parse(encoded));
        Check.equal("\\u escapes are read", new Json.Str("A"), Json.parse("\"\\u0041\""));
    }

    private static void events() {
        var reader = new JsonReader(new StringReader("{\"a\":[1,true]}"));
        var seen = List.of(reader.next(), reader.next(), reader.next(), reader.next(),
                reader.next(), reader.next(), reader.next(), reader.next());
        Check.equal("events arrive in document order",
                List.of(new JsonReader.Event.BeginObject(),
                        new JsonReader.Event.Name("a"),
                        new JsonReader.Event.BeginArray(),
                        new JsonReader.Event.Value(new Json.Num(1)),
                        new JsonReader.Event.Value(Json.TRUE),
                        new JsonReader.Event.EndArray(),
                        new JsonReader.Event.EndObject(),
                        new JsonReader.Event.End()),
                seen);
    }

    private static void streaming() throws IOException {
        var out = new StringWriter();
        var writer = new JsonWriter(out);
        writer.beginObject()
                .name("class").value("invoicing.Invoice")
                .name("implements").beginArray().value("Comparable").value("Serializable").endArray()
                .name("methods").beginArray().endArray()
                .endObject()
                .flush();
        Check.equal("a document can be written without building it",
                "{\"class\":\"invoicing.Invoice\",\"implements\":[\"Comparable\",\"Serializable\"],\"methods\":[]}",
                out.toString());
    }

    private static void pointers() {
        var document = Json.parse("""
                {"foo":["bar","baz"],"":0,"a/b":1,"c%d":2,"e^f":3,
                 "g|h":4,"i\\\\j":5,"k\\\"l":6," ":7,"m~n":8,"ä":9}
                """);
        Check.equal("the empty pointer selects the document", document, document.at(""));
        Check.equal("an object and array pointer selects a value", Json.of("bar"), document.at("/foo/0"));
        Check.equal("an empty reference token selects the empty name", Json.of(0), document.at("/"));
        Check.equal("a slash is unescaped", Json.of(1), document.at("/a~1b"));
        Check.equal("a tilde is unescaped", Json.of(8), document.at("/m~0n"));
        Check.equal("unescaping happens once", List.of("~1"), Pointer.parse("/~01").tokens());
        Check.equal("the URI fragment root selects the document", document, Pointer.fragment("#").get(document));
        Check.equal("URI fragment percent escapes are UTF-8", Json.of(9), Pointer.fragment("#/%C3%A4").get(document));
        Check.equal("a pointer fragment round trips", "#/a~1b", Pointer.parse("/a~1b").toFragment());
        Check.equal("tokens render with escapes", "/a~1b/m~0n", Pointer.ofTokens("a/b", "m~n").toString());
        Check.equal("parent removes the last decoded token", Pointer.parse("/foo"),
                Pointer.parse("/foo/0").parent().orElseThrow());
        Check.equal("last returns the decoded token", "a/b", Pointer.parse("/a~1b").last().orElseThrow());
        Check.that("a missing value has an empty optional", document.find("/gone").isEmpty());
        Check.throwing("a missing value fails strict extraction", () -> document.at("/gone"));
        Check.throwing("a dash does not select an array item", () -> document.at("/foo/-"));
        Check.throwing("an array index cannot have a leading zero", () -> document.at("/foo/01"));
        Check.throwing("a malformed escape is refused", () -> Pointer.parse("/a~2b"));
        Check.throwing("a pointer must start with slash", () -> Pointer.parse("foo"));
        Check.throwing("a URI representation must start with hash", () -> Pointer.fragment("/foo"));
        Check.throwing("malformed percent escapes are refused", () -> Pointer.fragment("#/%GG"));
        Check.throwing("raw spaces are refused in a URI fragment", () -> Pointer.fragment("#/ "));
    }

    private static void pointerUpdates() {
        var original = Json.parse("{\"person\":{\"name\":\"Ada\"},\"tags\":[\"java\",\"json\"]}");
        var renamed = original.set("/person/name", "Grace");
        Check.equal("set replaces an object member", Json.of("Grace"), renamed.at("/person/name"));
        Check.equal("set does not change its input", Json.of("Ada"), original.at("/person/name"));
        Check.equal("set creates missing object parents", Json.of(true),
                original.set("/build/release/ready", true).at("/build/release/ready"));
        Check.equal("dash appends to an array", Json.of("pointer"), original.set("/tags/-", "pointer").at("/tags/2"));
        Check.equal("set replaces an array item", Json.of("modern"), original.set("/tags/0", "modern").at("/tags/0"));
        Check.equal("update receives the selected value", Json.of("ADA"), original
                .update("/person/name", value -> Json.of(value.string("").toUpperCase())).at("/person/name"));
        Check.equal("remove deletes an object member", Json.Object.of(), original.remove("/person/name").at("/person"));
        Check.equal("remove closes an array gap", Json.of("json"), original.remove("/tags/0").at("/tags/0"));
        var replacements = new LinkedHashMap<String, Json>();
        replacements.put("/person/name", Json.of("Lin"));
        replacements.put("/person/active", Json.TRUE);
        var changed = original.set(replacements);
        Check.equal("batch set applies every pointer", Json.parse("{\"name\":\"Lin\",\"active\":true}"),
                changed.at("/person"));
        Check.equal("compiled batch set applies every pointer", Json.parse("{\"name\":\"Kay\",\"active\":true}"),
                Pointer.set(original, Map.of(
                        Pointer.parse("/person/name"), Json.of("Kay"),
                        Pointer.parse("/person/active"), Json.TRUE)).at("/person"));
        Check.equal("the root can be replaced", Json.of("new"), original.set("", "new"));
        Check.throwing("the root cannot be removed", () -> original.remove(""));
        Check.throwing("set does not traverse a scalar", () -> original.set("/person/name/first", "Ada"));
        Check.equal("set does not infer an array", Json.of("Ada"),
                Json.Object.of().set("/items/0/name", "Ada").at("/items/0/name"));
    }

    private static void relativePointers() {
        var document = Json.parse("""
                {"foo":["bar","baz"],"highly":{"nested":{"objects":true}}}
                """);
        var baz = Pointer.parse("/foo/1");
        Check.equal("relative zero selects the starting value", Json.of("baz"),
                RelativePointer.parse("0").get(document, baz));
        Check.equal("relative up then down selects a sibling", Json.of("bar"),
                RelativePointer.parse("1/0").get(document, baz));
        Check.equal("relative index manipulation selects a sibling", Json.of("bar"),
                RelativePointer.parse("0-1").get(document, baz));
        Check.equal("relative traversal can return to the root", Json.TRUE,
                RelativePointer.parse("2/highly/nested/objects").get(document, baz));
        Check.equal("hash returns an array index", Json.of(1), RelativePointer.parse("0#").get(document, baz));
        Check.equal("hash returns an object name", Json.of("foo"), RelativePointer.parse("1#").get(document, baz));
        var nested = Pointer.parse("/highly/nested");
        Check.equal("relative zero descends from an object member", Json.TRUE,
                RelativePointer.parse("0/objects").get(document, nested));
        Check.equal("hash returns the starting object name", Json.of("nested"),
                RelativePointer.parse("0#").get(document, nested));
        Check.equal("a relative pointer resolves to an absolute pointer", Pointer.parse("/foo/0"),
                RelativePointer.parse("0-1").resolve(document, baz));
        Check.equal("a relative pointer updates immutably", Json.of("qux"),
                RelativePointer.parse("0-1").set(document, baz, Json.of("qux")).at("/foo/0"));
        var selection = document.select("/foo/1");
        Check.equal("a selection evaluates a relative pointer", Json.of("bar"), selection.at("0-1"));
        Check.equal("a selection returns a relative array index", Json.of(1), selection.at("0#"));
        Check.equal("a selection moves to a relative location", Json.of("bar"), selection.move("0-1").value());
        Check.equal("a selection returns a complete edited document", Json.of("qux"),
                selection.set("0-1", Json.of("qux")).at("/foo/0"));
        Check.throwing("a relative pointer cannot move above root", () -> RelativePointer.parse("3").get(document, baz));
        Check.throwing("index manipulation requires an array item", () -> RelativePointer.parse("0+1").get(document, nested));
        Check.throwing("index manipulation stays inside its array", () -> RelativePointer.parse("0+1").get(document, baz));
        Check.throwing("hash cannot return the root index", () -> RelativePointer.parse("0#").get(document, Pointer.root()));
        Check.throwing("hash has no absolute target pointer", () -> RelativePointer.parse("0#").resolve(document, baz));
        Check.throwing("relative pointer integers have no leading zero", () -> RelativePointer.parse("01/foo"));
        Check.throwing("relative index shifts have no leading zero", () -> RelativePointer.parse("0+01"));
        Check.throwing("a relative pointer needs an integer", () -> RelativePointer.parse("/foo"));
    }

    private static void malformed() {
        Check.throwing("truncated input is refused", () -> Json.parse("{\"a\":"));
        Check.throwing("bad literals are refused", () -> Json.parse("tru"));
        Check.throwing("bad numbers are refused", () -> Json.parse("1.2.3"));
    }
}
