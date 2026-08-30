package project;

import ffi.Platform;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Builds one native module for every platform tuul ships, not just the one
/// doing the building.
///
/// This is what lets `tuul install` hand a project a library it can use without
/// a compiler: the compiling happened once, here, on whatever machine built the
/// distribution. `zig cc` cross-compiles by being told a target, so six
/// platforms cost six invocations and no extra toolchain.
///
/// A project that vendors the result gets every platform, because `vendor/` is
/// committed and the next person to clone it is on a different machine.
public final class Natives {

    /// The platforms built, the ones already current, and what the compiler
    /// said about the rest.
    public record Result(List<String> built, List<String> current, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private Natives() {}

    /// Cross-builds `module` — a directory of C under `native/` — into
    /// `out/<platform>/<library>`, skipping the platforms already up to date.
    public static Result distribute(Path module, Path out, Writer log) throws IOException, InterruptedException {
        return distribute(module, out, log, ProcessRunner.system());
    }

    /// Cross-builds through `processes`. The runner receives one compiler
    /// command for each stale platform.
    public static Result distribute(Path module, Path out, Writer log, ProcessRunner processes)
            throws IOException, InterruptedException {
        var sources = sources(module);
        if (sources.isEmpty()) throw new IOException("no C to build in " + module);
        if (!Native.compiler().equals("zig")) {
            throw new IOException("cross-building needs zig; the system compiler builds for this machine only");
        }

        var name = module.getFileName().toString();
        var built = new ArrayList<String>();
        var current = new ArrayList<String>();
        var problems = new ArrayList<String>();
        for (var platform : Platform.SHIPPED) {
            var library = out.resolve(platform.directory()).resolve(platform.library(name));
            if (!Native.stale(library, sources)) {
                current.add(platform.directory());
                continue;
            }
            log.write("cross-building " + name + " for " + platform.directory() + "\n");
            log.flush();
            var failure = compile(module, sources, platform, library, processes);
            if (failure.isEmpty()) built.add(platform.directory());
            else problems.add(failure);
        }
        return new Result(List.copyOf(built), List.copyOf(current), List.copyOf(problems));
    }

    /// Whether every platform already has a library, which is what [Install]
    /// asks before deciding it has to build anything.
    public static boolean complete(Path module, Path out) throws IOException {
        var sources = sources(module);
        if (sources.isEmpty()) return false;
        var name = module.getFileName().toString();
        for (var platform : Platform.SHIPPED) {
            if (Native.stale(out.resolve(platform.directory()).resolve(platform.library(name)), sources)) return false;
        }
        return true;
    }

    private static String compile(Path module, List<Path> sources, Platform platform, Path library,
            ProcessRunner processes)
            throws IOException, InterruptedException {
        Files.createDirectories(library.getParent());
        var command = new ArrayList<>(List.of(
                "zig", "cc", "-target", platform.target(), "-shared", "-fPIC", "-O2", "-s",
                "-o", library.toString()));
        sources.forEach(source -> command.add(source.toString()));
        command.addAll(Native.flags(module));

        var output = new StringWriter();
        var status = Launch.run(command, module, output, java.util.Map.of(), processes);
        if (status == 0) {
            sweep(library);
            return "";
        }
        Files.deleteIfExists(library);
        return platform.directory() + ": "
                + (output.toString().isBlank() ? "compiler exited with " + status : output.toString().strip());
    }

    /// Linking for Windows leaves an import library beside the real one, which
    /// nothing here loads and everything here would ship. Only the library
    /// itself is the output.
    private static void sweep(Path library) throws IOException {
        try (var beside = Files.list(library.getParent())) {
            for (var path : beside.filter(path -> !path.equals(library)).toList()) Files.delete(path);
        }
    }

    private static List<Path> sources(Path module) throws IOException {
        if (!Files.isDirectory(module)) return List.of();
        try (var tree = Files.walk(module)) {
            return tree.filter(path -> path.toString().endsWith(".c")).sorted().toList();
        }
    }
}
