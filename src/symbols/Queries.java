package symbols;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import json.Json;

/// Turns a name a reader typed into the JSON that describes it.
///
/// Call this from anything that answers a reader. `tuul docs` prints the
/// object and `tuul browse` renders it. Both get the same object for the
/// same name.
///
/// Pass any [Catalog]. The catalog decides where the symbols come from and
/// whether a call can compile. This class only reads.
///
/// Start with [#answer] when the name can be any shape. Call [#symbol],
/// [#member] or [#document] when the shape is already known. Call [#search]
/// for words instead of a name, [#roots] when there is no name yet, and
/// [#source] to turn an answer into the text it was read from.
public final class Queries {

    /// The number of matches one search answers with. Pass it as the limit
    /// to [#search] unless a caller has a reason for another number.
    public static final int MATCHES = 25;

    private Queries() {}

    /// What to include with a symbol. Pass [#SYMBOL] for the symbol alone.
    ///
    /// `all` includes members that are not public or protected. `members`
    /// adds a description of every type a package holds, or every nested
    /// type a type holds, under `members`. `recursive` adds subpackages to
    /// that, and implies `members`. `documents` adds the body of every
    /// package document under `documents` instead of only its title.
    public record Asking(boolean all, boolean members, boolean recursive, boolean documents) {

        /// The symbol alone: public members, document titles, no `members`.
        public static final Asking SYMBOL = new Asking(false, false, false, false);

        public Asking {
            if (recursive) members = true;
        }
    }

    /// Answers a name of any shape.
    ///
    /// The shape decides the answer. `web/tutorial` or `web/tutorial/first`
    /// is a document and goes to [#document]. `json.Json#parse` is a member
    /// and goes to [#member]. Anything else is a symbol and goes to
    /// [#symbol]. A name is read as one shape only. Returns empty when the
    /// catalog has nothing by that name in that shape.
    public static Optional<Json.Object> answer(Catalog index, String name, Asking asking) {
        var document = Document.reference(name);
        if (document.isPresent()) return document(index, document.get());
        var hash = name.indexOf('#');
        if (hash > 0) return member(index, name.substring(0, hash), name.substring(hash + 1), asking.all());
        return symbol(index, name, asking);
    }

    /// Answers a type, a package, or a module by its qualified name.
    ///
    /// The object is the one [Docs#describe(TypeInfo, boolean, List)]
    /// builds. A package also lists its documents under `documents`. With
    /// `asking.members()` the object holds `members`, one such object per
    /// type the symbol holds. Returns empty when the catalog has no symbol
    /// by that name.
    public static Optional<Json.Object> symbol(Catalog index, String name, Asking asking) {
        return index.lookup(name).map(type -> describe(index, type, asking, new LinkedHashSet<>()));
    }

    /// Answers one member of a type by name.
    ///
    /// The object is the type's description with `member` set to the name,
    /// `methods` holding every overload of that name, and `fields` holding a
    /// field of that name. With `all`, non-public members count too. Returns
    /// empty when `member` is empty, when the type does not exist, or when
    /// the type has no member of that name.
    public static Optional<Json.Object> member(Catalog index, String typeName, String member, boolean all) {
        if (member.isEmpty()) return Optional.empty();
        var found = index.lookup(typeName);
        if (found.isEmpty()) return Optional.empty();
        var description = Docs.describe(found.get(), all);
        var methods = named(description.list("methods"), member);
        var fields = named(description.list("fields"), member);
        if (methods.isEmpty() && fields.isEmpty()) return Optional.empty();
        return Optional.of(description
                .with("member", member)
                .with("methods", Json.Array.of(methods))
                .with("fields", Json.Array.of(fields)));
    }

    private static List<Json> named(List<Json> members, String name) {
        return members.stream()
                .filter(member -> member instanceof Json.Object held && held.string("name", "").equals(name))
                .toList();
    }

    /// Answers one package document, or a kind of document.
    ///
    /// When the reference names a document that exists, the object is
    /// [Document#describe()] with two lists added. `documents` holds the
    /// other documents of the same kind. `links` holds every document of the
    /// package with its file name, which a renderer uses to turn a relative
    /// link into a route.
    ///
    /// When the reference has no slug and the kind has no introduction, the
    /// object has no `doc`. It carries `symbol`, `package`, `kind`, `title`,
    /// and the same two lists, so a caller can offer the documents of that
    /// kind. Returns empty when a slug was given and no such document
    /// exists, or when the kind has no documents at all.
    public static Optional<Json.Object> document(Catalog index, Document.Reference reference) {
        var page = index.documentPage(reference.packageName(), reference.kind(), reference.slug());
        var siblings = page.documents().stream().filter(document -> document.kind().equals(reference.kind())).toList();
        var selected = page.selected();
        if (selected.isEmpty() && (!reference.slug().isEmpty() || siblings.isEmpty())) return Optional.empty();
        var listed = Json.Array.of(siblings.stream().map(Queries::linked).toList());
        var links = Json.Array.of(page.documents().stream().map(Queries::linked).toList());
        return Optional.of(selected
                .map(document -> document.describe().with("documents", listed).with("links", links))
                .orElseGet(() -> Json.Object.of()
                        .with("symbol", reference.packageName() + "/" + reference.kind())
                        .with("package", reference.packageName())
                        .with("kind", reference.kind())
                        .with("slug", "")
                        .with("title", Document.title("", reference.kind(), ""))
                        .with("documents", listed)
                        .with("links", links)));
    }

