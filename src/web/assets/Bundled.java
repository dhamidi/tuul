package web.assets;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Where assets are, when nobody has said where they are.
///
/// An asset load path is a directory, and a directory is a [Path] — but a
/// [Path] does not have to be on the disk the program started from. A jar is a
/// file system too, so the same code finds `browser/assets` in a checkout and
/// inside `tuul.jar`, and neither the pipeline nor the application has to know
/// which one it got.
///
/// Two things travel this way, and they are not the same thing:
///
///   - **What an application ships.** Its assets belong to it, so they live
///     beside its code and are found from its code. [#of(Class, String)].
///   - **What tuul ships.** Turbo, Stimulus, the cable's controller and the
///     component stylesheet, which every application gets without asking.
///     [#shipped()].
///
/// Keeping them apart is the point. They used to be one tree, so an
/// application's own stylesheet was handed to every other application that
/// ever used tuul, and the four provenance files in it collapsed into
/// whichever one sorted first.
public final class Bundled {

    /// Open archives, kept for the life of the process.
    ///
    /// A file system that is closed takes its paths with it, and an asset is
    /// read when it is served rather than when it is found — so closing this
    /// would mean a page that renders and a stylesheet that 404s. There is one
    /// per jar, and a program has one or two.
    private static final Map<Path, FileSystem> ARCHIVES = new ConcurrentHashMap<>();

    private static final String DIRECTORY = "assets";

    private Bundled() {}

    /// The directory of assets that travels with `owner`, named `directory`
    /// and sitting beside `owner`'s package.
    ///
    /// ```
    /// Assets.standard(List.of(Bundled.of(Browser.class, "assets")));
    /// ```
    ///
    /// The build copies everything under `src` that is not Java beside the
    /// classes it compiled, and a jar keeps that arrangement, so
    /// `src/browser/assets` is `browser/assets` in both — one name, two file
    /// systems.
    ///
    /// A path is answered even when there is nothing at it, because a load path
    /// that does not exist is skipped by the scan, and a caller reading the
    /// error from a missing asset is better off seeing where it was looked for.
    public static Path of(Class<?> owner, String directory) {
        var pkg = owner.getPackageName().replace('.', '/');
        return root(owner)
                .map(root -> root.resolve(pkg).resolve(directory))
                .orElseGet(() -> Path.of(pkg, directory));
    }

    /// Every directory of assets tuul ships: Turbo and Stimulus, the cable's
    /// controller, the components' stylesheet, and whatever ships later.
    ///
    /// They are enumerated rather than named, so a package that ships assets
    /// does not have to be known here — `web.assets` would otherwise import
    /// `web.cable` to put its one file on the load path, which is backwards.
    /// What may be enumerated is exactly what tuul ships: an application's
    /// assets live with the application and reach the pipeline through
    /// [#of(Class, String)].
    public static List<Path> shipped() {
        var root = root();
        if (!Files.isDirectory(root)) return List.of(root);
        try (var directories = Files.list(root)) {
            var found = directories.filter(Files::isDirectory).sorted().toList();
            return found.isEmpty() ? List.of(root) : found;
        } catch (IOException unreadable) {
            return List.of(root);
        }
    }

    /// One directory tuul ships, by name.
    ///
    /// A package that ships assets asks for its own by name rather than
    /// working out where it is, which is why nothing but this has to know how
    /// tuul finds what it carries.
    public static Path shipped(String name) {
        return root().resolve(name);
    }

    /// The directory holding what tuul ships. When nothing is found, the last
    /// candidate is answered anyway: an import map that cannot resolve a pin
    /// says which directories it looked in, and a path that does not exist is
    /// more use in that message than no path at all.
    private static Path root() {
        var candidates = candidates();
        return candidates.stream().filter(Files::isDirectory).findFirst().orElse(candidates.getLast());
    }

    private static List<Path> candidates() {
        var override = System.getProperty("tuul.assets");
        if (override != null) return List.of(Path.of(override));
        var candidates = new ArrayList<Path>();
        root(Bundled.class).map(root -> root.resolve(DIRECTORY)).ifPresent(candidates::add);
        beside().ifPresent(candidates::add);
        candidates.add(Path.of(DIRECTORY));
        return List.copyOf(candidates);
    }

    /// A jar carries them inside it, and an installed tuul also has them
    /// unpacked beside it. Inside comes first: a jar that is copied somewhere
    /// on its own still knows what it ships.
    private static Optional<Path> beside() {
        return code(Bundled.class).map(code -> Files.isDirectory(code)
                ? code.getParent().getParent().resolve(DIRECTORY)
                : code.getParent().resolve(DIRECTORY));
    }

    /// The root of the file system `owner` was loaded from: the directory of
    /// classes, or the inside of the jar.
    private static Optional<Path> root(Class<?> owner) {
        return code(owner).flatMap(code -> {
            if (Files.isDirectory(code)) return Optional.of(code);
            try {
                return Optional.of(archive(code).getPath("/"));
            } catch (RuntimeException unreadable) {
                return Optional.empty();
            }
        });
    }

    /// Where the code itself is, asked of the code rather than of the shell —
    /// an installed tuul runs in a directory that knows nothing about it.
    private static Optional<Path> code(Class<?> owner) {
        try {
            var source = owner.getProtectionDomain().getCodeSource();
            if (source == null) return Optional.empty();
            return Optional.of(Path.of(source.getLocation().toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static FileSystem archive(Path jar) {
        return ARCHIVES.computeIfAbsent(jar, Bundled::open);
    }

    /// A jar as a file system. The JDK keeps one per URI, so a jar that
    /// something else has already opened is joined rather than opened twice.
    private static FileSystem open(Path jar) {
        var uri = URI.create("jar:" + jar.toUri());
        try {
            return FileSystems.newFileSystem(uri, Map.of());
        } catch (FileSystemAlreadyExistsException already) {
            return FileSystems.getFileSystem(uri);
        } catch (IOException unreadable) {
            throw new AssetException("cannot read the assets in " + jar, unreadable);
        }
    }
}
