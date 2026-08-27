package project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Where things are in a tuul project. The tree is the configuration: a
/// directory under `src/` holding a `main.java` is an entrypoint, every other
/// one is a library, and `vendor/` is the dependency list.
public record Layout(Path root) {

    public static final String ENTRYPOINT = "main.java";

    public Path src() {
        return root.resolve("src");
    }

    public Path test() {
        return root.resolve("test");
    }

    public Path vendor() {
        return root.resolve("vendor");
    }

    public Path nativeRoot() {
        return root.resolve("native");
    }

    /// Where a native module lands, under the name the platform expects:
    /// `libsqlite3.dylib`, `libsqlite3.so`, `sqlite3.dll`.
    public Path library(String module) {
        return root.resolve("build/native").resolve(System.mapLibraryName(module));
    }

    public Path classes() {
        return root.resolve("build/classes");
    }

    public Path tests() {
        return root.resolve("build/test");
    }

    /// Entrypoints are compiled one directory apart, because every `main.java`
    /// compiles to the same class name.
    public Path entry(String entrypoint) {
        return root.resolve("build/entry").resolve(entrypoint);
    }

    public boolean exists() {
        return Files.isDirectory(src());
    }

    public List<Path> libraries() throws IOException {
        return directories(false);
    }

    public List<String> entrypoints() throws IOException {
        return directories(true).stream().map(path -> path.getFileName().toString()).toList();
    }

    /// The entrypoint to run when none was named: `cli` if there is one, and
    /// otherwise the only one there is.
    public String entrypoint(String named) throws IOException {
        var entrypoints = entrypoints();
        if (!named.isEmpty()) return named;
        if (entrypoints.contains("cli")) return "cli";
        return entrypoints.size() == 1 ? entrypoints.getFirst() : "";
    }

    /// A native module is a directory of C under `native/`, or a single `.c`
    /// file sitting directly in it. Either way it compiles to one library,
    /// named after the directory or the file.
    public Map<String, List<Path>> natives() throws IOException {
        var modules = new LinkedHashMap<String, List<Path>>();
        for (var directory : vendored()) collect(directory, modules);
        collect(nativeRoot(), modules);
        return modules;
    }

    /// A dependency can bring C with it — that is how a project vendoring tuul
    /// gets SQLite without fetching a binary for its own machine. The project's
    /// own `native/` is read last, so a module it defines wins over one that
    /// arrived in `vendor/`.
    private List<Path> vendored() throws IOException {
        if (!Files.isDirectory(vendor())) return List.of();
        try (var tree = Files.list(vendor())) {
            return tree.map(dependency -> dependency.resolve("native")).filter(Files::isDirectory).sorted().toList();
        }
    }

    private static void collect(Path root, Map<String, List<Path>> modules) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var tree = Files.list(root)) {
            for (var path : tree.sorted().toList()) {
                if (Files.isDirectory(path)) sources(path).ifPresent(files -> modules.put(name(path), files));
                else if (path.toString().endsWith(".c")) modules.put(stem(path), List.of(path));
            }
        }
    }

    private static Optional<List<Path>> sources(Path directory) throws IOException {
        try (var tree = Files.walk(directory)) {
            var sources = tree.filter(path -> path.toString().endsWith(".c")).sorted().toList();
            return sources.isEmpty() ? Optional.empty() : Optional.of(sources);
        }
    }

    private static String name(Path path) {
        return path.getFileName().toString();
    }

    private static String stem(Path path) {
        var file = name(path);
        return file.substring(0, file.length() - ".c".length());
    }

    private List<Path> directories(boolean entrypoints) throws IOException {
        if (!exists()) return List.of();
        var found = new ArrayList<Path>();
        try (var tree = Files.list(src())) {
            tree.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve(ENTRYPOINT)) == entrypoints)
                    .sorted()
                    .forEach(found::add);
        }
        return found;
    }
}
