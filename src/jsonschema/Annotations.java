package jsonschema;

import static jsonschema.Keyword.Shape.SCHEMA;

import java.net.URI;
import java.util.List;
import json.Json;

/// The two vocabularies that only describe: meta-data and content.
///
/// Not one keyword here can fail an instance. They copy their own value into an
/// annotation, and a caller who reads the `basic` output gets them back with the
/// instance location they belong to. That is the whole point of them: a form
/// generator wants the `title` of the property it is drawing, and a validator
/// has no opinion about it.
///
/// `contentSchema` is the surprising one. It holds a real schema and it is
/// still never applied. The specification says so: the string would have to be
/// decoded first, and decoding is the caller's business, not the validator's.
/// The schema travels out as an annotation so that a caller who does decode the
/// string can then use it.
public final class Annotations {

    static final URI META_DATA = URI.create("https://json-schema.org/draft/2020-12/vocab/meta-data");
    static final URI CONTENT = URI.create("https://json-schema.org/draft/2020-12/vocab/content");

    private Annotations() {}

    public static Vocabulary metaData() {
        return Vocabulary.of(META_DATA, List.of(
                describing("title"),
                describing("description"),
                describing("default"),
                describing("deprecated"),
                describing("readOnly"),
                describing("writeOnly"),
                describing("examples")));
    }

    public static Vocabulary content() {
        return Vocabulary.of(CONTENT, List.of(
                describing("contentEncoding"),
                describing("contentMediaType"),
                Keyword.of("contentSchema", SCHEMA, Annotations::describe)));
    }

    private static Keyword describing(String name) {
        return Keyword.of(name, Annotations::describe);
    }

    private static void describe(Json value, Json instance, Context context) {
        context.annotate(value);
    }
}
