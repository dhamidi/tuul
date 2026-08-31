package settings;

import java.nio.file.Path;
import java.util.Objects;
import json.Json;

/// One immutable source declaration for one setting path.
///
/// An external declaration stores a source scheme, a source name, and a
/// decoder. It does not read the source. A constant declaration stores its
/// JSON value and does not have a decoder.
///
/// Use [#environment], [#file], or [#value] to create a declaration. Use one
/// of [#string], [#number], [#booleanValue], or [#json] as the decoder for an
/// external declaration.
///
/// ```
/// var initial = Initial.environment("IMAGES_MAX_BYTES", Initial.number());
/// var images = Contribution.named("images", schema).initially("/maximumBytes", initial);
/// ```
public final class Initial {

    /// Decodes source text into one JSON value.
    ///
    /// The decoder name is stored in the `settings.resolve` effect. A handler
    /// uses that name to select the same decoder when it reads the source.
    public interface Decoder {
        /// The stable name of this decoder.
        String name();

        /// Decodes the complete source text.
        ///
        /// The standard decoders reject text that does not match their type.
        /// A decoder may throw when the text is invalid.
        Json decode(String text) throws Exception;
    }

    private final String scheme;
    private final String name;
    private final Decoder decoder;
    private final Json value;

    private Initial(String scheme, String name, Decoder decoder, Json value) {
        this.scheme = scheme;
        this.name = name;
        this.decoder = decoder;
        this.value = value;
    }

    /// Creates an initializer that reads one environment variable.
    ///
    /// The name must be non-empty. The handler receives the name and decodes
    /// the returned text. A missing variable produces no initializer result.
    /// This factory does not read the environment.
    public static Initial environment(String name, Decoder decoder) {
        return external("environment", name, decoder);
    }

    /// Creates an initializer that reads one file under a handler-selected root.
    ///
    /// The path must be non-empty. The file handler applies its configured
    /// lexical root and rejects a path that escapes it. This factory does not
    /// open the file.
    public static Initial file(Path path, Decoder decoder) {
        Objects.requireNonNull(path, "path");
        return external("file", path.toString(), decoder);
    }

    /// Creates an initializer that records `value` when its path is eligible.
    ///
    /// The value is emitted through the ordinary initializer-result message.
    /// The actor validates it before it commits it. The value must be non-null.
    public static Initial value(Json value) {
        return new Initial("value", "", null, Objects.requireNonNull(value, "value"));
    }

    /// Returns a decoder that preserves all source text as one JSON string.
    public static Decoder string() {
        return decoder("string", text -> Json.of(text));
    }

    /// Returns a decoder that accepts one finite JSON number after trimming.
    ///
    /// The decoder rejects other text and rejects values that do not fit in a
    /// finite Java `double`.
    public static Decoder number() {
        return decoder("number", text -> {
            var value = text.trim();
            if (!value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) {
                throw new IllegalArgumentException("not a JSON number");
            }
            var number = Double.parseDouble(value);
            if (!Double.isFinite(number)) throw new IllegalArgumentException("number is not finite");
            return Json.of(number);
        });
    }

    /// Returns a decoder that accepts only `true` or `false` after trimming.
    public static Decoder booleanValue() {
        return decoder("boolean", text -> switch (text.trim()) {
            case "true" -> Json.TRUE;
            case "false" -> Json.FALSE;
            default -> throw new IllegalArgumentException("not a JSON boolean");
        });
    }

    /// Returns a decoder that parses exactly one JSON value.
    ///
    /// Leading and trailing JSON whitespace is accepted. Additional values or
    /// malformed JSON cause the decoder to throw.
    public static Decoder json() {
        return decoder("json", text -> {
            var reader = new json.JsonReader(new java.io.StringReader(text));
            var value = reader.readValue();
            if (!(reader.next() instanceof json.JsonReader.Event.End)) {
                throw new IllegalArgumentException("more than one JSON value");
            }
            return value;
        });
    }

    String scheme() {
        return scheme;
    }

    String name() {
        return name;
    }

    Decoder decoder() {
        return decoder;
    }

    Json value() {
        return value;
    }

    boolean constant() {
        return value != null;
    }

    private static Initial external(String scheme, String name, Decoder decoder) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("an initializer name cannot be empty");
        return new Initial(scheme, name, Objects.requireNonNull(decoder, "decoder"), null);
    }

    private static Decoder decoder(String name, DecoderFunction function) {
        return new Decoder() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Json decode(String text) throws Exception {
                return function.decode(text);
            }
        };
    }

    @FunctionalInterface
    private interface DecoderFunction {
        Json decode(String text) throws Exception;
    }
}
