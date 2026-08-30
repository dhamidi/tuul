package symbols;

import java.util.Optional;

/// Finds class and source material in one symbol origin.
///
/// Project output, vendored jars, and the running JDK implement the same
/// lookup. The index applies precedence and persistence after this lookup.
@FunctionalInterface
public interface SymbolOrigin {

    /// Returns the material filed under one binary name. A missing name does
    /// not prove that another origin lacks it.
    Optional<Origin> lookup(String binaryName);
}