    /// A document without its body: identity, title, and `file`, the name of
    /// the Markdown file it was read from.
    private static Json linked(Document document) {
        return document.describe().without("doc").without("source")
                .with("file", java.nio.file.Path.of(document.source()).getFileName().toString());
    }

    /// Answers the question with no name in it: which roots there are and
    /// which names each holds. See [Docs#describe(List)] for the object.
    public static Json.Object roots(Catalog index) {
        return Docs.describe(index.roots());
    }

    /// Reads the source text behind an answer from [#answer].
    ///
    /// Pass the object that method returned. For a document the text is its
    /// Markdown body. For a package or a module it is the whole
    /// `package-info.java` or `module-info.java`. For a top-level type it is
    /// the whole file, imports included. For a nested type it is that type's
    /// declaration with the comment above it. For a member it is every
    /// declaration of that name with its comment, one after another with a
    /// blank line between them.
    ///
    /// Returns empty when the object names no source, when the location
    /// cannot be read, or when a member was asked for and its declaration
    /// is not in the file. A type from a jar without a sources jar has no
    /// source.
    public static Optional<String> source(Catalog index, Json.Object description) {
        if (description.get("doc") instanceof Json.Str(var body) && description.get("package") instanceof Json.Str
                && description.get("title") instanceof Json.Str) {
            return Optional.of(body);
        }
        var location = description.string("source", "");
        var text = index.source(location);
        if (text.isEmpty()) return Optional.empty();
        var name = description.string("class", "");
        var kind = description.string("kind", "");
        if (kind.equals("package") || kind.equals("module")) return text;
        var path = written(name, location);
        var member = description.string("member", "");
        if (member.isEmpty() && !path.contains(".")) return text;
        var spans = Javadoc.spans(text.get(), file(location), path, member);
        if (spans.isEmpty()) return member.isEmpty() ? text : Optional.empty();
        var out = new StringBuilder();
        for (var span : spans) {
            if (!out.isEmpty()) out.append('\n');
            out.append(span.of(text.get()));
        }
        return Optional.of(out.toString());
    }

    /// The type as it is written inside its file: `Json.Object` for
    /// `json.Json.Object` in `Json.java`. The file name marks where the
    /// package ends, which the dotted name alone does not.
    private static String written(String name, String location) {
        var file = file(location);
        var outer = file.endsWith(".java") ? file.substring(0, file.length() - ".java".length()) : file;
        var segments = List.of(name.split("\\."));
        var at = segments.indexOf(outer);
        if (at < 0) return name.substring(name.lastIndexOf('.') + 1);
        return String.join(".", segments.subList(at, segments.size()));
    }

    private static String file(String location) {
        var slash = Math.max(location.lastIndexOf('/'), location.lastIndexOf(java.io.File.separatorChar));
        return slash < 0 ? location : location.substring(slash + 1);
    }

    /// Searches names and comments for words.
    ///
    /// The object holds `query`, `every`, and `groups`. Each group holds a
    /// `prefix` and its `matches`. A match holds `symbol`, `kind`,
    /// `modifiers`, `doc`, `origin`, and `source`. Blank text answers with
    /// no groups.
    ///
    /// The catalog first looks for what holds every word, at most `limit`
    /// matches. When that finds nothing, it looks for what holds any word and
    /// sets `every` to false. A symbol appears once, so three overloads of
    /// one method are one match.
    ///
    /// Matches are grouped by the package they belong to, in the order of
    /// each group's best match. The prefix is the longest dotted name the
    /// group's matches share, and is itself a package or a type that
    /// [#symbol] answers. A search for `event stream` groups the types and
    /// members that mention it under the prefix `eventstream`.
    public static Json.Object search(Catalog index, String text, int limit) {
        var every = true;
        var matches = distinct(index.search(text, limit));
        if (matches.isEmpty() && !text.isBlank()) {
            matches = distinct(index.searchAny(text, limit));
            every = false;
        }
        return Json.Object.of()
                .with("query", text)
                .with("every", every)
                .with("groups", Json.Array.of(groups(matches)));
    }

