package project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
