package settings;

import java.util.Objects;
import java.util.Optional;
import json.Json;

/// An immutable, validated runtime settings document.
///
/// A [Configuration] creates a snapshot with [Configuration#values]. The
/// snapshot contains the document the application supplied. It does not read
/// the environment, open files, send actor messages, or retain the source
/// configuration for later changes.
///
/// A missing pointer returns an empty [Optional]. Callers that need a required
/// value must check that result before constructing their typed options.
public final class Values {

    private final Configuration configuration;
    private final Json.Object document;

    Values(Configuration configuration, Json.Object document) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.document = Objects.requireNonNull(document, "document");
    }

    /// Returns the validated document. The returned JSON object is immutable.
    public Json.Object document() {
        return document;
    }

    /// Finds a value by absolute JSON Pointer.
    ///
    /// The pointer must name an installed namespace. An absent value returns an
    /// empty [Optional]. The method does not return a mutable copy.
    public Optional<Json> find(String pointer) {
        configuration.owned(pointer);
        return document.find(pointer);
    }
}
