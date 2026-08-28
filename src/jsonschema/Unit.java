package jsonschema;

import java.io.IOException;
import json.Json;
import json.JsonWriter;

/// One line of output. A unit is either a reason the instance failed or a value
/// a keyword produced.
///
/// The two cases are separate types because they carry different payloads and
/// never appear together. An [Error] carries a message for a person and a
/// `params` value for a program. An [Annotation] carries a JSON value for a
/// program, and `unevaluatedItems` and `unevaluatedProperties` read those
/// values to do their work.
///
/// ## What an error message says
///
/// This package writes the messages that Ajv writes, because Ajv is the
/// validator whose errors most people who read JSON Schema output have read
/// before. A message states what the instance must be and never restates
/// where the instance is, so it finishes the sentence that the instance
/// location starts: `/username must NOT have fewer than 3 characters`. Every
/// message begins with `must`.
///
/// This package diverges from Ajv in one place, and [Validation] says where.
/// Ajv appends a fixed plural and writes `1 characters`, while this package
/// writes `1 character`, because a message that a person reads should read as
/// English.
///
/// A message never names the value that failed. [Error#params()] carries that
/// instead, as a JSON object that follows Ajv's `params` convention:
/// `minLength` reports `{"limit":3,"actual":1}`, `type` reports
/// `{"expected":["string"],"actual":"number"}`, and `required` reports
/// `{"missingProperty":"card"}`. A keyword with no detail worth reporting
/// carries [json.Json#NULL], and [#write(JsonWriter)] then leaves the field
/// out, the same way [jsonrpc2.Failure] leaves out a null `data`.
///
/// The 2020-12 output format allows the extra field. Its `outputUnit`
/// definition lists the properties it knows about, requires three of them, and
/// declares neither `additionalProperties` nor `unevaluatedProperties`, so a
/// unit may carry fields of its own.
public sealed interface Unit {

    Location at();

    /// A keyword that rejected the instance, the message it rejected it with,
    /// and the detail that the message leaves out.
    record Error(Location at, String message, Json params) implements Unit {

        /// An error with no detail worth reporting.
        public static Error of(Location at, String message) {
            return new Error(at, message, Json.NULL);
        }
    }

    record Annotation(Location at, Json value) implements Unit {}

    /// Writes this unit as one object of the `basic` output format.
    default void write(JsonWriter out) throws IOException {
        out.beginObject()
                .name("keywordLocation").value(at().keywordLocation())
                .name("absoluteKeywordLocation").value(at().absoluteKeywordLocation().toString())
                .name("instanceLocation").value(at().instanceLocation());
        switch (this) {
            case Error(var ignored, var message, var params) -> {
                out.name("error").value(message);
                if (!(params instanceof Json.Null)) out.name("params").value(params);
            }
            case Annotation(var ignored, var value) -> out.name("annotation").value(value);
        }
        out.endObject();
    }
}
