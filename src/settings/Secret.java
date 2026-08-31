package settings;

import json.Json;

/// Creates and recognizes durable descriptors for secret values.
///
/// A descriptor is JSON data that names the source of a secret. The descriptor
/// can enter settings state and inspection. Secret bytes do not enter either.
/// A consumer resolves the descriptor inside the effect that needs the bytes.
///
/// ```
/// var credentials = Contribution.named("credentials", schema).secret("/apiKey");
/// var settings = Settings.of(credentials);
/// var reference = Secret.reference("environment:PAYMENTS_API_KEY");
/// var message = settings.set("/credentials/apiKey", reference);
/// ```
public final class Secret {

    private Secret() {}

    /// Creates a JSON reference for one secret descriptor.
    ///
    /// The descriptor must contain a non-empty scheme, a colon, and a
    /// non-empty name. This method does not check whether the named source
    /// exists and never resolves it.
    public static Json reference(String descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("a secret descriptor cannot be null");
        var colon = descriptor.indexOf(':');
        if (colon <= 0 || colon == descriptor.length() - 1) {
            throw new IllegalArgumentException("a secret descriptor must have one non-empty scheme and name");
        }
        return Json.Object.of().with("$secret", descriptor);
    }

    /// Tests whether `value` has exactly one string field named `$secret`.
    ///
    /// The method does not resolve the descriptor or check that its source
    /// exists. It returns false for every other JSON shape.
    public static boolean isReference(Json value) {
        return value instanceof Json.Object object
                && object.fields().size() == 1
                && object.get("$secret") instanceof Json.Str;
    }

    /// Returns the JSON Schema for the exact secret-reference shape.
    ///
    /// The schema requires one `$secret` string property and rejects every
    /// additional property. It is a new immutable JSON value for each call.
    public static Json schema() {
        return Json.Object.of()
                .with("type", "object")
                .with("properties", Json.Object.of().with("$secret", Json.Object.of().with("type", "string")))
                .with("required", Json.Array.strings(java.util.List.of("$secret")))
                .with("additionalProperties", false);
    }
}
