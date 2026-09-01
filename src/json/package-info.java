/// Reads, writes, selects, and edits JSON with no dependencies.
///
/// [JsonReader] and [JsonWriter] process a document through a [java.io.Reader]
/// or [java.io.Writer]. [Json] is the immutable in-memory value for callers
/// that need a complete document.
///
/// [Pointer] implements [RFC 6901](https://datatracker.ietf.org/doc/html/rfc6901).
/// It accepts the JSON string form and the URI fragment form.
/// [RelativePointer] implements
/// [draft-bhutton-relative-json-pointer-00](https://datatracker.ietf.org/doc/html/draft-bhutton-relative-json-pointer-00).
/// The two parsers stay separate because neither specification makes the other
/// syntax acceptable.
/// [Selection] retains the starting location that relative evaluation needs.
///
/// Use the methods on [Json] for one operation. Compile a [Pointer] when one
/// path is used more than once.
///
/// ```
/// var source = Json.parse("{\"users\":[{\"name\":\"Ada\"}]}");
/// var name = source.at("/users/0/name").string("");
/// var changed = source
///         .set("/users/0/active", true)
///         .update("/users/0/name", value -> Json.of(value.string("").toUpperCase()));
/// ```
package json;
