package project;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import tuul.Version;

/// Where the running tuul keeps its own parts.
///
/// A tool that installs itself must find itself first. This finds it in either
/// shape it comes in. A source checkout keeps the classes under `build/` and
/// `src/` and `native/` at the root. An installed artifact is a jar with its
/// sources jar and its C beside it. Both answer the same questions, which is
/// all [Install] asks.
///
/// `root` is the directory that holds the parts. For a checkout it is the top
/// of the checkout. For an artifact it is the directory the jar sits in.
///
/// [#find()] locates the running tuul. [#at(Path)] describes a tuul somewhere
/// else. [#missing()] reports a part that is not there.
public record Home(Path root, Path classes, Path sources, Path natives) {

    /// The directory prebuilt libraries live under, inside a jar and beside
    /// one.
    public static final String NATIVE = "native";

    /// Finds tuul by asking where its own class file came from.
    public static Home find() throws IOException {
        return at(location());
    }

    /// A tuul at a known place, whether or not it is the one running. Both
    /// shapes are recognised by what is there: a directory is a checkout, a
    /// file is an artifact.
    public static Home at(Path located) {
        if (Files.isDirectory(located)) return checkout(located);
        return new Home(located.getParent(), located,
                located.resolveSibling(Version.artifact() + "-sources.jar"),
                located.getParent().resolve(NATIVE));
    }

    /// A checkout, found from a directory of classes inside it.
    private static Home checkout(Path classes) {
        var root = rootOf(classes);
        return new Home(root, classes, root.resolve("src"), root.resolve(NATIVE));
    }

    /// The checkout that holds these classes.
    ///
    /// This reads the directories above the classes and answers the first one
    /// that holds both `src/` and `native/`. It does not count levels, because
    /// the number of them depends on where the compiler was told to write.
    /// `mise run build` writes `build/modules/tuul` and makes it two. An agent
    /// that compiles into `build/mine/modules/tuul` to leave a running server alone makes
    /// it three, and `tuul install` must still work there.
    ///
    /// When no directory above holds both, this answers two levels up. That is
    /// where the classes of a checkout normally sit, so [#missing()] then names
    /// the path a reader expects to see.
    private static Path rootOf(Path classes) {
        for (var above = classes.getParent(); above != null; above = above.getParent()) {
            if (Files.isDirectory(above.resolve("src")) && Files.isDirectory(above.resolve(NATIVE))) return above;
        }
        return classes.getParent().getParent();
    }

    /// Whether the parts are actually there. An install that would write half an
    /// artifact should say so before it writes any of it.
    ///
    /// The C is not one of them: a distribution that carries compiled libraries
    /// has no reason to carry the sources as well, and `--source` says so for
    /// itself when they are missing.
    public String missing() {
        if (!Files.exists(classes)) return "no classes at " + classes;
        if (!Files.exists(sources)) return "no sources at " + sources;
        return "";
    }

    public boolean packaged() {
        return !Files.isDirectory(classes);
    }

    /// The prebuilt libraries this tuul carries, and where they are kept: a
    /// directory in a checkout, entries inside the jar of an installed one.
    ///
    /// A jar is a zip and a zip is a file system, so both answer as a [Path]
    /// and [Install] copies from one without knowing which it got. Closing
    /// releases the archive, if there was one to open.
    public record Libraries(Path root, FileSystem archive) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            if (archive != null) archive.close();
        }
    }

    /// Inside the jar first, because that is how a distribution carries them
    /// and a single file is the point of one; beside it otherwise, which is
    /// what a checkout's `build/dist` and an unpacked install both look like.
    public Libraries libraries() throws IOException {
        return shipped(NATIVE, distribution(), natives);
    }

    /// Web assets are not here, and are not handed out. Every package that
    /// ships one keeps it beside its own code — `web/ui/assets` inside the same
    /// jar as `web/ui/Ui.class` — so a project that has the jar has them, and
    /// [web.assets.Bundled] reads them from wherever the jar is. There used to
    /// be a tree of them copied into every project beside the jar that already
    /// carried a copy.

    private Libraries shipped(String directory, Path unpackaged, Path fallback) throws IOException {
        if (!packaged()) return new Libraries(unpackaged, null);
        var archive = FileSystems.newFileSystem(classes);
        var inside = archive.getPath("/" + directory);
        if (Files.isDirectory(inside)) return new Libraries(inside, archive);
        archive.close();
        return new Libraries(fallback, null);
    }

    /// Where a checkout keeps what it cross-built: derived output, under
    /// `build/` with everything else that can be deleted.
    public Path distribution() {
        return root.resolve("build").resolve("dist").resolve(NATIVE);
    }

    private static Path location() throws IOException {
        try {
            return Path.of(Home.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (URISyntaxException | NullPointerException e) {
            throw new IOException("cannot tell where tuul is installed", e);
        }
    }
}
