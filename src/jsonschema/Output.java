package jsonschema;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import json.JsonWriter;

/// What one schema said about one instance.
///
/// The specification names four output formats. This package produces the two
/// that carry information without a tree to walk: `flag` is the verdict alone,
/// and `basic` is the verdict plus a flat list of units.
///
/// Both write straight to a [Writer]. A document is never built in memory
/// first, because an instance with many failures produces many units.
///
/// A valid result has no errors, and an invalid result has no annotations that
/// anybody may use. The specification drops the annotations of a schema that
/// failed, and this record keeps both lists only so that a caller can look at
/// them. [#basic(Writer)] writes the list that matches the verdict.
public record Output(boolean valid, List<Unit> errors, List<Unit> annotations) {

    public Output {
        errors = List.copyOf(errors);
        annotations = List.copyOf(annotations);
    }

    /// A pass with nothing to report.
    public static Output ok() {
        return new Output(true, List.of(), List.of());
    }

    /// `{"valid":true}` or `{"valid":false}`, and nothing else.
    public void flag(Writer out) throws IOException {
        new JsonWriter(out).beginObject().name("valid").value(valid).endObject().flush();
    }

    /// The verdict and one flat list: the errors when it failed, and the
    /// annotations when it passed.
    public void basic(Writer out) throws IOException {
        var writer = new JsonWriter(out);
        writer.beginObject().name("valid").value(valid);
        writer.name(valid ? "annotations" : "errors").beginArray();
        for (var unit : valid ? annotations : errors) unit.write(writer);
        writer.endArray().endObject().flush();
    }
}
