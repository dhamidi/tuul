package project;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import tuul.Version;

/// Puts tuul into a project, as an ordinary dependency.
///
/// What lands in `vendor/tuul/` is a jar, a sources jar and the C that
/// `sqlite3` binds — nothing tuul-shaped, nothing the build has to be told
/// about. The jar is on the classpath because it is in `vendor/`, the sources
/// jar is what makes `tuul docs application.Application` answer inside the
/// project, and the C is compiled by the project's own `tuul build` into its own
/// `build/native`, which is why SQLite works there without anybody fetching a
/// binary.
///
/// Entrypoints are left out. `main.java` compiles to a class in the default
/// package, and a library that puts one of those on a consumer's classpath is a
/// library that has taken a name it does not own.
public final class Install {

    public record Result(Path directory, String version, int classes, int sources, boolean natives) {}

    private Install() {}

    public static Result into(Layout layout) throws IOException {
        var home = Home.find();
        var missing = home.missing();
        if (!missing.isEmpty()) throw new IOException("this tuul cannot install itself: " + missing);

        var directory = layout.vendor().resolve(Version.NAME);
        Files.createDirectories(directory);
        forget(directory);

        var jar = directory.resolve(Version.artifact() + ".jar");
        var sources = directory.resolve(Version.artifact() + "-sources.jar");
        if (home.packaged()) {
            Files.copy(home.classes(), jar);
            Files.copy(home.sources(), sources);
            return new Result(directory, Version.NUMBER, 0, 0, natives(home, directory));
        }
        var classes = pack(home.classes(), jar, ".class");
        var written = pack(home.sources(), sources, ".java");
        return new Result(directory, Version.NUMBER, classes, written, natives(home, directory));
    }

    /// An older tuul in the same directory would be a second copy of every class
    /// on the project's classpath, so it goes before the new one arrives.
    private static void forget(Path directory) throws IOException {
        try (var existing = Files.list(directory)) {
            for (var path : existing.filter(Install::artifact).toList()) Files.delete(path);
        }
    }

    private static boolean artifact(Path path) {
        var name = path.getFileName().toString();
        return name.startsWith(Version.NAME + "-") && name.endsWith(".jar");
    }

    /// Everything under `root` with this extension, minus the entrypoints.
    private static int pack(Path root, Path jar, String extension) throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, Version.NAME);
        attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, Version.NUMBER);

        var written = 0;
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (var path : library(root, extension)) {
                out.putNextEntry(new JarEntry(root.relativize(path).toString()));
                Files.copy(path, out);
                out.closeEntry();
                written++;
            }
        }
        return written;
    }

    /// The library half of tuul: everything in a package, which leaves out the
    /// default-package entrypoints and the directories they live in.
    private static List<Path> library(Path root, String extension) throws IOException {
        var found = new ArrayList<Path>();
        try (var tree = Files.walk(root)) {
            tree.filter(path -> path.toString().endsWith(extension))
                    .filter(path -> root.relativize(path).getNameCount() > 1)
                    .filter(path -> !entrypoint(path))
                    .sorted()
                    .forEach(found::add);
        }
        return found;
    }

    private static boolean entrypoint(Path path) {
        return Files.isRegularFile(path.resolveSibling(Layout.ENTRYPOINT));
    }

    /// The C, copied as C. A prebuilt library would be faster and would only
    /// work on the machine that built it; `vendor/` is not the place for
    /// something that is true of one operating system.
    private static boolean natives(Home home, Path directory) throws IOException {
        var from = home.natives();
        if (!Files.isDirectory(from)) return false;
        var to = directory.resolve("native");
        try (var tree = Files.walk(from)) {
            tree.forEach(path -> copy(path, to.resolve(from.relativize(path).toString())));
        }
        return true;
    }

    private static void copy(Path from, Path to) {
        try {
            if (Files.isDirectory(from)) Files.createDirectories(to);
            else Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
