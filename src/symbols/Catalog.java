package symbols;

import java.util.List;
import java.util.Optional;

/// Reads symbols and documents from one committed catalog generation.
///
/// A catalog does not compile sources or publish index rows. Call
/// [Index#catalog(Path)] to read `index.db`. A caller can provide an in-memory
/// catalog for tests. The caller closes the catalog after the last lookup.
public interface Catalog extends AutoCloseable {

    /// One source of symbols and the names at its top level.
    record Root(String name, String label, List<String> contents) {}

    /// One search result. `origin` names the project, dependency coordinate, or
    /// platform. `source` locates the declaration when source is available.
    record Match(String symbol, String kind, String modifiers, String doc, String origin, String source) {
        public Match withOrigin(String value) {
            return new Match(symbol, kind, modifiers, doc, value, source);
        }
    }

    /// One package-document page: the selected document, if it has one, and
    /// every sibling needed to render its navigation.
    record DocumentPage(Optional<Document> selected, List<Document> documents) {}

    /// Whether this catalog has a complete committed generation to read.
    default boolean ready() {
        return true;
    }

    /// Looks up one type, package, or module. A nested type can use source form
    /// with dots instead of its binary name with `$`.
    Optional<TypeInfo> lookup(String name);

    /// Returns one project package document. An unknown package, kind, or slug
    /// returns empty.
    default Optional<Document> document(String packageName, String kind, String slug) {
        return Optional.empty();
    }

    /// Returns all documents for one project package in filename order.
    default List<Document> documents(String packageName) {
        return List.of();
    }

    /// Returns the documents of one kind in filename order.
    default List<Document> documents(String packageName, String kind) {
        return documents(packageName).stream().filter(document -> document.kind().equals(kind)).toList();
    }

    /// Reads a package document and its siblings as one catalog operation.
    default DocumentPage documentPage(String packageName, String kind, String slug) {
        var documents = documents(packageName);
        var selected = documents.stream()
                .filter(document -> document.kind().equals(kind) && document.slug().equals(slug)).findFirst();
        return new DocumentPage(selected, documents);
    }

    /// Returns all project type names. Dependency and platform names are read
    /// on demand and are not in this list.
    List<String> names();

    /// Returns each non-empty source of symbols in display order.
    List<Root> roots();

    /// Returns at most `limit` indexed matches in rank order.
    List<Match> search(String text, int limit);

    /// Releases resources held by the catalog. A catalog with no resources can
    /// keep this default.
    @Override
    default void close() {}
}
