package jsonschema;

import java.net.URI;
import java.util.List;

/// A named set of keywords, and the only way to extend this package.
///
/// The specification has one extension mechanism and this is it. A meta-schema
/// lists the vocabularies it uses in `$vocabulary`, as a map of URI to boolean.
/// A store knows a set of vocabularies by URI. Compiling a schema takes the
/// meta-schema the schema declares, reads that map, and builds the keyword set
/// from it. A vocabulary the store does not know is skipped when the map says
/// false and refused when the map says true.
///
/// The seven vocabularies of draft 2020-12 are ordinary instances of this
/// interface, built by [Vocabularies]. They hold no privilege that a caller's
/// own vocabulary lacks. To add a keyword: write a [Keyword], put it in a
/// vocabulary with a URI of your own, register the vocabulary with the store,
/// register a meta-schema that names that URI in `$vocabulary`, and point
/// `$schema` at that meta-schema.
public interface Vocabulary {

    /// The URI a meta-schema names this vocabulary by.
    URI uri();

    List<Keyword> keywords();

    static Vocabulary of(URI uri, Keyword... keywords) {
        return new Named(uri, List.of(keywords));
    }

    static Vocabulary of(URI uri, List<Keyword> keywords) {
        return new Named(uri, keywords);
    }

    record Named(URI uri, List<Keyword> keywords) implements Vocabulary {

        public Named {
            keywords = List.copyOf(keywords);
        }
    }
}
