package json;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/// An [RFC 6901](https://datatracker.ietf.org/doc/html/rfc6901) JSON Pointer.
///
/// Call [#parse(String)] for the JSON string form. Call [#fragment(String)]
/// for a URI fragment. Then call [#get(Json)] or [#find(Json)] to extract a
/// value. Call [#set(Json, Json)], [#update(Json, UnaryOperator)], or
/// [#remove(Json)] to return an edited document. No edit changes its input.
///
/// ```
/// var document = Json.parse("{\"people\":[{\"name\":\"Ada\"}]}");
/// var name = Pointer.parse("/people/0/name").get(document);
/// var changed = Pointer.parse("/people/0/name").set(document, Json.of("Grace"));
/// ```
///
/// A set replaces the root or an existing value. It can add the last member
/// of an object. It creates missing object parents. It does not infer a new
/// array from a numeric token. The `-` token appends to an existing array.
public final class Pointer {

    private static final String FRAGMENT_SAFE = "-._~!$&'()*+,;=:@/?";
    private static final Pointer ROOT = new Pointer(List.of());

    private final List<String> tokens;
    private final String text;

    private Pointer(List<String> tokens) {
        this.tokens = List.copyOf(tokens);
        this.text = render(tokens);
    }

    /// Returns the pointer to the complete document.
    public static Pointer root() {
        return ROOT;
    }

    /// Parses the JSON string representation.
    ///
    /// An empty string selects the complete document. Every non-empty pointer
    /// must start with `/`. The parser rejects a `~` that is not followed by
    /// `0` or `1`.
    public static Pointer parse(String pointer) {
        Objects.requireNonNull(pointer, "pointer");
        if (pointer.isEmpty()) return ROOT;
        if (pointer.charAt(0) != '/') throw malformed(pointer);
        var tokens = new ArrayList<String>();
        for (var part : pointer.substring(1).split("/", -1)) tokens.add(unescape(part));
        return new Pointer(tokens);
    }

    /// Creates a pointer from decoded reference tokens.
    ///
    /// This method escapes `/` and `~` when it renders the pointer.
    public static Pointer ofTokens(String... tokens) {
        return new Pointer(List.of(tokens));
    }

    /// Parses an RFC 6901 URI fragment representation.
    ///
    /// `#` selects the complete document. Percent escapes are decoded as
    /// UTF-8 before the pointer is parsed. The parser rejects malformed UTF-8.
    public static Pointer fragment(String fragment) {
        Objects.requireNonNull(fragment, "fragment");
        if (!fragment.startsWith("#")) throw new JsonException("a JSON Pointer fragment must start with #: " + fragment);
        try {
            return parse(decodeFragment(URI.create(fragment).getRawFragment(), fragment));
        } catch (IllegalArgumentException invalid) {
            throw new JsonException("invalid JSON Pointer fragment: " + fragment, invalid);
        }
    }

    /// Escapes one decoded reference token.
    public static String escape(String token) {
        Objects.requireNonNull(token, "token");
        return token.replace("~", "~0").replace("/", "~1");
    }

    /// Decodes one reference token.
    ///
    /// The decoder processes `~1` before `~0`. It rejects every other escape.
    public static String unescape(String token) {
        Objects.requireNonNull(token, "token");
        var decoded = new StringBuilder(token.length());
        for (var at = 0; at < token.length(); at++) {
            var character = token.charAt(at);
            if (character != '~') {
                decoded.append(character);
                continue;
            }
            if (++at == token.length()) throw new JsonException("invalid JSON Pointer token: " + token);
            decoded.append(switch (token.charAt(at)) {
                case '0' -> '~';
                case '1' -> '/';
                default -> throw new JsonException("invalid JSON Pointer token: " + token);
            });
        }
        return decoded.toString();
    }

    /// Returns the decoded reference tokens in document order.
    public List<String> tokens() {
        return tokens;
    }

    /// Returns true when this pointer selects the complete document.
    public boolean isRoot() {
        return tokens.isEmpty();
    }

    /// Returns the final decoded token. The root pointer has no final token.
    public Optional<String> last() {
        return tokens.isEmpty() ? Optional.empty() : Optional.of(tokens.getLast());
    }

    /// Returns a pointer with one decoded reference token appended.
    public Pointer append(String token) {
        Objects.requireNonNull(token, "token");
        var appended = new ArrayList<>(tokens);
        appended.add(token);
        return new Pointer(appended);
    }

