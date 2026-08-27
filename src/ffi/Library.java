package ffi;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// A shared library, and the handles to call into it.
///
/// `java.lang.foreign` end to end: no JNI, no javah, no generated glue. A
/// library found here stays loaded for the life of the process — it is opened
/// against the global arena, so a handle can never outlive the code it points
/// at.
///
/// A library is looked for where the code asking for it lives, and then where
/// the project being worked on puts its own: beside the jar or classes
/// directory this class was loaded from, and then `build/native` under the
/// working directory. `-Dtuul.native=<dir>[:<dir>]` replaces the lot.
///
/// Asking the code where it is, rather than asking the shell, is what lets an
/// installed tuul work in somebody else's project: it is run from a directory
/// that knows nothing about it, and the library it binds ships with it, not
/// with them. The working directory is still searched afterwards, because that
/// is where a project's own `tuul build` leaves the modules it compiled —
/// including the ones that arrived in `vendor/`.
public final class Library {

    private static final Linker LINKER = Linker.nativeLinker();

    private final String name;
    private final SymbolLookup lookup;

    private Library(String name, SymbolLookup lookup) {
        this.name = name;
        this.lookup = lookup;
    }

    /// Opens `libsqlite3.dylib` — or `.so`, or `.dll` — by its plain name,
    /// `sqlite3`, from the libraries this project built.
    ///
    /// It does not fall back to whatever the operating system has under that
    /// name. A binding is generated against one library's header with one set of
    /// compiler flags, and the system's copy is a different library that happens
    /// to share a name: the difference shows up as a missing symbol, in
    /// production, on somebody else's machine. Use [#system] when the system's
    /// copy is genuinely what is wanted.
    public static Library open(String name) {
        var file = System.mapLibraryName(name);
        var built = directories().stream().map(directory -> directory.resolve(file)).filter(Files::isRegularFile).findFirst();
        if (built.isPresent()) return new Library(name, SymbolLookup.libraryLookup(built.get(), Arena.global()));
        throw new UnsatisfiedLinkError("no " + file + " in " + directories() + " — " + advice());
    }

    /// What to do about it, which depends on whether a library for this machine
    /// was ever built. A platform tuul ships is one `tuul build` away; a
    /// platform it does not ship has to be compiled where it is going to run.
    private static String advice() {
        var host = Platform.host();
        return host.shipped()
                ? "run tuul build to compile the native modules"
                : "there is no prebuilt library for " + host.directory()
                        + " — install with tuul install --source and run tuul build to compile it here";
    }

    /// Opens a library the operating system provides, by asking it. For the ones
    /// that are genuinely the system's — libc, and whatever a machine happens to
    /// have — rather than the ones a project ships.
    public static Library system(String name) {
        var file = System.mapLibraryName(name);
        try {
            return new Library(name, SymbolLookup.libraryLookup(file, Arena.global()));
        } catch (IllegalArgumentException e) {
            throw new UnsatisfiedLinkError("the system has no " + file);
        }
    }

    public static Library open(Path file) {
        return new Library(file.getFileName().toString(), SymbolLookup.libraryLookup(file, Arena.global()));
    }

    /// A downcall handle for one function. The descriptor is the C signature,
    /// spelled out — there is nothing to generate and nothing to keep in sync.
    public MethodHandle function(String function, FunctionDescriptor descriptor) {
        var symbol = lookup.find(function)
                .orElseThrow(() -> new UnsatisfiedLinkError(name + " has no function named " + function));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    /// The same handle, or null when this build of the library does not export
    /// the function — which is the normal case for a binding generated against
    /// a header with more in it than the build has.
    public MethodHandle optional(String function, FunctionDescriptor descriptor) {
        return lookup.find(function).map(symbol -> LINKER.downcallHandle(symbol, descriptor)).orElse(null);
    }

    /// A call site for a variadic function. C decides nothing here: the caller
    /// names the layouts of the arguments it is about to pass.
    public MethodHandle variadic(String function, FunctionDescriptor fixed, MemoryLayout... variadic) {
        var symbol = lookup.find(function)
                .orElseThrow(() -> new UnsatisfiedLinkError(name + " has no function named " + function));
        return LINKER.downcallHandle(
                symbol,
                fixed.appendArgumentLayouts(variadic),
                Linker.Option.firstVariadicArg(fixed.argumentLayouts().size()));
    }

    public boolean has(String function) {
        return lookup.find(function).isPresent();
    }

    private static List<Path> directories() {
        var override = System.getProperty("tuul.native");
        if (override != null) return List.of(override.split(File.pathSeparator)).stream().map(Path::of).toList();
        var directories = new ArrayList<>(beside());
        directories.add(Path.of("build", "native"));
        return List.copyOf(directories);
    }

    /// Where the running code keeps its own libraries. A classes directory has
    /// them in its sibling `native` — `build/classes` and `build/native` — and a
    /// jar has them beside it, or under a `native` directory beside it, which is
    /// where an installed tuul and a vendored one both put what they ship.
    ///
    /// Each of those is looked at twice: once as it is, for a library built for
    /// this machine and nothing else, and once under this machine's platform
    /// directory, for a dependency carrying a library for every machine its
    /// project might be checked out on.
    private static List<Path> beside() {
        try {
            var code = Path.of(Library.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            var natives = Files.isDirectory(code)
                    ? List.of(code.resolveSibling("native"))
                    : List.of(code.getParent(), code.getParent().resolve("native"));
            var directories = new ArrayList<Path>();
            var platform = Platform.host().directory();
            for (var directory : natives) {
                directories.add(directory);
                directories.add(directory.resolve(platform));
            }
            return List.copyOf(directories);
        } catch (URISyntaxException | RuntimeException e) {
            return List.of();
        }
    }
}