    private static List<Catalog.Match> distinct(List<Catalog.Match> matches) {
        var seen = new LinkedHashSet<String>();
        return matches.stream().filter(match -> seen.add(match.symbol())).toList();
    }

    private static List<Json> groups(List<Catalog.Match> matches) {
        var grouped = new LinkedHashMap<String, List<Catalog.Match>>();
        for (var match : matches) grouped.computeIfAbsent(packageOf(match), key -> new ArrayList<>()).add(match);
        var groups = new ArrayList<Json>();
        grouped.forEach((packageName, held) -> groups.add(Json.Object.of()
                .with("prefix", prefix(held))
                .with("matches", Json.Array.of(held.stream().map(Queries::describe).toList()))));
        return groups;
    }

    /// The package a match belongs to.
    ///
    /// A document names its package before the slash. For a type or a
    /// member the package is the leading segments that start with a
    /// lowercase letter. That is the convention for Java packages, not a
    /// rule, so a type with a lowercase name is grouped as if it were a
    /// package.
    static String packageOf(Catalog.Match match) {
        var page = page(match.symbol());
        if (match.symbol().contains("/")) return page;
        var kept = new ArrayList<String>();
        for (var segment : page.split("\\.")) {
            if (segment.isEmpty() || Character.isUpperCase(segment.charAt(0))) break;
            kept.add(segment);
        }
        return String.join(".", kept);
    }

    /// The symbol a match is shown on: the type for a member, the package
    /// for a document, and the symbol itself otherwise.
    private static String page(String symbol) {
        var slash = symbol.indexOf('/');
        if (slash >= 0) return symbol.substring(0, slash);
        var hash = symbol.indexOf('#');
        return hash < 0 ? symbol : symbol.substring(0, hash);
    }

    /// The prefix a group is shown under.
    ///
    /// When the matches are on two or more pages, it is the longest dotted
    /// name those pages share. When they are all on one page, it is the
    /// package of that page.
    static String prefix(List<Catalog.Match> matches) {
        var pages = new LinkedHashSet<String>();
        for (var match : matches) pages.add(page(match.symbol()));
        if (pages.size() <= 1) return matches.isEmpty() ? "" : packageOf(matches.getFirst());
        List<String> common = null;
        for (var page : pages) {
            var segments = List.of(page.split("\\."));
            if (common == null) {
                common = new ArrayList<>(segments);
                continue;
            }
            var shared = 0;
            while (shared < common.size() && shared < segments.size() && common.get(shared).equals(segments.get(shared))) {
                shared++;
            }
            common = new ArrayList<>(common.subList(0, shared));
        }
        return String.join(".", common);
    }

    private static Json describe(Catalog.Match match) {
        return Json.Object.of()
                .with("symbol", match.symbol())
                .with("kind", match.kind().toLowerCase(Locale.ROOT))
                .with("modifiers", match.modifiers())
                .with("doc", match.doc())
                .with("origin", match.origin())
                .with("source", match.source());
    }

    /// One symbol as [#symbol] answers it, with `members` when asked for.
    private static Json.Object describe(Catalog index, TypeInfo type, Asking asking, Set<String> seen) {
        var documents = type.kind() == TypeInfo.Kind.PACKAGE ? index.documents(type.name()) : List.<Document>of();
        var description = Docs.describe(type, asking.all(), documents);
        if (asking.documents() && !documents.isEmpty()) {
            description = description.with("documents", Json.Array.of(documents.stream()
                    .map(document -> (Json) document.describe()).toList()));
        }
        if (!asking.members()) return description;
        seen.add(type.name());
        return description.with("members", Json.Array.of(members(index, description, asking, seen)));
    }

    /// The types a package or a type holds, each described as [#symbol]
    /// describes it.
    ///
    /// A subpackage is included only with `recursive`, and then its own
    /// contents follow it. A name that is reached twice is described once.
    private static List<Json> members(Catalog index, Json.Object description, Asking asking, Set<String> seen) {
        var described = new ArrayList<Json>();
        for (var value : description.list("nested")) {
            if (!(value instanceof Json.Str(var name)) || !seen.add(name)) continue;
            var found = index.lookup(name);
            if (found.isEmpty()) continue;
            var group = found.get().kind().grouping();
            if (group && !asking.recursive()) continue;
            var member = describe(index, found.get(), new Asking(asking.all(), false, false, asking.documents()), seen);
            described.add(member);
            if (group) described.addAll(members(index, member, asking, seen));
        }
        return List.copyOf(described);
    }
}
