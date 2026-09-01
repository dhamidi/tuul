package symbols;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import json.Json;

/// Answers the questions `tuul docs` and `tuul browse` both need to answer.
///
/// A name a reader writes is a symbol such as `json.Json`, a member such as
/// `json.Json#parse`, a package document such as `web/tutorial`, or nothing
/// the catalog knows. The command line and the browser used to parse and
/// describe a name separately, and their two descriptions of the same name
/// could drift apart. This class reads a name, asks the [Catalog], and
/// builds the JSON that both of them print.
///
/// Every answer is a [Json.Object]. A message is JSON, and `--json` prints
/// this same object. The text output and the browser page both render it,
/// so neither can show a fact this object does not hold.
public final class Queries {

    /// The default limit on how many matches [#search] returns.
    public static final int MATCHES = 25;

    private Queries() {}

    /// What a caller wants to see with a symbol, beyond the symbol itself.
    ///
    /// `all` includes private members. `members` describes everything a
    /// package or a type holds. `documents` carries the full body of each
    /// package document instead of just its title.
    ///
    /// `recursive` also asks into subpackages. Setting `recursive` sets
    /// `members` too, since there is nothing to recurse into otherwise.
    public record Asking(boolean all, boolean members, boolean recursive, boolean documents) {

        /// Asks for the symbol alone, the way a page shows it.
        public static final Asking SYMBOL = new Asking(false, false, false, false);

        public Asking {
            if (recursive) members = true;
        }
    }

    /// Answers a name of any shape: a package document, a member, or a
    /// symbol.
    ///
    /// This method tries each shape in that order and returns the first
    /// match. It returns empty when the catalog has nothing by that name.
    public static Optional<Json.Object> answer(Catalog index, String name, Asking asking) {
        var document = Document.reference(name);
        if (document.isPresent()) return document(index, document.get());
        var hash = name.indexOf('#');
        if (hash > 0) return member(index, name.substring(0, hash), name.substring(hash + 1), asking.all());
        return symbol(index, name, asking);
    }

    /// Answers one symbol: a type, a package, or a module.
    ///
    /// The result includes what `asking` asked for beyond the symbol itself.
    public static Optional<Json.Object> symbol(Catalog index, String name, Asking asking) {
        return index.lookup(name).map(type -> describe(index, type, asking, new LinkedHashSet<>()));
    }

    /// Answers the members of one type that share a name: every overload of
    /// a method, or a field.
    ///
    /// The result is the type's description holding only those members, with
    /// `member` set to the name asked for. A renderer reads `member` to know
    /// what was asked. Returns empty when `member` is blank, when the type
    /// does not exist, or when the type has no member by that name.
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

    /// Answers one package document, or the introduction of a kind when the
    /// caller asked for a kind with no slug.
    ///
    /// A kind with no document of its own still answers, with a synthesized
    /// title and the list of documents of that kind under `documents`, so
    /// `web/reference` is never a dead end when reference documents exist.
    /// `links` holds every document of the package, which a page needs to
    /// resolve a relative link to another file in the package.
    ///
    /// Returns empty when the reference names a slug that does not exist, or
    /// when the kind has no documents at all.
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

    /// Describes a document as a link to it.
    ///
    /// The result holds the document's identity and file name, and drops
    /// the body.
    private static Json linked(Document document) {
        return document.describe().without("doc").without("source")
                .with("file", java.nio.file.Path.of(document.source()).getFileName().toString());
    }

    /// Answers what there is, before a caller has named anything: every root
    /// and what it holds.
    public static Json.Object roots(Catalog index) {
        return Docs.describe(index.roots());
    }

    /// Returns the source text that a description describes.
    ///
    /// A document description returns its own `doc` field, since a
    /// document's source is the Markdown already inside the description. A
    /// package or a module returns the whole text of its file. A top-level
    /// type with no member asked for also returns its whole file, because
    /// the imports are part of reading it.
    ///
    /// A nested type, or a member, returns only its own declaration and the
    /// comment above it. When a member has more than one overload, each
    /// overload's span joins the others with a newline between them.
    ///
    /// Returns empty when the catalog knows no source for the location,
    /// which is the case for a jar that shipped without one. Returns empty
    /// when a member was asked for and the catalog finds no declaration for
    /// it.
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

    /// Returns the type as it is written inside its file.
    ///
    /// `json.Json.Object` becomes `Json.Object` in `Json.java`. The filename
    /// says where the package ends, which the dotted name alone cannot say.
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

    /// Answers what matches some words, grouped by what each match belongs
    /// to.
    ///
    /// Every word must match by default. When nothing matches every word,
    /// this method answers with whatever matches any word instead, and sets
    /// `every` to false to say so. Each symbol appears once, even when it
    /// matches through more than one overload.
    ///
    /// Matches are grouped by the package they are filed under. Groups
    /// appear in the order their best match ranks. A group's name is the
    /// longest prefix its matches' pages share. Searching for `event
    /// stream` shows `eventstream` as a group name above the types and
    /// members that mention it, a name the reader can search next.
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

    /// Returns the package a match is filed under.
    ///
    /// A document's symbol already names its package. A type or a member
    /// has no separate package field, so this method takes the leading
    /// lowercase segments of its symbol, following the convention every
    /// Java package uses on disk.
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

    /// Returns the symbol that owns a page: the type of a member, the
    /// package of a document, or the symbol itself otherwise.
    private static String page(String symbol) {
        var slash = symbol.indexOf('/');
        if (slash >= 0) return symbol.substring(0, slash);
        var hash = symbol.indexOf('#');
        return hash < 0 ? symbol : symbol.substring(0, hash);
    }

    /// Returns the name a group is filed under.
    ///
    /// The name is the longest dotted prefix that the matches' pages share.
    /// When every match shares one page, the name is that page's package
    /// instead, since a package introduces a lone type better than the type
    /// introduces itself.
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

    /// Describes one symbol, with its members when `asking` asked for them.
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

    /// Describes every symbol a package or a type holds.
    ///
    /// Each member gets the same description a direct lookup would give it.
    /// A subpackage is skipped unless `recursive` is set. `web` holds eight
    /// subpackages, and a caller who asks about `web` does not also want
    /// all of them expanded by default. The `seen` set makes a name appear
    /// once, however many ways this method reaches it.
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
