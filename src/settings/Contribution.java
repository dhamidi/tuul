package settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import json.Json;

/// One component's immutable settings declaration.
///
/// A contribution owns one top-level property in the settings document. Its
/// schema validates the value under that property. Its initializer and secret
/// declarations add rules to the same property. [Configuration] and [Settings]
/// use the declaration to validate runtime and durable values.
///
/// Call [#named] to create a contribution. Call [#initially] or [#secret] to
/// return a new contribution with one more declaration. The original value
/// does not change.
///
/// ```
/// var images = Contribution.named("images", Json.parse("""
///         {"type":"object","properties":{"maximumBytes":{"type":"integer"}}}
///         """))
///         .initially("/maximumBytes",
///                 Initial.environment("IMAGES_MAX_BYTES", Initial.number()));
/// var settings = Settings.of(images);
/// ```
public final class Contribution {

    record Declaration(String relative, Initial initial) {}

    private final String namespace;
    private final Json schema;
    private final List<Declaration> initializers;
    private final List<String> secrets;

    private Contribution(String namespace, Json schema, List<Declaration> initializers, List<String> secrets) {
        this.namespace = namespace;
        this.schema = schema;
        this.initializers = List.copyOf(initializers);
        this.secrets = List.copyOf(secrets);
    }

    /// Creates a contribution for `namespace`.
    ///
    /// The namespace must be non-empty. The schema must describe an object and
    /// is compiled when the caller passes this contribution to
    /// [Configuration#of] or [Settings#of].
    /// The schema validates the namespace value without the top-level name.
    /// This method does not add an initializer or a secret path.
    public static Contribution named(String namespace, Json schema) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("a settings namespace cannot be empty");
        }
        return new Contribution(namespace, Objects.requireNonNull(schema, "schema"), List.of(), List.of());
    }

    /// Adds one initializer relative to this contribution's namespace.
    ///
    /// An empty pointer targets the complete namespace. A non-empty pointer
    /// must start with `/`. The method returns a new immutable contribution.
    /// [Settings#of] rejects duplicate full pointers and invalid secret
    /// initializers. This method does not read the source.
    public Contribution initially(String pointer, Initial initial) {
        Pointer.relative(pointer);
        Objects.requireNonNull(initial, "initial");
        var next = new ArrayList<>(initializers);
        next.add(new Declaration(pointer, initial));
        return new Contribution(namespace, schema, next, secrets);
    }

    /// Declares one path that may contain only a [Secret] reference.
    ///
    /// The pointer is relative to this contribution's namespace. An empty
    /// pointer declares the complete namespace. The method returns a new
    /// immutable contribution. [Settings#of] checks the declaration against
    /// initializer declarations. The declaration does not resolve a secret.
    public Contribution secret(String pointer) {
        Pointer.relative(pointer);
        if (secrets.contains(pointer)) throw new IllegalArgumentException("duplicate secret path: " + pointer);
        var next = new ArrayList<>(secrets);
        next.add(pointer);
        return new Contribution(namespace, schema, initializers, next);
    }

    String namespace() {
        return namespace;
    }

    Json schema() {
        return schema;
    }

    List<Declaration> initializers() {
        return initializers;
    }

    List<String> secrets() {
        return secrets;
    }
}
