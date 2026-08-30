package jsonschema;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import json.Json;

/// The draft 2020-12 meta-schemas, as files beside this class.
///
/// They travel as resources and not as string constants, because that is what
/// they are: eight short JSON documents that the specification publishes and
/// that never change. A resource is found the same way whether the classes sit
/// in a directory or in a jar, and the build copies every non-Java file under
/// `src/` into the class output for exactly this.
///
/// Nothing here reaches the network. The URIs in these files name them; they do
/// not fetch them.
final class Meta {

    private static final List<String> FILES = List.of(
            "meta/schema.json",
            "meta/core.json",
            "meta/applicator.json",
            "meta/unevaluated.json",
            "meta/validation.json",
            "meta/meta-data.json",
            "meta/format-annotation.json",
            "meta/content.json");
    private static final List<Json> SCHEMAS = FILES.stream().map(Meta::read).toList();

    private Meta() {}

    static void load(Store store) {
        SCHEMAS.forEach(store::add);
    }

    /// A missing resource is a [SchemaException] naming the file, because a null
    /// here shows up much later as a `$ref` that cannot resolve, and nobody can
    /// tell that apart from a schema fault.
    static Json read(String resource) {
        try (var found = Meta.class.getResourceAsStream(resource)) {
            if (found == null) throw SchemaException.missing(resource);
            return Json.parse(new InputStreamReader(found, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
