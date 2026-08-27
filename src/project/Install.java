package project;

import ffi.Platform;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import tuul.Version;

/// Puts tuul into a project, as an ordinary dependency.
///
/// What lands in `vendor/tuul/` is a jar, a sources jar and a compiled SQLite
/// for every platform tuul ships — nothing tuul-shaped, nothing the build has to
/// be told about. The jar is on the classpath because it is in `vendor/`, the
/// sources jar is what makes `tuul docs application.Application` answer inside
/// the project, and the libraries are what let `sqlite3` work on a machine with
/// no compiler on it at all.
///
/// Every platform, not just this one, because `vendor/` is committed: the
/// person who clones this next is on a different machine, and a dependency that
/// only works where it was installed is not vendored, it is borrowed.
///
/// `--source` vendors the C instead, for a platform tuul has no library for.
/// That one needs a compiler in the project.
///
/// Entrypoints are left out. `main.java` compiles to a class in the default
/// package, and a library that puts one of those on a consumer's classpath is a
/// library that has taken a name it does not own.
public final class Install {

    /// What was written. `platforms` names the libraries that landed, and is
    /// empty when the C was vendored instead — which is the only difference
    /// between the two shapes worth reporting.
    public record Result(Path directory, String version, int classes, int sources, List<String> platforms) {}

    private Install() {}

    public static Result into(Layout layout, boolean source, Writer log) throws IOException, InterruptedException {
        var home = Home.find();
        var missing = home.missing();
        if (!missing.isEmpty()) throw new IOException("this tuul cannot install itself: " + missing);

        var directory = layout.vendor().resolve(Version.NAME);
        Files.createDirectories(directory);
        forget(directory);

        var jar = directory.resolve(Version.artifact() + ".jar");
        var sources = directory.resolve(Version.artifact() + "-sources.jar");
        var classes = 0;
        var written = 0;
        if (home.packaged()) {
            classes = repack(home.classes(), jar);
            Files.copy(home.sources(), sources);
        } else {
            classes = pack(home.classes(), jar, ".class");
            written = pack(home.sources(), sources, ".java");
        }

        var native_ = directory.resolve(Home.NATIVE);
        var platforms = source ? source(home, native_) : binaries(home, native_, log);
        web(home, directory.resolve(Home.ASSETS));
        return new Result(directory, Version.NUMBER, classes, written, platforms);
    }

    /// An older tuul in the same directory would be a second copy of every class
    /// on the project's classpath, so it goes before the new one arrives — and
    /// so does whatever native shape the last install left, since the two are
    /// alternatives rather than additions.
    private static void forget(Path directory) throws IOException {
        try (var existing = Files.list(directory)) {
            for (var path : existing.filter(Install::artifact).toList()) Files.delete(path);
        }
        remove(directory.resolve(Home.NATIVE));
        remove(directory.resolve(Home.ASSETS));
    }

    private static boolean artifact(Path path) {
        var name = path.getFileName().toString();
        return name.startsWith(Version.NAME + "-") && name.endsWith(".jar");
    }

    /// The libraries, one directory per platform. A checkout builds whatever it
    /// has not built yet, because a checkout has the compiler by definition; an
    /// installed tuul carries them and only has to unpack.
    private static List<String> binaries(Home home, Path destination, Writer log)
            throws IOException, InterruptedException {
        if (!home.packaged()) build(home, log);
        var written = new ArrayList<String>();
        try (var libraries = home.libraries()) {
            for (var platform : Platform.SHIPPED) {
                var from = libraries.root().resolve(platform.directory());
                if (!Files.isDirectory(from)) continue;
                copyTree(from, destination.resolve(platform.directory()));
                written.add(platform.directory());
            }
        }
        if (written.isEmpty()) {
            throw new IOException("this tuul carries no prebuilt libraries — install with --source to vendor the C");
        }
        return List.copyOf(written);
    }

    /// Turbo and Stimulus, beside the jar, because that is where a vendored
    /// tuul looks for them. A tuul that carries none is not an error — the
    /// libraries still work, and an import map that cannot resolve a pin says
    /// which directories it looked in.
    private static void web(Home home, Path destination) throws IOException {
        try (var shipped = home.web()) {
            if (Files.isDirectory(shipped.root())) copyTree(shipped.root(), destination);
        }
    }

    /// Cross-builds anything the checkout is missing. `mise run natives` does
    /// the same thing ahead of time; this is here so that installing from a
    /// checkout works without having remembered to.
    private static void build(Home home, Writer log) throws IOException, InterruptedException {
        for (var module : modules(home.natives())) {
            if (Natives.complete(module, home.distribution())) continue;
            var result = Natives.distribute(module, home.distribution(), log);
            if (!result.ok()) throw new IOException(String.join("\n", result.problems()));
        }
    }

    private static List<Path> modules(Path natives) throws IOException {
        if (!Files.isDirectory(natives)) return List.of();
        try (var tree = Files.list(natives)) {
            return tree.filter(Files::isDirectory).sorted().toList();
        }
    }

    /// The C, copied as C — for a platform tuul has no library for, or for
    /// anyone who would rather compile it themselves.
    private static List<String> source(Home home, Path destination) throws IOException {
        var from = home.natives();
        if (!Files.isDirectory(from)) throw new IOException("this tuul carries no native sources at " + from);
        copyTree(from, destination);
        return List.of();
    }

    /// Everything under `root` with this extension, minus the entrypoints.
    /// Copies a distribution jar into a project without what the project
    /// cannot use.
    ///
    /// A distribution carries the prebuilt libraries and the web assets inside
    /// itself, because that is how an installed tuul has them to hand out. A
    /// project is handed them beside the jar, so carrying them inside it as
    /// well is five megabytes of every project that nothing will ever read.
    private static int repack(Path distribution, Path jar) throws IOException {
        var written = 0;
        try (var from = new JarFile(distribution.toFile());
                var out = new JarOutputStream(Files.newOutputStream(jar), from.getManifest())) {
            for (var entry : Collections.list(from.entries())) {
                if (entry.isDirectory() || shipped(entry.getName())) continue;
                if (entry.getName().startsWith("META-INF/")) continue;
                out.putNextEntry(new JarEntry(entry.getName()));
                try (var bytes = from.getInputStream(entry)) {
                    bytes.transferTo(out);
                }
                out.closeEntry();
                written++;
            }
        }
        return written;
    }

    /// What a distribution carries for handing out rather than for running.
    private static boolean shipped(String entry) {
        return entry.startsWith(Home.NATIVE + "/") || entry.startsWith(Home.ASSETS + "/");
    }

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

    /// Copies a directory that may live inside an archive, which is why the
    /// destination is built from strings rather than resolved against a path
    /// from another file system.
    private static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var tree = Files.walk(from)) {
            tree.forEach(path -> copy(path, to.resolve(from.relativize(path).toString())));
        }
    }

    private static void copy(Path from, Path to) {
        try {
            if (Files.isDirectory(from)) Files.createDirectories(to);
            else Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void remove(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var tree = Files.walk(directory)) {
            for (var path : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
