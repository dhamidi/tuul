package ffi;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// A shared library, and the handles to call into it.
///
/// `java.lang.foreign` end to end: no JNI, no javah, no generated glue. A
/// library found here stays loaded for the life of the process — it is opened
/// against the global arena, so a handle can never outlive the code it points
/// at.
///
/// Libraries are looked for in `build/native` first, which is where
/// `tuul build` puts them, and then wherever the operating system looks. Set
/// `-Dtuul.native=<dir>[:<dir>]` to look somewhere else.
public final class Library {

    private static final Linker LINKER = Linker.nativeLinker();

    private final String name;
    private final SymbolLookup lookup;

    private Library(String name, SymbolLookup lookup) {
        this.name = name;
        this.lookup = lookup;
    }

    /// Opens `libsqlite3.dylib` — or `.so`, or `.dll` — by its plain name,
    /// `sqlite3`.
    public static Library open(String name) {
        var file = System.mapLibraryName(name);
        var built = directories().stream().map(directory -> directory.resolve(file)).filter(Files::isRegularFile).findFirst();
        if (built.isPresent()) return new Library(name, SymbolLookup.libraryLookup(built.get(), Arena.global()));
        return new Library(name, system(name, file));
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
        return List.of(System.getProperty("tuul.native", "build/native").split(File.pathSeparator))
                .stream()
                .map(Path::of)
                .toList();
    }

    private static SymbolLookup system(String name, String file) {
        try {
            return SymbolLookup.libraryLookup(file, Arena.global());
        } catch (IllegalArgumentException e) {
            throw new UnsatisfiedLinkError(
                    "no native library named " + name + ": looked for " + file + " in "
                            + directories() + " and in the system library path — run tuul build");
        }
    }
}
