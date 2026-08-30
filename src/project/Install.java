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
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import tuul.Version;

/// Puts tuul into a project, as an ordinary dependency.
///
/// What lands in `vendor/tuul/` is a jar, a sources jar, and a compiled SQLite
/// for each supported platform. The jar is the named module `tuul`.
///
/// A modular project reads the jar from its module path. An unnamed project
/// reads the same jar from its classpath. The sources jar supplies comments to
/// `tuul docs`. The native libraries let `sqlite3` run without a C compiler.
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
            written = repackSources(home.sources(), sources);
        } else {
            classes = pack(home.classes(), jar, path -> true);
            written = pack(home.sources(), sources, java(".java"));
        }

        var native_ = directory.resolve(Home.NATIVE);
        var platforms = source ? source(home, native_) : binaries(home, native_, log);
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
    /// A distribution carries the prebuilt libraries inside itself, because
    /// that is how an installed tuul has them to hand out. A project is handed
    /// them beside the jar, so carrying them inside it as well is megabytes of
    /// every project that nothing will ever read.
    ///
    /// Web assets are not among them. They sit beside the code that ships them,
    /// under `web/`, so they come across with the classes and a project reads
    /// them out of the jar it already has.
    private static int repack(Path distribution, Path jar) throws IOException {
        return repack(distribution, jar, entry -> !shipped(entry) && library(entry));
    }

    /// Keeps the module declaration and the library sources. The distribution
    /// also carries the CLI source, which is not part of the `tuul` module.
    private static int repackSources(Path distribution, Path jar) throws IOException {
        return repack(distribution, jar, entry -> entry.endsWith(".java") && library(entry));
    }

    private static int repack(Path distribution, Path jar, Predicate<String> wanted) throws IOException {
        var written = 0;
        try (var from = new JarFile(distribution.toFile());
                var out = new JarOutputStream(Files.newOutputStream(jar), manifest())) {
            for (var entry : Collections.list(from.entries())) {
                if (entry.isDirectory() || !wanted.test(entry.getName())) continue;
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
        return entry.startsWith(Home.NATIVE + "/");
    }

    /// A module declaration lives at the archive root. All other library
    /// entries live in a package. This excludes each default-package CLI class
    /// and the `cli/main.java` source.
    private static boolean library(String entry) {
        if (entry.equals("module-info.class") || entry.equals("module-info.java")) return true;
        return entry.contains("/") && !entry.startsWith("cli/");
    }

    private static int pack(Path root, Path jar, Predicate<Path> wanted) throws IOException {
        var manifest = manifest();

        var written = 0;
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (var path : library(root, wanted)) {
                out.putNextEntry(new JarEntry(root.relativize(path).toString()));
                Files.copy(path, out);
                out.closeEntry();
                written++;
            }
        }
        return written;
    }

    private static Manifest manifest() {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, Version.NAME);
        attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, Version.NUMBER);
        return manifest;
    }

    /// The library half of tuul: everything in a package, which leaves out the
    /// default-package entrypoints and the directories they live in.
    ///
    /// A classes tree wants all of it, not only the `.class` files: a package
    /// that ships a stylesheet keeps it beside its own code, so the assets are
    /// in there too and a jar without them is a jar whose pages have no design.
    /// A sources tree wants the Java and nothing else — it is there for
    /// `tuul docs`, which reads comments.
    private static List<Path> library(Path root, Predicate<Path> wanted) throws IOException {
        var found = new ArrayList<Path>();
        try (var tree = Files.walk(root)) {
            tree.filter(Files::isRegularFile)
                    .filter(wanted)
                    .filter(path -> root.relativize(path).getNameCount() > 1 || moduleInfo(path))
                    .filter(path -> !entrypoint(path))
                    .sorted()
                    .forEach(found::add);
        }
        return found;
    }

    private static boolean moduleInfo(Path path) {
        var name = path.getFileName().toString();
        return name.equals("module-info.java") || name.equals("module-info.class");
    }

    private static Predicate<Path> java(String extension) {
        return path -> path.toString().endsWith(extension);
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
