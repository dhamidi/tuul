package project;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Compiles the C under `native/` into shared libraries, one per module, with
/// `zig cc` — a C compiler that is already a dependency of the toolchain and
/// cross-compiles without a second install.
///
/// Extra compiler arguments go in a `cflags` file beside the sources, one per
/// line. That is a list of arguments, not a configuration language: what is in
/// the file is what the compiler is given.
public final class Native {

    private static final String FLAGS = "cflags";

    /// The libraries built, the ones already up to date, and whatever the
    /// compiler said when it refused.
    public record Result(List<String> built, List<String> current, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private Native() {}

    public static Result build(Layout layout, Writer out) throws IOException, InterruptedException {
        return build(layout, out, ProcessRunner.system());
    }

    /// Builds native modules through `processes`. The runner receives each
    /// compiler command and streams its merged output.
    public static Result build(Layout layout, Writer out, ProcessRunner processes) throws IOException, InterruptedException {
        var modules = layout.natives();
        if (modules.isEmpty()) return new Result(List.of(), List.of(), List.of());

        var built = new ArrayList<String>();
        var current = new ArrayList<String>();
        var problems = new ArrayList<String>();
        for (var module : modules.entrySet()) {
            var library = layout.library(module.getKey());
            if (!stale(library, module.getValue())) {
                current.add(module.getKey());
                continue;
            }
            out.write("compiling native module " + module.getKey() + "\n");
            out.flush();
            var failure = compile(layout, module.getKey(), module.getValue(), library, processes);
            if (failure.isEmpty()) built.add(module.getKey());
            else problems.add(failure);
        }
        return new Result(List.copyOf(built), List.copyOf(current), List.copyOf(problems));
    }

    /// The compiler's own words on failure, and nothing on success.
    private static String compile(Layout layout, String module, List<Path> sources, Path library, ProcessRunner processes)
            throws IOException, InterruptedException {
        Files.createDirectories(library.getParent());
        var command = new ArrayList<>(List.of(compiler(), "cc", "-shared", "-fPIC", "-O2", "-o", library.toString()));
        if (compiler().equals("cc")) command.remove(1);
        sources.forEach(source -> command.add(source.toString()));
        command.addAll(flags(sources.getFirst().getParent()));

        var output = new StringWriter();
        var status = Launch.run(command, layout.root(), output, java.util.Map.of(), processes);
        if (status == 0) return "";
        Files.deleteIfExists(library);
        return module + ": " + (output.toString().isBlank() ? "compiler exited with " + status : output.toString().strip());
    }

    /// `zig cc` where zig is on the path, and the system compiler where it is
    /// not — a project should still build on a machine that has only cc.
    static String compiler() {
        var path = System.getenv("PATH");
        if (path == null) return "cc";
        for (var directory : path.split(":")) {
            if (Files.isExecutable(Path.of(directory, "zig"))) return "zig";
        }
        return "cc";
    }

    /// The arguments beside the sources, if there are any. Shared with
    /// [Natives], so a cross-build compiles with the flags the module names.
    static List<String> flags(Path directory) throws IOException {
        var file = directory.resolve(FLAGS);
        if (!Files.isRegularFile(file)) return List.of();
        return Files.readAllLines(file).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    /// Nothing is recompiled unless something changed: the amalgamation takes
    /// ten seconds, and a build that costs that every time is a build nobody
    /// runs.
    static boolean stale(Path library, List<Path> sources) throws IOException {
        if (!Files.isRegularFile(library)) return true;
        var built = Files.getLastModifiedTime(library).toMillis();
        for (var source : sources) {
            if (Files.getLastModifiedTime(source).toMillis() > built) return true;
        }
        var directory = sources.getFirst().getParent();
        if (!Files.isDirectory(directory)) return false;
        if (newer(directory.resolve(FLAGS), built)) return true;
        try (var tree = Files.walk(directory)) {
            return tree.filter(path -> path.toString().endsWith(".h")).anyMatch(header -> newer(header, built));
        }
    }

    /// A missing file is not newer than anything; a changed one is.

    private static boolean newer(Path path, long than) {
        try {
            return Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() > than;
        } catch (IOException e) {
            return true;
        }
    }
}
