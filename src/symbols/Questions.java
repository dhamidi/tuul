package symbols;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import json.Json;

/// The questions `tuul docs` and `tuul browse` both answer, answered once.
///
/// A name a reader writes is one of four things: a symbol such as
/// `json.Json`, a member of one such as `json.Json#parse`, a package document
/// such as `web/tutorial`, or nothing at all. The command line and the browser
/// used to tell them apart separately and describe them separately, and two
/// descriptions of one thing drift. This is the one place that reads a name,
/// asks the [Catalog], and builds the JSON both of them print.
///
/// Every answer is a [Json.Object] because a message is JSON, and because it
/// is the same object `--json` prints: the text and the page are renderings of
/// it and cannot add a fact it does not hold.
public final class Questions {

    /// The search results one page or one command answers with.
    public static final int MATCHES = 25;

    private Questions() {}

    /// What a caller wants to see with a symbol beyond the symbol itself.
    ///
    /// `all` includes private members. `members` describes everything a
    /// package or a type holds. `recursive` goes into subpackages as well.
    /// `documents` carries the body of every package document rather than its
    /// title, which is what a reader who wants to read the package asks for.
    public record Asking(boolean all, boolean members, boolean recursive, boolean documents) {

        /// The symbol and nothing more, which is what a page shows.
        public static final Asking SYMBOL = new Asking(false, false, false, false);

        public Asking {
            if (recursive) members = true;
        }
    }

    /// Answers a name of any shape, or empty when the catalog has nothing by
    /// that name.
    public static Optional<Json.Object> answer(Catalog index, String name, Asking asking) {
        var document = Document.reference(name);
        if (document.isPresent()) return document(index, document.get());
        var hash = name.indexOf('#');
        if (hash > 0) return member(index, name.substring(0, hash), name.substring(hash + 1), asking.all());
        return symbol(index, name, asking);
    }

    /// One symbol: a type, a package, or a module, with what was asked for.
    public static Optional<Json.Object> symbol(Catalog index, String name, Asking asking) {
        return index.lookup(name).map(type -> describe(index, type, asking, new LinkedHashSet<>()));
    }

    /// The members of one type that share a name: every overload of a method,
    /// or a field. The answer is the type's description holding only them and
    /// naming the member, so a renderer knows what was asked.
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

    /// One package document, or the introduction of a kind.
    ///
    /// A kind without an introduction answers with the list of the documents
    /// of that kind, so `web/reference` is never a dead end when there are
    /// reference documents. The answer names its siblings under `documents`
    /// and every document of the package under `links`, which is what a page
    /// needs to resolve a relative link to another file in the package.
    public static Optional<Json.Object> document(Catalog index, Document.Reference reference) {
        var page = index.documentPage(reference.packageName(), reference.kind(), reference.slug());
        var siblings = page.documents().stream().filter(document -> document.kind().equals(reference.kind())).toList();
        var selected = page.selected();
        if (selected.isEmpty() && (!reference.slug().isEmpty() || siblings.isEmpty())) return Optional.empty();
        var listed = Json.Array.of(siblings.stream().map(Questions::linked).toList());
        var links = Json.Array.of(page.documents().stream().map(Questions::linked).toList());
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

    /// A document as a link to it: its identity and its file name, without
    /// its body.
    private static Json linked(Document document) {
        return document.describe().without("doc").without("source")
                .with("file", java.nio.file.Path.of(document.source()).getFileName().toString());
    }

    /// What there is, before anything has been named.
    public static Json.Object roots(Catalog index) {
        return Docs.describe(index.roots());
    }

    /// The source text of what a description describes.
    ///
    /// A type is answered with its whole file, because the imports are part of
    /// reading it; a nested type or a member with its own declaration and the
    /// comment above it; a package with its `package-info.java`; a document
    /// with its Markdown. Empty when the catalog knows no source, which is the
    /// case for a jar that shipped without one.
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

    /// The type as it is written inside its file — `Json.Object` for
    /// `json.Json.Object` in `Json.java`. The file says where the package
    /// ends, which the dotted name alone cannot.
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

    /// What matches some words, grouped by what it belongs to.
    ///
    /// Every word must match. When nothing holds all of them, whatever holds
    /// any of them answers instead and `every` says so: a lead beats a dead
    /// end. Each symbol appears once, since overloads are one place.
    ///
    /// The matches are grouped by the package they are filed under, in the
    /// order the best match of each group ranks, and a group is named by the
    /// longest prefix its matches share. Searching for `event stream` then
    /// shows `eventstream` above the types and members that mention it — a
    /// name a reader can ask about next, which a flat list of members never
    /// offered.
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
                .with("matches", Json.Array.of(held.stream().map(Questions::describe).toList()))));
        return groups;
    }

    /// The package a match is filed under. A document names it; a symbol's
    /// leading lowercase segments are it, which is the convention every Java
    /// package on disk follows.
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

    /// The symbol that has a page: the type of a member, the package of a
    /// document, and the symbol itself otherwise.
    private static String page(String symbol) {
        var slash = symbol.indexOf('/');
        if (slash >= 0) return symbol.substring(0, slash);
        var hash = symbol.indexOf('#');
        return hash < 0 ? symbol : symbol.substring(0, hash);
    }

    /// The name a group is filed under: the longest dotted prefix its pages
    /// share, or the package when they are all one page — a lone type is
    /// better introduced by its package than by itself.
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

    /// One symbol, and what it holds when the caller asked for that.
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

    /// Every symbol a package or a type holds, described the same way it is.
    ///
    /// Reading a package took a question per type in it, which is the dominant
    /// cost of this tool for anybody getting oriented — a person or an agent.
    /// One question answers for all of them.
    ///
    /// A subpackage is skipped unless `recursive` is set, because `web` holds
    /// eight of them and a reader who asked about `web` asked about `web`. The
    /// `seen` set makes a name appear once however many ways it is reached.
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
