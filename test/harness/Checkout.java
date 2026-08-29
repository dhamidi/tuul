package harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import project.Home;

/// The checkout a test reads its own source out of.
///
/// Some tests read `src/` rather than the classes built from it. One indexes
/// tuul's source to give `tuul browse` something real to serve. Another reads
/// `src/actors` to check that one file alone names `jdk.jfr`.
///
/// Those tests used to write `Path.of("src")`, which is the working directory
/// and not the checkout. It agreed with the checkout whenever `mise` started
/// the run, and it was one `cd` away from reading nothing. This asks
/// [project.Home] instead, which finds the checkout from the classes that are
/// running, so a test reads the source those classes were built from.
public final class Checkout {

    private Checkout() {}

    /// The top of the checkout.
    public static Path root() {
        try {
            return Home.find().root();
        } catch (IOException unlocatable) {
            throw new UncheckedIOException(unlocatable);
        }
    }

    /// A path under the top of the checkout, such as `src` or `spec/browse`.
    public static Path at(String first, String... rest) {
        return root().resolve(Path.of(first, rest));
    }
}
