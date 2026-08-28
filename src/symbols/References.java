package symbols;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/// Javadoc's reference links, resolved against the index.
///
/// A markdown doc comment writes a cross-reference as `[ActorSystem#effect(String,
/// Effect.Handler)]`. That is javadoc's syntax and CommonMark's punctuation: to a
/// markdown parser it is a shortcut reference to a link definition, the comment
/// never writes one, and so what a reader gets is the square brackets they were
/// meant to stop seeing — seven of them on `actors`' package page and not one
/// link.
///
/// **Why here.** Three things have to be true at once for that text to become a
/// link, and no one library knows all three. Markdown knows the label is a
/// reference nobody defined, and asks — that is [markdown.Links], and it is the
/// whole of what markdown may know, because a markdown library that could parse
/// a method signature would be carrying Java around. A caller knows the URL
/// space, and that is not this either: `symbols` answers questions about an
/// index and has no opinion about where a browser puts its pages. What is left
/// in the middle is knowing that `Foo#bar` is javadoc for a Java symbol and
/// whether the index has one — which is exactly what this is, and the only
/// place both halves of it are already known.
///
/// **What does not become a link.** A reference the index cannot find stays as
/// text. A dead link is worse than the brackets: the brackets say a name, and a
/// 404 says the page you are reading is wrong about its own project. So a name
/// that resolves to nothing is left alone, and so is a label that was never a
/// reference — `[the docs]` is prose, and is not looked up at all.
public final class References {

    private final Index index;
    private final String scope;
    private final BiFunction<String, String, String> url;

    /// What each label came to, including the ones that came to nothing.
    ///
    /// A page names the same type a dozen times — that is what a package
    /// comment is — and every one of them is otherwise a lookup. Remembering
    /// the misses matters as much as the hits: prose that is bracketed but is
    /// not a name is asked about once.
    private final Map<String, String> resolved = new HashMap<>();

    private References(Index index, String scope, BiFunction<String, String, String> url) {
        this.index = index;
        this.scope = scope;
        this.url = url;
    }

    /// A [markdown.Links] over `index`, reading references the way they would
    /// be read in the doc comment of `scope`, and pointing at wherever `url`
    /// says a symbol and one of its members live.
    ///
    /// `url` takes the resolved symbol and the member named after the `#`, or
    /// an empty member when the reference named a type. Whether a member gets
    /// an anchor of its own is a question about a page, and is answered by
    /// whoever has one.
    public static markdown.Links of(Index index, String scope, BiFunction<String, String, String> url) {
        var references = new References(index, scope, url);
        return references::destination;
    }

    private String destination(String label) {
        return resolved.computeIfAbsent(label, this::locate);
    }

    private String locate(String label) {
        var hash = label.indexOf('#');
        var type = (hash < 0 ? label : label.substring(0, hash)).strip();
        var member = hash < 0 ? "" : label.substring(hash + 1).strip();
        // A reference has to name something. Both halves empty is `[]`, and an
        // empty type otherwise means `[#advance]` — the enclosing type — which
        // is a reference to it and this is not.
        if (type.isEmpty() && member.isEmpty()) return null;
        if (!name(type) || !member(member)) return null;
        var found = symbol(type);
        if (found.isEmpty()) return null;
        var called = member.isEmpty() ? "" : member.substring(0, at(member));
        if (!called.isEmpty() && !declares(found.get(), called)) return null;
        return url.apply(found.get().name(), called);
    }

    /// The symbol a reference names: what it says, or what it says read from
    /// inside `scope`.
    ///
    /// The order is the one a reader has in mind. A fully qualified name means
    /// itself. A bare one written in a package comment means something in that
    /// package, and written in a type's comment means one of its nested types —
    /// both of which are `scope` with the name under it. Failing that it is a
    /// neighbour: `[ActorSystem]` in `actors.Definition` is `actors.ActorSystem`,
    /// which is the same rule javac uses and by far the commonest case.
    ///
    /// An empty type is `[#advance(Message)]`, a reference to a member of the
    /// type the comment is on, and that is `scope` itself.
    private Optional<TypeInfo> symbol(String type) {
        if (type.isEmpty()) return index.lookup(scope);
        var enclosing = scope.lastIndexOf('.');
        return index.lookup(type)
                .or(() -> scope.isEmpty() ? Optional.empty() : index.lookup(scope + "." + type))
                .or(() -> enclosing < 0 ? Optional.empty()
                        : index.lookup(scope.substring(0, enclosing) + "." + type));
    }

    /// Whether the type declares something by this name.
    ///
    /// The name only, never the parameter types, though the reference carries
    /// them. `[Fleet#ask(Address, Message)]` and `[Fleet#ask(Address)]` go to the
    /// same page whichever one exists, so telling the overloads apart would buy
    /// nothing and would have to agree with javadoc about how a parameter type
    /// is spelled — `Effect.Handler` or `actors.Effect$Handler` — which is a
    /// argument this does not need to have. What it is for is the difference
    /// between a member that exists and one that does not, and a name answers
    /// that.
    private static boolean declares(TypeInfo type, String called) {
        return type.methods().stream().anyMatch(method -> method.name().equals(called))
                || type.fields().stream().anyMatch(field -> field.name().equals(called));
    }

    private static int at(String member) {
        var parenthesis = member.indexOf('(');
        return parenthesis < 0 ? member.length() : parenthesis;
    }

    /// Whether a label could be a type name at all — dotted identifiers and
    /// nothing else, or empty for a reference to the enclosing type.
    ///
    /// This is what keeps prose out of the index. `[the docs]` and `[see below]`
    /// are labels somebody wrote meaning nothing of the kind, and every one of
    /// them would otherwise be a query.
    private static boolean name(String type) {
        if (type.isEmpty()) return true;
        var start = true;
        for (var at = 0; at < type.length(); at++) {
            var character = type.charAt(at);
            if (character == '.') {
                if (start) return false;
                start = true;
                continue;
            }
            if (!(start ? Character.isJavaIdentifierStart(character) : Character.isJavaIdentifierPart(character))) {
                return false;
            }
            start = false;
        }
        return !start;
    }

    /// Whether what follows the `#` could be a member: a name, and optionally a
    /// parameter list, which is read no further than its opening bracket.
    private static boolean member(String member) {
        if (member.isEmpty()) return true;
        var called = member.substring(0, at(member));
        return name(called) && (called.length() == member.length() || member.endsWith(")"));
    }
}
