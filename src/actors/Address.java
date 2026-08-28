package actors;

import java.nio.file.Path;
import java.util.Objects;
import json.Json;

/// An address names one actor: the system it belongs to, its type, and its
/// instance id.
///
/// ## The written form
///
/// An address is written `[system:]type/id`. The system may be left off, and
/// then the address means the local system. The type never contains a slash.
/// The id is everything after the first slash, so an id may contain slashes of
/// its own and addresses form a hierarchy:
///
/// ```
/// counter/42                a local actor
/// orders:counter/42         the same actor, named from another system
/// basket/42/7               basket 7 of customer 42
/// ```
///
/// ## Why this is a type and not a string
///
/// Every operation a caller needs is here, so no call site has to build an
/// address by joining strings. [Definition#at(String)] makes an address of the
/// right type without naming the type, [#child(String, String)] derives a
/// child address from a parent, and [#sibling(String)] moves to another
/// instance of the same type. A misspelled type name in a string literal
/// produces an actor that exists, is empty, and receives messages nobody reads,
/// which is a bug with no error message. Removing the string removes the bug.
///
/// ## The trade-off
///
/// Splitting on the first colon means a system name cannot contain a colon and
/// a type cannot contain a slash. Ids may contain both. That asymmetry buys a
/// grammar with no escaping in it, which is worth more than the freedom to name
/// a type `a/b`.
public record Address(String system, String type, String id) implements Comparable<Address> {

    /// The system name an address carries when it means the local system.
    public static final String LOCAL = "";

    public Address {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (type.indexOf('/') >= 0) throw new IllegalArgumentException("a type cannot contain a slash: " + type);
        if (type.indexOf(':') >= 0) throw new IllegalArgumentException("a type cannot contain a colon: " + type);
        if (system.indexOf(':') >= 0) throw new IllegalArgumentException("a system cannot contain a colon: " + system);
    }

    /// An address in the local system.
    public static Address of(String type, String id) {
        return new Address(LOCAL, type, id);
    }

    /// An address in a named system.
    public static Address at(String system, String type, String id) {
        return new Address(system, type, id);
    }

    /// Reads the written form. A text with no slash names a type with an empty
    /// id, which is how a singleton actor is addressed.
    public static Address parse(String text) {
        var slash = text.indexOf('/');
        var colon = text.indexOf(':');
        var system = LOCAL;
        var rest = text;
        if (colon >= 0 && (slash < 0 || colon < slash)) {
            system = text.substring(0, colon);
            rest = text.substring(colon + 1);
        }
        var divide = rest.indexOf('/');
        if (divide < 0) return new Address(system, rest, "");
        return new Address(system, rest.substring(0, divide), rest.substring(divide + 1));
    }

    /// Reads an address out of a message or an effect. A string is parsed. An
    /// object with `type` and `id` fields is read field by field, which is what
    /// [#json()] writes.
    public static Address from(Json value) {
        return switch (value) {
            case Json.Str(var text) -> parse(text);
            case Json.Object object -> new Address(
                    object.string("system", LOCAL), object.string("type", ""), object.string("id", ""));
            case null, default -> throw new IllegalArgumentException("not an address: " + value);
        };
    }

    /// Whether this address leaves the system name off, which means the system
    /// that reads it.
    public boolean local() {
        return system.equals(LOCAL);
    }

    /// Whether this address belongs to a system other than the one named.
    public boolean foreign(String here) {
        return !local() && !system.equals(here);
    }

    /// The same actor, named from another system.
    public Address in(String system) {
        return new Address(system, type, id);
    }

    /// The same actor with the system name removed, which is how a system
    /// stores an address it has decided is its own.
    public Address here() {
        return local() ? this : new Address(LOCAL, type, id);
    }

    /// Another instance of the same type, in the same system.
    public Address sibling(String id) {
        return new Address(system, type, id);
    }

    /// An address derived from this one: the given type, and an id that carries
    /// this actor's id as its prefix.
    ///
    /// A customer at `customer/42` derives its baskets as `basket/42/1`,
    /// `basket/42/2`, and so on. Every basket of that customer then shares the
    /// id prefix `42/`, so [Logs#catalogue(String, String)] can list them
    /// without a separate index.
    public Address child(String type, String id) {
        return new Address(system, type, this.id.isEmpty() ? id : this.id + "/" + id);
    }

    /// This address as JSON, which is how it travels inside a message or an
    /// effect.
    public Json.Object json() {
        var object = Json.Object.of().with("type", type).with("id", id);
        return local() ? object : object.with("system", system);
    }

    /// Where the log of this actor lives under `root`.
    ///
    /// Every character that is not a letter, a digit, a dot, a dash or an
    /// underscore is percent-encoded, slashes included, so an id never turns
    /// into a directory. Encoding a slash as `%2F` keeps prefix order, so the
    /// baskets of customer 42 still sort together.
    public Path path(Path root) {
        return root.resolve(encode(type)).resolve(encode(id) + ".sqlite");
    }

    @Override
    public int compareTo(Address other) {
        var bySystem = system.compareTo(other.system);
        if (bySystem != 0) return bySystem;
        var byType = type.compareTo(other.type);
        return byType != 0 ? byType : id.compareTo(other.id);
    }

    @Override
    public String toString() {
        var name = type + (id.isEmpty() ? "" : "/" + id);
        return local() ? name : system + ":" + name;
    }

    /// Percent-encoding, with a hyphen and a dot left alone so that ordinary
    /// ids stay readable in a directory listing.
    static String encode(String text) {
        var out = new StringBuilder(text.length());
        for (var character : text.toCharArray()) {
            if (Character.isLetterOrDigit(character) || character == '.' || character == '-' || character == '_') {
                out.append(character);
                continue;
            }
            for (var byteValue : String.valueOf(character).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                out.append('%').append("%02X".formatted(byteValue & 0xFF));
            }
        }
        return out.toString();
    }

    static String decode(String text) {
        var out = new java.io.ByteArrayOutputStream(text.length());
        for (var index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '%') {
                out.writeBytes(String.valueOf(text.charAt(index)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                continue;
            }
            out.write(Integer.parseInt(text.substring(index + 1, index + 3), 16));
            index += 2;
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
