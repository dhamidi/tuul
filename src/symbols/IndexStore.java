package symbols;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Stores symbol facts that an [Index] can reuse.
///
/// The store groups facts by an origin. An origin is fresh when its stored
/// stamp equals the current stamp. A complete origin can answer a missing name
/// without reading the source again.
public interface IndexStore extends AutoCloseable {

    /// The stored state of one origin.
    record Snapshot(long id, boolean fresh, boolean complete) {}

    /// Opens the SQLite store at `file`. An unwritable location returns empty
    /// so that an index can continue without persistence.
    static Optional<IndexStore> open(Path file) {
        return Store.open(file).map(store -> store);
    }

    /// Finds or creates an origin and applies the current stamp.
    Snapshot origin(String kind, String location, String stamp);

    /// Returns one stored symbol from an origin.
    Optional<TypeInfo> type(long origin, String name);

    /// Returns every stored name from an origin in insertion order.
    List<String> names(long origin);

    /// Returns stored names of one kind in name order.
    List<String> names(long origin, TypeInfo.Kind kind);

    /// Returns at most `limit` search matches in rank order.
    List<Catalog.Match> search(String text, int limit);

    /// Replaces the given types in one transaction. `complete` records that the
    /// map contains every symbol from the origin.
    void write(long origin, Map<String, TypeInfo> types, boolean complete);

    /// Releases resources held by the store.
    @Override
    void close();
}
