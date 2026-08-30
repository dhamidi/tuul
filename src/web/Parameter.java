package web;

import java.util.Optional;
import json.Json;

/// A named value that enters an application as text and leaves it as `T`.
///
/// A route uses [#parse(String)] when it recognises a path. A form uses the
/// same method when it captures a field. [#format] performs the other
/// direction when a [RouteRef] builds a path or a form shows an existing value.
///
/// Implement this interface to add an application type. The parser throws an
/// [IllegalArgumentException] when text is not a value of that type. The
/// message from [#invalid()] is what a form shows for that failure.
///
/// ```
/// record Slug(String value) {}
/// record SlugParameter(String name) implements Parameter<Slug> {
///     public Slug parse(String text) {
///         if (!text.matches("[a-z0-9-]+")) throw new IllegalArgumentException();
///         return new Slug(text);
///     }
///
///     public String format(Slug slug) {
///         return slug.value();
///     }
/// }
/// ```
public interface Parameter<T> {

    String name();

    /// Parses one value. Throws [IllegalArgumentException] when the value is
    /// not valid. A router treats that failure as a route that did not match.
    /// A form reports [#invalid()] beside the field.
    T parse(String text);

    /// Formats a value for a URI or form control. The default uses
    /// [Object#toString()]. Override it when that text does not round-trip
    /// through [#parse(String)].
    default String format(T value) {
        return value.toString();
    }

    /// Converts a parsed value to the JSON shape that a form submission keeps.
    /// The default keeps the formatted value as a string.
    default Json json(T value) {
        return Json.of(format(value));
    }

    /// The form problem for text that [#parse(String)] refuses.
    default String invalid() {
        return "is not valid";
    }

    /// Whether an optional blank value is a value. Text parameters keep a
    /// blank. Numeric and date parameters do not.
    default boolean keepsBlank() {
        return false;
    }

    /// The typed value that the matched route placed on this request.
    /// Returns empty when the request did not pass through that route.
    default Optional<T> find(Request request) {
        return Router.parameter(request, this);
    }

    /// The typed value that the matched route placed on this request.
    /// Throws when the request did not pass through a route with this
    /// parameter.
    default T get(Request request) {
        return find(request).orElseThrow(() -> new IllegalArgumentException(
                "request has no route parameter named " + name()));
    }
}
