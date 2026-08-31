package json;

import harness.Check;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

public final class JsonTest {

    private JsonTest() {}

    public static void run() throws IOException {
        values();
        escapes();
        events();
        streaming();
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

    private static void malformed() {
        Check.throwing("truncated input is refused", () -> Json.parse("{\"a\":"));
        Check.throwing("bad literals are refused", () -> Json.parse("tru"));
        Check.throwing("bad numbers are refused", () -> Json.parse("1.2.3"));
    }
}