    /// Returns this pointer without its final token.
    ///
    /// The root pointer has no parent.
    public Optional<Pointer> parent() {
        return tokens.isEmpty() ? Optional.empty() : Optional.of(new Pointer(tokens.subList(0, tokens.size() - 1)));
    }

    /// Returns true when this pointer starts with all tokens in `prefix`.
    public boolean startsWith(Pointer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return prefix.tokens.size() <= tokens.size()
                && tokens.subList(0, prefix.tokens.size()).equals(prefix.tokens);
    }

    /// Returns the selected value.
    ///
    /// This method throws [JsonException] when a token does not select a
    /// concrete value. The `-` token never selects a value.
    public Json get(Json document) {
        return lookup(document).orElseThrow(this::missing);
    }

    /// Returns the selected value, or an empty result when it does not exist.
    ///
    /// Invalid pointer syntax fails during [#parse(String)]. A valid pointer
    /// that meets a scalar, a missing member, or an invalid array index returns
    /// an empty result here.
    public Optional<Json> find(Json document) {
        Objects.requireNonNull(document, "document");
        return lookup(document);
    }

    /// Returns true when this pointer selects a concrete value.
    public boolean exists(Json document) {
        return find(document).isPresent();
    }

    /// Returns a selection that retains the document and this location.
    ///
    /// Use [Selection#at(String)] to evaluate a Relative JSON Pointer from the
    /// selected value. This pointer must select a concrete value.
    public Selection select(Json document) {
        return new Selection(document, this);
    }

