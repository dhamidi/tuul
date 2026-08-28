package jsonschema;

import java.net.URI;
import java.util.List;

/// The seven vocabularies of draft 2020-12, plus the format-assertion one.
///
/// They are ordinary [Vocabulary] instances. The engine gives them no privilege
/// that a caller's own vocabulary lacks, and a caller who wants a different
/// dialect can build a store with [Store#empty()] and register whichever of
/// these they still want.
public final class Vocabularies {

    /// The core vocabulary is always in force. Every meta-schema must include
    /// it, so the store adds it whether the meta-schema said so or not.
    public static final URI CORE = Core.URI_;

    private Vocabularies() {}

    public static List<Vocabulary> all() {
        return List.of(
                Core.vocabulary(),
                Applicator.vocabulary(),
                Unevaluated.vocabulary(),
                Validation.vocabulary(),
                Annotations.metaData(),
                Annotations.content(),
                Formats.annotation(),
                Formats.assertion());
    }
}
