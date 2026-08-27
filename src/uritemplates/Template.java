package uritemplates;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// A URI template, per RFC 6570, read once and expanded as often as you like.
///
/// ```
/// var template = Template.of("/users/{id}/posts{?tag,page}");
/// template.expand(Map.of("id", "42", "tag", "java"));   // /users/42/posts?tag=java
/// ```
///
/// A template is immutable and holds its parsed form, so the parse happens when
/// it is read and never again — which is what makes it reasonable for a router
/// to keep one per route and expand it on every request.
///
/// Values are the ordinary Java shapes: a `String` (or anything with a sensible
/// `toString`) is a string, a `Collection` is a list, a `Map` is an associative
/// array, and `null` — or an absent key, or an empty collection — is undefined
/// and contributes nothing.
///
/// The RFC specifies expansion only. [#match] goes the other way for the
/// operators where that is unambiguous; see it for which, and why the rest are
/// not.
public final class Template {

    private final String text;
    private final List<Part> parts;
    private final Optional<Recognizer> recognizer;

    private Template(String text, List<Part> parts) {
        this.text = text;
        this.parts = List.copyOf(parts);
        this.recognizer = Recognizer.of(this.parts);
    }

    /// Reads a template. Throws [TemplateException] if it is not one.
    public static Template of(String text) {
        return new Template(text, Grammar.parse(text));
    }

    /// The template as it was written.
    public String text() {
        return text;
    }

    /// What the template is made of, for a caller that wants to look — a router
    /// deciding whether a route is one it can recognise, say.
    public List<Part> parts() {
        return parts;
    }

    /// Every variable the template mentions, in the order it mentions them,
    /// without repeats.
    public List<String> names() {
        var names = new ArrayList<String>();
        for (var part : parts) {
            if (part instanceof Part.Expression expression) {
                for (var variable : expression.variables()) {
                    if (!names.contains(variable.name())) names.add(variable.name());
                }
            }
        }
        return List.copyOf(names);
    }

    /// Expands into `out` as it goes, holding no more of the result than the
    /// value it is writing.
    public void expand(Map<String, ?> variables, Writer out) throws IOException {
        Expansion.write(parts, variables, out);
    }

    /// The same expansion, for the callers that want a String — which is most
    /// of them, since a URI is small.
    public String expand(Map<String, ?> variables) {
        var out = new StringWriter();
        try {
            expand(variables, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /// Whether this template can be read backwards. See [#match].
    public boolean matchable() {
        return recognizer.isPresent();
    }

    /// Recovers the variables from a URI this template could have produced, or
    /// nothing if it could not have produced it.
    ///
    /// This is an addition to the RFC, which specifies expansion only, and it
    /// is offered exactly where it is honest: the operators whose values are
    /// encoded so thoroughly that the separators cannot appear inside them —
    /// `{var}`, `{.var}` and `{/var}` — with no modifiers.
    ///
    /// `{+var}` and `{#var}` let reserved characters through, so a value may
    /// contain the very characters that would end it and there is no one right
    /// answer. `{?var}` and its kin describe a query, whose parameters may
    /// arrive in any order, so recognising one positionally would be wrong more
    /// often than right. A prefix modifier throws the rest of the value away,
    /// so nothing can bring it back. A template using any of those never
    /// matches, and [#matchable] says so in advance, so a router can refuse the
    /// route rather than the request.
    public Optional<Map<String, String>> match(String uri) {
        return recognizer.flatMap(pattern -> pattern.match(uri));
    }

    /// A template that has already been expanded is still a template: this is
    /// the identity a router keys on.
    @Override
    public String toString() {
        return text;
    }
}
