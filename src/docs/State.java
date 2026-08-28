package docs;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import symbols.Index;

/// What the docs application knows: where to look — the project's sources, its
/// vendored dependencies, and the index kept between runs — how to print, and
/// whether anything went wrong.
public record State(
        List<Path> sourcePath,
        List<Path> vendorPath,
        Path index,
        boolean json,
        Set<String> sections,
        boolean all,
        int exit) {

    public static State of(List<Path> sourcePath, List<Path> vendorPath) {
        return of(sourcePath, vendorPath, Index.INDEX);
    }

    /// The same, keeping the index somewhere else. A test says so, because the
    /// index under `build/` belongs to the project rather than to a test.
    public static State of(List<Path> sourcePath, List<Path> vendorPath, Path index) {
        return new State(sourcePath, vendorPath, index, false, Set.of(), false, 0);
    }

    public State asking(List<Path> sourcePath, List<Path> vendorPath, boolean json, Set<String> sections, boolean all) {
        return new State(sourcePath, vendorPath, index, json, sections, all, exit);
    }

    public State failed() {
        return new State(sourcePath, vendorPath, index, json, sections, all, 1);
    }
}