    /// Returns a document with the selected location set to `replacement`.
    ///
    /// The root pointer replaces the document. A missing object member is
    /// added. Missing object parents are created. An array index must already
    /// exist. The `-` token appends to an existing array.
    public Json set(Json document, Json replacement) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(replacement, "replacement");
        return set(document, 0, replacement);
    }

    /// Returns a document with each pointer set in map iteration order.
    ///
    /// A later set sees the result of every earlier set. Use an ordered map
    /// when two pointers overlap and order changes the result.
    public static Json set(Json document, Map<Pointer, ? extends Json> replacements) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(replacements, "replacements");
        var changed = document;
        for (var replacement : replacements.entrySet()) {
            changed = replacement.getKey().set(changed, replacement.getValue());
        }
        return changed;
    }

    /// Applies `change` to the selected value and returns the edited document.
    ///
    /// The selected value must exist. The function must return a JSON value.
    public Json update(Json document, UnaryOperator<Json> change) {
        Objects.requireNonNull(change, "change");
        return set(document, Objects.requireNonNull(change.apply(get(document)), "updated value"));
    }

    /// Returns a document without the selected object member or array item.
    ///
    /// The selected value must exist. The root cannot be removed because an
    /// absent document is not a JSON value.
    public Json remove(Json document) {
        Objects.requireNonNull(document, "document");
        if (tokens.isEmpty()) throw new JsonException("the root JSON value cannot be removed");
        return remove(document, 0);
    }

    /// Returns the RFC 6901 URI fragment representation.
    public String toFragment() {
        return "#" + encodeFragment(text);
    }

    /// Returns the same URI without its fragment.
    public static URI document(URI uri) {
        Objects.requireNonNull(uri, "uri");
        var text = uri.toString();
        var hash = text.indexOf('#');
        return hash < 0 ? uri : URI.create(text.substring(0, hash));
    }

    /// Resolves a URI reference against `base`.
    ///
    /// A same-document fragment keeps an opaque base such as a `urn:`. The JDK
    /// resolver does not keep that base, so this method handles that case.
    public static URI resolve(URI base, String reference) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(reference, "reference");
        if (reference.isEmpty()) return document(base);
        if (reference.startsWith("#")) return URI.create(document(base) + reference);
        return base.resolve(reference);
    }

    /// Returns the absolute URI of a pointer inside `base`.
    public static URI absolute(URI base, String pointer) {
        return URI.create(document(base).toString() + parse(pointer).toFragment());
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Pointer pointer && tokens.equals(pointer.tokens);
    }

    @Override
    public int hashCode() {
        return tokens.hashCode();
    }

    private Optional<Json> lookup(Json document) {
        Json current = Objects.requireNonNull(document, "document");
        for (var token : tokens) {
            current = switch (current) {
                case Json.Object(var fields) -> fields.get(token);
                case Json.Array(var items) -> element(items, token);
                default -> null;
            };
            if (current == null) return Optional.empty();
        }
        return Optional.of(current);
    }

    private Json set(Json current, int depth, Json replacement) {
        if (depth == tokens.size()) return replacement;
        var token = tokens.get(depth);
        return switch (current) {
            case Json.Object object -> {
                var child = object.get(token);
                if (child == null) child = Json.Object.of();
                yield object.with(token, set(child, depth + 1, replacement));
            }
            case Json.Array(var items) -> {
                var changed = new ArrayList<>(items);
                if (token.equals("-") && depth == tokens.size() - 1) changed.add(replacement);
                else {
                    var index = requiredIndex(items, token);
                    changed.set(index, set(items.get(index), depth + 1, replacement));
                }
                yield new Json.Array(changed);
            }
            default -> throw cannotTraverse(depth, current);
        };
    }

    private Json remove(Json current, int depth) {
        var token = tokens.get(depth);
        return switch (current) {
            case Json.Object object -> {
                var child = object.get(token);
                if (child == null) throw missing();
                if (depth == tokens.size() - 1) yield object.without(token);
                yield object.with(token, remove(child, depth + 1));
            }
            case Json.Array(var items) -> {
                var index = requiredIndex(items, token);
                var changed = new ArrayList<>(items);
                if (depth == tokens.size() - 1) changed.remove(index);
                else changed.set(index, remove(items.get(index), depth + 1));
                yield new Json.Array(changed);
            }
            default -> throw cannotTraverse(depth, current);
        };
    }

    private static Json element(List<Json> items, String token) {
        var index = index(token);
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    private int requiredIndex(List<Json> items, String token) {
        var index = index(token);
        if (index < 0 || index >= items.size()) throw missing();
        return index;
    }

    private static int index(String token) {
        if (token.isEmpty() || token.equals("-") || token.length() > 1 && token.charAt(0) == '0') return -1;
        for (var character : token.toCharArray()) if (!Character.isDigit(character)) return -1;
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException tooLarge) {
            return -1;
        }
    }

    private JsonException cannotTraverse(int depth, Json current) {
        return new JsonException("cannot traverse " + current.getClass().getSimpleName() + " at "
                + new Pointer(tokens.subList(0, depth)) + " while evaluating " + text);
    }

    private JsonException missing() {
        return new JsonException("JSON Pointer does not select a value: " + text);
    }

    private static JsonException malformed(String pointer) {
        return new JsonException("invalid JSON Pointer: " + pointer);
    }

    private static String render(List<String> tokens) {
        var rendered = new StringBuilder();
        for (var token : tokens) rendered.append('/').append(escape(token));
        return rendered.toString();
    }

    private static String encodeFragment(String pointer) {
        var encoded = new StringBuilder(pointer.length());
        for (var at = 0; at < pointer.length();) {
            var codePoint = pointer.codePointAt(at);
            at += Character.charCount(codePoint);
            var plain = codePoint < 128
                    && (Character.isLetterOrDigit(codePoint) || FRAGMENT_SAFE.indexOf(codePoint) >= 0);
            if (plain) {
                encoded.appendCodePoint(codePoint);
                continue;
            }
            for (var octet : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                encoded.append('%').append("%02X".formatted(octet & 0xff));
            }
        }
        return encoded.toString();
    }

    private static String decodeFragment(String encoded, String original) {
        var decoded = new StringBuilder(encoded.length());
        for (var at = 0; at < encoded.length();) {
            if (encoded.charAt(at) != '%') {
                decoded.append(encoded.charAt(at++));
                continue;
            }
            var octets = new ByteArrayOutputStream();
            while (at < encoded.length() && encoded.charAt(at) == '%') {
                if (at + 2 >= encoded.length()) throw new JsonException("invalid percent escape in JSON Pointer: " + original);
                try {
                    octets.write(Integer.parseInt(encoded.substring(at + 1, at + 3), 16));
                } catch (NumberFormatException invalid) {
                    throw new JsonException("invalid percent escape in JSON Pointer: " + original);
                }
                at += 3;
            }
            try {
                decoded.append(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(octets.toByteArray())));
            } catch (CharacterCodingException invalid) {
                throw new JsonException("invalid UTF-8 in JSON Pointer: " + original, invalid);
            }
        }
        return decoded.toString();
    }
}
