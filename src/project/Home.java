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
/// A tool that installs itself has to be able to find itself. There are two
/// shapes it can be in: a source checkout, where the classes are in
/// `build/classes` with `src/` and `native/` beside them, and an installed
/// artifact, where the jar sits in a directory with its sources jar and its C
/// next to it. Both answer the same three questions, which is all [Install]
/// asks.
public record Home(Path classes, Path sources, Path natives) {

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
        return new Home(located, located.resolveSibling(Version.artifact() + "-sources.jar"),
                located.getParent().resolve(NATIVE));
    }

    /// A checkout: `build/classes` has `src/` and `native/` two levels up.
    private static Home checkout(Path classes) {
        var root = classes.getParent().getParent();
        return new Home(classes, root.resolve("src"), root.resolve("native"));
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
        if (!packaged()) return new Libraries(distribution(), null);
        var archive = FileSystems.newFileSystem(classes);
        var inside = archive.getPath("/" + NATIVE);
        if (Files.isDirectory(inside)) return new Libraries(inside, archive);
        archive.close();
        return new Libraries(natives(), null);
    }

    /// Where a checkout keeps what it cross-built: derived output, under
    /// `build/` with everything else that can be deleted.
    public Path distribution() {
        return classes.resolveSibling("dist").resolve(NATIVE);
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
