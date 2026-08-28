package web.assets;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Assets that travel with the code that uses them.
///
/// A package that ships a stylesheet or a controller keeps it in a directory
/// beside its own source, and asks for it here. `web.ui` has `src/web/ui/assets`
/// and the browser has `src/browser/assets`, and neither has to know where the
/// other keeps anything.
///
/// ```
/// Feature.named("browser").from(Bundled.of(Browser.class, "assets"));
/// ```
///
/// The build copies everything under `src` that is not Java beside the classes
/// it compiled, and a jar keeps that arrangement, so `src/web/ui/assets` is
/// `web/ui/assets` in both. That is the whole reason this exists: an asset load
/// path is a [Path], and a [Path] does not have to be on the disk the program
/// started from. A jar is a file system too, so one lookup finds a checkout's
/// files and an installed tuul's, and `tuul.jar` copied somewhere on its own
/// still knows what it carries.
///
/// This replaced a single tree of everything, enumerated by listing whatever
/// sat beside the vendored Turbo. That could not tell what a package ships from
/// what an application ships, so a browser's stylesheet was handed to every
/// application anybody wrote — and since logical names are relative to each
/// load path root, the four `ORIGIN.md` files in it collapsed into whichever
/// sorted first. Assets belong to the code they came with.
public final class Bundled {

    /// Open archives, kept for the life of the process.
    ///
    /// A file system that is closed takes its paths with it, and an asset is
    /// read when it is served rather than when it is found — so closing this
    /// would mean a page that renders and a stylesheet that 404s. There is one
    /// per jar, and a program has one or two.
    private static final Map<Path, FileSystem> ARCHIVES = new ConcurrentHashMap<>();

    private Bundled() {}

    /// The directory named `directory`, beside `owner`'s package, in whatever
    /// file system `owner` was loaded from.
    ///
    /// A path is answered even when there is nothing at it, because a load path
    /// that does not exist is skipped by the scan, and somebody reading the
    /// error from a missing asset is better off seeing where it was looked for.
    public static Path of(Class<?> owner, String directory) {
        var pkg = owner.getPackageName().replace('.', '/');
        return root(owner)
                .map(root -> root.resolve(pkg).resolve(directory))
                .orElseGet(() -> Paths.get(pkg, directory));
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
