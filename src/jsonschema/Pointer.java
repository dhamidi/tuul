package jsonschema;

import java.net.URI;

/// JSON pointers, as RFC 6901 writes them, and the URIs that carry one.
///
/// A pointer is a string of steps. Each step starts with `/`. A `~` inside a
/// step becomes `~0` and a `/` becomes `~1`, because those two characters
/// already mean something else in a pointer.
public final class Pointer {

    private static final String SAFE = "-._~!$&'()*+,;=:@/?";

    private Pointer() {}

    /// One property name or one keyword, ready to put after a `/`.
    public static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    /// The reverse of [#escape(String)]. The order matters: `~01` must come
    /// back as `~1` and not as `/`.
    public static String unescape(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    /// The same URI without its fragment.
    ///
    /// A schema resource is named by the part in front of the `#`. Two
    /// references that differ only after the `#` point into one document, so
    /// the store keys documents by this.
    public static URI document(URI uri) {
        var text = uri.toString();
        var hash = text.indexOf('#');
        return hash < 0 ? uri : URI.create(text.substring(0, hash));
    }

    /// A URI reference resolved against a base URI, as RFC 3986 says.
    ///
    /// [URI#resolve(String)] is used for everything except a reference that is
    /// only a fragment. RFC 3986 calls that a same-document reference and keeps
    /// the base, and [URI#resolve(String)] does that only for a hierarchical
    /// base. It hands an opaque base such as a `urn:` straight back, which
    /// would turn `#/$defs/name` into a URI of its own.
    public static URI resolve(URI base, String reference) {
        if (reference.isEmpty()) return document(base);
        if (reference.startsWith("#")) return URI.create(document(base) + reference);
        return base.resolve(reference);
    }

    /// The absolute location of a node: the base URI, a `#`, and the pointer.
    ///
    /// Characters that a URI fragment does not allow are percent-encoded here.
    /// A property name can hold a space or a quote, and [URI#create] refuses
    /// those, so the encoding is not optional.
    public static URI absolute(URI base, String pointer) {
        return URI.create(base.toString() + "#" + encode(pointer));
    }

    private static String encode(String pointer) {
        var out = new StringBuilder(pointer.length());
        for (var i = 0; i < pointer.length(); i++) {
            var c = pointer.charAt(i);
            var plain = Character.isLetterOrDigit(c) && c < 128 || SAFE.indexOf(c) >= 0;
            if (plain) out.append(c);
            else for (var b : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                out.append('%').append("%02X".formatted(b & 0xff));
            }
        }
        return out.toString();
    }
}
