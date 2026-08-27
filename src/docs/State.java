package docs;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/// What the docs application knows: where to look — the project's sources and
/// its vendored dependencies — how to print, and whether anything went wrong.
public record State(
        List<Path> sourcePath,
        List<Path> vendorPath,
        boolean json,
        Set<String> sections,
        boolean all,
        int exit) {

    public static State of(List<Path> sourcePath, List<Path> vendorPath) {
        return new State(sourcePath, vendorPath, false, Set.of(), false, 0);
    }

    public State asking(List<Path> sourcePath, List<Path> vendorPath, boolean json, Set<String> sections, boolean all) {
        return new State(sourcePath, vendorPath, json, sections, all, exit);
    }

    public State failed() {
        return new State(sourcePath, vendorPath, json, sections, all, 1);
    }
}
