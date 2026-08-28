package web.assets;

import java.nio.file.Path;

/// Turbo and Stimulus: the names an application imports them by, and the files
/// they are in.
///
/// They are vendored — the files are in the repository, with their licences and
/// an `ORIGIN.md` saying which versions and where they came from — for the same
/// reason the SQLite amalgamation is: nothing at build or run time should have
/// to reach the network, and an application should not have to install a
/// package manager to get the two libraries its framework assumes.
///
/// Where they are is [Bundled]'s question, not this one. This used to answer it
/// for every package that ships assets, so `Hotwired.shipped()` meant "every
/// directory tuul carries" — a name that told a reader the opposite of what the
/// method did, and a reason for `web.cable` to ask about Turbo to find its own
/// file.
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

    /// The load path holding them, which [Assets#standard(List)] puts on every
    /// application's without being asked: a framework that assumes Turbo has to
    /// supply it.
    public static Path path() {
        return Bundled.of(Hotwired.class, DIRECTORY);
    }
}
