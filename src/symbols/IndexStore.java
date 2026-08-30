package symbols;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Stores symbol facts that an [Index] can reuse and publish.
///
/// The store groups facts by an origin. An origin is fresh when its stored
/// stamp equals the current stamp. A complete origin can answer a missing name
/// without reading the source again. [#inspect] never changes committed rows.
/// [#publish] replaces rows and their stamp in one transaction.
public interface IndexStore extends AutoCloseable {

    /// The stored state of one origin.
    record Snapshot(long id, boolean fresh, boolean complete) {}

    /// Opens the SQLite store at `file`. An unwritable location returns empty
    /// so that an index can continue without persistence.
    static Optional<IndexStore> open(Path file) {
        return Store.open(file).map(store -> store);
    }

    /// Inspects freshness without changing the committed index. Empty means the
    /// origin has never been published.
    Optional<Snapshot> inspect(String kind, String location, String stamp);

    /// Returns one stored symbol from an origin.
    Optional<TypeInfo> type(long origin, String name);

    /// Returns one stored package document.
    Optional<Document> document(long origin, String packageName, String kind, String slug);

    /// Returns stored package documents in source filename order. An empty kind
    /// returns all kinds.
    List<Document> documents(long origin, String packageName, String kind);

    /// Returns every stored name from an origin in insertion order.
    List<String> names(long origin);

    /// Returns stored names of one kind in name order.
    List<String> names(long origin, TypeInfo.Kind kind);

    /// Returns at most `limit` search matches in rank order.
    List<Catalog.Match> search(String text, int limit);

    /// Returns the stored browser root summary.
    default List<Catalog.Root> roots() {
        return List.of();
    }

    /// Replaces one origin and stamps it complete in the same transaction.
    void publish(String kind, String location, String stamp,
            Map<String, TypeInfo> types, List<Document> documents);

    /// Publishes newly learned rows for an origin that is indexed on demand.
    /// A changed stamp forgets the old rows in the same transaction that adds
    /// the first replacement rows.
    void publishIncremental(String kind, String location, String stamp, Map<String, TypeInfo> types);

    /// Replaces the browser root summary.
    default void publishRoots(List<Catalog.Root> roots) {}

    /// Releases resources held by the store.
    @Override
    void close();
}
