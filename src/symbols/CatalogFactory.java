package symbols;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Opens a symbol catalog for one project snapshot.
///
/// [#system()] opens the compiler-backed, SQLite-backed [Index]. A caller can
/// provide another factory to control where symbol queries read from.
@FunctionalInterface
public interface CatalogFactory {

    /// Opens a catalog for the given source roots, vendor roots, and index file.
    Catalog open(List<Path> sourceRoots, List<Path> vendorRoots, Path index) throws IOException;

    /// Returns the factory used by command-line symbol queries. The index
    /// refreshes itself when a question needs it, and not before: opening it
    /// costs a walk of the source tree, not a compile.
    static CatalogFactory system() {
        return (sources, vendor, index) -> Index.of(sources, vendor, index);
    }
}
