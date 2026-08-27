package tuul;

/// Which tuul this is.
///
/// A tool that installs itself into other people's projects has to be able to
/// say what it is: the number goes into the artifact's name, into its manifest,
/// and into `tuul --version`. It is written here and nowhere else, so bumping a
/// release is one edit.
public final class Version {

    /// The name of the artifact, the directory it is vendored into, and the
    /// library everything else in tuul belongs to.
    public static final String NAME = "tuul";

    /// Bumped by hand. `-dev` because nothing has been released: a project that
    /// vendors this is vendoring a moving target, and the version should say so.
    public static final String NUMBER = "0.1.0-dev";

    private Version() {}

    /// `tuul 0.1.0-dev` — what `tuul --version` prints.
    public static String describe() {
        return NAME + " " + NUMBER;
    }

    /// `tuul-0.1.0-dev` — what the jar is called, and the directory under
    /// `vendor/` that holds it.
    public static String artifact() {
        return NAME + "-" + NUMBER;
    }
}
