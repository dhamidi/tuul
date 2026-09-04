package symbols;

import java.util.Optional;

/// Where a type came from: the class file that says what it is, the source that
/// says what it means when there is one, and where that source lives.
///
/// The three places tuul looks all answer with this: javac's output for the
/// project, a jar under `vendor/`, and `jrt:/` for the JDK.
///
/// `location` is what a reader would need to open the source themselves. Only
/// one of the three is a file they can open — a project's `src/a/B.java` — and
/// the other two name an entry inside an archive, in the shape a jar URL uses:
/// `vendor/x/x-sources.jar!/a/B.java`. Saying which archive and which entry is
/// worth more than saying nothing, even where nothing can click it.
public record Origin(byte[] classFile, Optional<String> source, String location, String module) {

    public Origin(byte[] classFile, Optional<String> source) {
        this(classFile, source, "", "");
    }

    public Origin(byte[] classFile, Optional<String> source, String location) {
        this(classFile, source, location, "");
    }
}
