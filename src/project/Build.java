package project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import compiler.Compiler;
import compiler.Compilation;
import symbols.Vendor;

/// Compiles a project onto disk: the libraries together, then each entrypoint
/// on top of them, one output directory apart. Successful source fingerprints
/// live under `build/.tuul`, so asking for the same build again does not start
/// javac just to discover that nothing changed.
///
/// A project compiles against the jars in `vendor/` and nothing else. If
/// `src/module-info.java` exists, javac reads those jars from the module path.
/// Otherwise, javac reads them from the classpath. Entrypoints and tests stay
/// unnamed. Tuul does not enable preview features. Files in `src/resources/`
/// land at the root of `build/classes/` and take part in its fingerprint.
public final class Build {

    /// What a compile did, or what stopped it. Problems are javac's own words.
    public record Result(int classes, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private Build() {}

    public static Result compile(Layout layout) throws IOException {
        return compile(layout, Compiler.system());
    }

    /// Compiles a project through `compiler`. Fingerprints and resources use
    /// the same filesystem behavior as [#compile(Layout)].
    public static Result compile(Layout layout, Compiler compiler) throws IOException {
        if (!layout.exists()) return new Result(0, List.of("no src/ in " + layout.root().toAbsolutePath()));
        var vendor = Vendor.of(List.of(layout.vendor()));
        var dependencies = vendor.runtime();
        var descriptor = layout.src().resolve("module-info.java");
        var libraryRoots = layout.libraries();
        var librarySources = sources(libraryRoots);
        if (Files.isRegularFile(descriptor)) librarySources.addFirst(descriptor);

        var fingerprintRoots = new ArrayList<>(libraryRoots);
        fingerprintRoots.add(layout.resources());
        var libraryFingerprint = fingerprint(fingerprintRoots, descriptor, vendor.stamp());
        var compileFingerprint = Compilation.fingerprint(
                librarySources, dependencies, Files.isRegularFile(descriptor), Runtime.version().feature(), true);
        Result libraries;
        if (current(layout, "libraries", libraryFingerprint, layout.classes())) {
            libraries = new Result(written(layout.classes()), List.of());
        } else {
            clear(layout.classes());
            libraries = javac(librarySources, layout.classes(), dependencies, Files.isRegularFile(descriptor), compiler);
            if (!libraries.ok()) return libraries;
            resources(libraryRoots, layout.src(), layout.classes());
            resources(List.of(layout.resources()), layout.resources(), layout.classes());
            remember(layout, "libraries", libraryFingerprint);
        }
        remember(layout, "libraries.compile", compileFingerprint);

        var classes = libraries.classes();
        var entrypoints = layout.entrypoints();
        clearStaleEntrypoints(layout, entrypoints);
        for (var entrypoint : entrypoints) {
            var directory = layout.src().resolve(entrypoint);
            var fingerprint = fingerprint(List.of(directory), null, vendor.stamp() + libraryFingerprint);
            Result built;
            if (current(layout, "entry-" + entrypoint, fingerprint, layout.entry(entrypoint))) {
                built = new Result(written(layout.entry(entrypoint)), List.of());
            } else {
                clear(layout.entry(entrypoint));
                built = javac(sources(List.of(directory)), layout.entry(entrypoint),
                        with(dependencies, layout.classes()), false, compiler);
                if (!built.ok()) return built;
                resources(List.of(directory), directory, layout.entry(entrypoint));
                remember(layout, "entry-" + entrypoint, fingerprint);
            }
            classes += built.classes();
        }
        return new Result(classes, List.of());
    }

    /// Tests are compiled on top of a built project, into a directory of their
    /// own, and run by their `run` class.
    public static Result compileTests(Layout layout) throws IOException {
        return compileTests(layout, Compiler.system());
    }

    /// Compiles tests through `compiler`. A current test fingerprint returns
    /// without calling the compiler.
    public static Result compileTests(Layout layout, Compiler compiler) throws IOException {
        if (!Files.isDirectory(layout.test())) return new Result(0, List.of("no test/ in " + layout.root().toAbsolutePath()));
        var vendor = Vendor.of(List.of(layout.vendor()));
        var fingerprint = fingerprint(List.of(layout.test()), null, vendor.testStamp() +
                fingerprint(List.of(layout.src()), null, vendor.stamp()));
        if (current(layout, "tests", fingerprint, layout.tests())) {
            return new Result(written(layout.tests()), List.of());
        }
        clear(layout.tests());
        var classpath = with(vendor.test(), layout.classes());
        var built = javac(sources(List.of(layout.test())), layout.tests(), classpath, false, compiler);
        if (built.ok()) {
            resources(List.of(layout.test()), layout.test(), layout.tests());
            remember(layout, "tests", fingerprint);
        }
        return built;
    }

    private static boolean current(Layout layout, String name, String fingerprint, Path output) throws IOException {
        var stamp = stamp(layout, name);
        return Files.isDirectory(output) && Files.isRegularFile(stamp)
                && Files.readString(stamp).equals(fingerprint);
    }

    private static void remember(Layout layout, String name, String fingerprint) throws IOException {
        var directory = layout.root().resolve("build/.tuul");
        Files.createDirectories(directory);
        var stamp = directory.resolve(name + ".stamp");
        var temporary = Files.createTempFile(directory, name + ".", ".tmp");
        try {
            Files.writeString(temporary, fingerprint);
            try {
                Files.move(temporary, stamp, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, stamp, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path stamp(Layout layout, String name) {
        return layout.root().resolve("build/.tuul").resolve(name + ".stamp");
    }

    private static String fingerprint(List<Path> roots, Path descriptor, String dependency) throws IOException {
        var digest = sha256();
        update(digest, dependency);
        for (var root : roots) {
            update(digest, root.toString());
            if (Files.isRegularFile(root)) {
                file(digest, root, root.getFileName().toString());
                continue;
            }
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                for (var path : tree.filter(Files::isRegularFile).sorted().toList()) {
                    file(digest, path, root.relativize(path).toString());
                }
            }
        }
        if (descriptor != null && Files.isRegularFile(descriptor)) file(digest, descriptor, descriptor.toString());
        update(digest, String.valueOf(Runtime.version().feature()));
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void file(MessageDigest digest, Path path, String name) throws IOException {
        update(digest, name);
        try (InputStream input = Files.newInputStream(path)) {
            var buffer = new byte[8192];
            for (var read = input.read(buffer); read >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
                read = input.read(buffer);
            }
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void clearStaleEntrypoints(Layout layout, List<String> entrypoints) throws IOException {
        var output = layout.root().resolve("build/entry");
        if (!Files.isDirectory(output)) return;
        try (var tree = Files.list(output)) {
            for (var directory : tree.filter(Files::isDirectory).toList()) {
                if (!entrypoints.contains(directory.getFileName().toString())) clear(directory);
            }
        }
    }

    private static void clear(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var tree = Files.walk(directory)) {
            for (var path : tree.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Result javac(List<Path> sources, Path out, List<Path> classpath, boolean module, Compiler compiler)
            throws IOException {
        if (sources.isEmpty()) return new Result(0, List.of());
        Files.createDirectories(out);
        var result = compiler.compile(new Compiler.Request(
                sources, classpath, module, Runtime.version().feature(), true), name -> {
                    var file = out.resolve(name.replace('.', '/') + ".class");
                    Files.createDirectories(file.getParent());
                    return Files.newOutputStream(file);
                });
        return result.ok() ? new Result(result.classes(), List.of()) : new Result(0, report(result.problems()));
    }

    /// Copies non-Java files to their classpath locations.
    ///
    /// `from` defines the resource path. A normal library resource is relative
    /// to `src/` and stays package-local. A root resource is relative to
    /// `src/resources/` and lands directly in `build/classes/`. An entrypoint
    /// resource is relative to its entrypoint directory.
    private static void resources(List<Path> roots, Path from, Path out) throws IOException {
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                for (var file : tree.filter(Files::isRegularFile).sorted().toList()) {
                    if (file.toString().endsWith(".java")) continue;
                    var to = out.resolve(from.relativize(file).toString());
                    Files.createDirectories(to.getParent());
                    Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static List<Path> sources(List<Path> roots) throws IOException {
        var sources = new ArrayList<Path>();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                tree.filter(path -> path.toString().endsWith(".java")).sorted().forEach(sources::add);
            }
        }
        return sources;
    }

    private static int written(Path out) throws IOException {
        try (var tree = Files.walk(out)) {
            return (int) tree.filter(path -> path.toString().endsWith(".class")).count();
        }
    }

    private static List<String> report(List<Compiler.Problem> problems) {
        return problems.stream()
                .map(Build::describe)
                .limit(20)
                .toList();
    }

    private static String describe(Compiler.Problem problem) {
        var where = problem.source() == null
                ? ""
                : problem.source().getFileName() + ":" + problem.line() + " ";
        return where + problem.message();
    }

    private static List<Path> with(List<Path> classpath, Path extra) {
        var all = new ArrayList<>(classpath);
        all.add(extra);
        return all;
    }
}
