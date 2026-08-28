package jsonschema;

import java.io.IOException;
import json.Json;
import json.JsonWriter;

/// One line of output: a reason the instance failed, or a value a keyword
/// produced.
///
/// The two cases are separate types because they carry different payloads and
/// never appear together. An [Error] carries a message for a person. An
/// [Annotation] carries a JSON value for a program, and `unevaluatedItems` and
/// `unevaluatedProperties` read those values to do their work.
public sealed interface Unit {

    Location at();

    record Error(Location at, String message) implements Unit {}

    record Annotation(Location at, Json value) implements Unit {}

    /// Writes this unit as one object of the `basic` output format.
    default void write(JsonWriter out) throws IOException {
        out.beginObject()
                .name("keywordLocation").value(at().keywordLocation())
                .name("absoluteKeywordLocation").value(at().absoluteKeywordLocation().toString())
                .name("instanceLocation").value(at().instanceLocation());
        switch (this) {
            case Error(var ignored, var message) -> out.name("error").value(message);
            case Annotation(var ignored, var value) -> out.name("annotation").value(value);
        }
        out.endObject();
    }
}
