package symbols;

import java.util.List;
import java.util.Optional;

/// Answers questions about the symbols available to a caller.
///
/// [Index] is the system catalog. A caller can provide another catalog when
/// symbols come from memory or from a service. The caller closes the catalog
/// after the last lookup.
public interface Catalog extends AutoCloseable {

    /// One source of symbols and the names at its top level.
    record Root(String name, String label, List<String> contents) {}

    /// One search result. `modifiers` is empty when the symbol has none.
    record Match(String symbol, String kind, String modifiers, String doc) {}

    /// Looks up one type, package, or module. A nested type can use source form
    /// with dots instead of its binary name with `$`.
    Optional<TypeInfo> lookup(String name);

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
