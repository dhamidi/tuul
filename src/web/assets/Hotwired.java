package web.assets;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Where Turbo and Stimulus live.
///
/// They are vendored — the files are in the repository, with their licences and
/// an `ORIGIN.md` saying which versions and where they came from — for the same
/// reason the SQLite amalgamation is: nothing at build or run time should have
/// to reach the network, and an application should not have to install a
/// package manager to get the two libraries its framework assumes.
///
/// They are found the way a native library is found: by asking the code where
/// it is, rather than asking the shell. An installed tuul runs in a directory
/// that knows nothing about it, and what it ships travels with it.
public final class Hotwired {

    /// The bare specifiers an application imports, which are the names npm uses
    /// — so that documentation written for Rails, or for anybody else, is about
    /// this too.
    public static final String TURBO = "@hotwired/turbo";

    public static final String STIMULUS = "@hotwired/stimulus";

    /// The logical names of the two files, for a caller that wants to pin them
    /// under a different specifier or ask this package what it shipped.
    public static final String TURBO_FILE = "turbo.js";

    public static final String STIMULUS_FILE = "stimulus.js";

    private static final String DIRECTORY = "hotwired";

    private Hotwired() {}

    /// The load path holding them. When nothing is found, the last candidate is
    /// answered anyway: an import map that cannot resolve a pin says which
    /// directories it looked in, and a path that does not exist is more use in
    /// that message than no path at all.
    public static Path path() {
        var candidates = candidates();
        return candidates.stream().filter(Files::isDirectory).findFirst().orElse(candidates.getLast());
    }

    private static List<Path> candidates() {
        var override = System.getProperty("tuul.assets");
        if (override != null) return List.of(Path.of(override).resolve(DIRECTORY));
        var candidates = new ArrayList<Path>();
        beside().ifPresent(candidates::add);
        candidates.add(Path.of("assets", DIRECTORY));
        return List.copyOf(candidates);
    }

    /// A jar has them under `assets` beside it, which is where an installed tuul
    /// and a vendored one both put what they ship. A classes directory is
    /// `build/classes` in a checkout, and the vendored files are at the root of
    /// it — two levels up, not beside, because they are source rather than
    /// something the build produced.
    private static Optional<Path> beside() {
        try {
            var code = Path.of(Hotwired.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            var root = Files.isDirectory(code) ? code.getParent().getParent() : code.getParent();
            return Optional.of(root.resolve("assets").resolve(DIRECTORY));
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
