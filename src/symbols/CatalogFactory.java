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

    /// Returns the factory used by command-line symbol queries.
    ///
    /// The returned index refreshes itself only when a query needs an
    /// answer. Opening it walks the source tree. Opening it does not
    /// compile the project.
    static CatalogFactory system() {
        return (sources, vendor, index) -> Index.of(sources, vendor, index);
    }
}
