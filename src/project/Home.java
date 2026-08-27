package project;

import java.io.IOException;
import java.net.URISyntaxException;
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

    /// Finds tuul by asking where its own class file came from.
    public static Home find() throws IOException {
        var located = location();
        if (Files.isDirectory(located)) return checkout(located);
        return new Home(located, located.resolveSibling(Version.artifact() + "-sources.jar"),
                located.getParent().resolve("native"));
    }

    /// A checkout: `build/classes` has `src/` and `native/` two levels up.
    private static Home checkout(Path classes) {
        var root = classes.getParent().getParent();
        return new Home(classes, root.resolve("src"), root.resolve("native"));
    }

    /// Whether the parts are actually there. An install that would write half an
    /// artifact should say so before it writes any of it.
    public String missing() {
        if (!Files.exists(classes)) return "no classes at " + classes;
        if (!Files.exists(sources)) return "no sources at " + sources;
        if (!Files.isDirectory(natives)) return "no native sources at " + natives;
        return "";
    }

    public boolean packaged() {
        return !Files.isDirectory(classes);
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
