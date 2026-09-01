package json;

import java.util.Objects;
import java.util.function.UnaryOperator;

/// One value selected inside a complete JSON document.
///
/// Call [Json#select(String)] with an absolute JSON Pointer. Then call
/// [#at(String)] with a Relative JSON Pointer. The selection retains the
/// absolute starting location that relative evaluation requires.
///
/// ```
/// var document = Json.parse("{\"foo\":[\"bar\",\"baz\"]}");
/// var baz = document.select("/foo/1");
/// var previous = baz.at("0-1");
/// var index = baz.at("0#");
/// ```
///
/// The document and its values are immutable. Edit methods return the complete
/// edited document. They do not move this selection or change its document.
public record Selection(Json document, Pointer pointer) {

    public Selection {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(pointer, "pointer").get(document);
    }

    /// Returns the complete immutable document.
    @Override
    public Json document() {
        return document;
    }

    /// Returns the absolute starting pointer.
    @Override
    public Pointer pointer() {
        return pointer;
    }

    /// Returns the value at the absolute starting pointer.
    public Json value() {
        return pointer.get(document);
    }

    /// Returns the value, member name, or array index selected relative to this value.
    public Json at(String relativePointer) {
        return RelativePointer.parse(relativePointer).get(document, pointer);
    }

    /// Returns the absolute pointer selected by a relative pointer.
    ///
    /// A relative pointer ending in `#` has no target pointer and fails. Call
    /// [#at(String)] to return its member name or array index.
    public Pointer resolve(String relativePointer) {
        return RelativePointer.parse(relativePointer).resolve(document, pointer);
    }

    /// Returns a selection at the relative location.
    ///
    /// A relative pointer ending in `#` returns a name or index, not a location,
    /// and fails here.
    public Selection move(String relativePointer) {
        return new Selection(document, resolve(relativePointer));
    }

    /// Returns the complete document with the relative location set.
    public Json set(String relativePointer, Json replacement) {
        return RelativePointer.parse(relativePointer).set(document, pointer, replacement);
    }

    /// Returns the complete document with a string at the relative location.
    public Json set(String relativePointer, String replacement) {
        return set(relativePointer, Json.of(replacement));
    }

    /// Returns the complete document with a boolean at the relative location.
    public Json set(String relativePointer, boolean replacement) {
        return set(relativePointer, Json.of(replacement));
    }

    /// Returns the complete document with a number at the relative location.
    public Json set(String relativePointer, double replacement) {
        return set(relativePointer, Json.of(replacement));
    }

    /// Applies `change` at the relative location and returns the complete document.
    public Json update(String relativePointer, UnaryOperator<Json> change) {
        return RelativePointer.parse(relativePointer).update(document, pointer, change);
    }

    /// Returns the complete document without the relative location.
    public Json remove(String relativePointer) {
        return RelativePointer.parse(relativePointer).remove(document, pointer);
    }
}
