package modules;

import harness.Check;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/// Checks that graph nodes contain only the reachable module closure.
public final class ModuleGraphIntegrationTest {

    private ModuleGraphIntegrationTest() {}

    /// Compiles a root and an unrelated named module, then resolves the root.
    public static void run() throws Exception {
        var root = Files.createTempDirectory("tuul-module-graph");
        try {
            var a = compile(root, "a", "module a { }\n", "package a; public class A {}\n");
            var unrelated = compile(root, "unrelated", "module unrelated { }\n",
                    "package unrelated; public class U {}\n");
            var graph = ModuleGraph.resolve(List.of(a, unrelated), List.of("a"));
            Check.that("the root is resolved", graph.modules().containsKey("a"));
            Check.that("java.base is the only required system closure here", graph.modules().containsKey("java.base"));
            Check.that("unrelated artifacts stay outside the resolved graph",
                    !graph.modules().containsKey("unrelated"));
            rejectsAutomaticModule(root);
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static void rejectsAutomaticModule(Path root) throws Exception {
        var jar = root.resolve("legacy.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "legacy");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {}
        try {
            ModuleGraph.resolve(List.of(jar), List.of("legacy"));
            throw new AssertionError("automatic module was accepted");
        } catch (ModuleGraph.Failure failure) {
            Check.equal("automatic module has an invalid-artifact diagnostic",
                    ModuleGraph.Code.INVALID_ARTIFACT, failure.diagnostics().getFirst().code());
            Check.equal("automatic module diagnostic is actionable",
                    "automatic modules are not supported; artifact must contain module-info.class",
                    failure.diagnostics().getFirst().detail());
        }
    }

    private static Path compile(Path root, String name, String descriptor, String source) throws Exception {
        var sourceRoot = Files.createDirectories(root.resolve(name + "-src"));
        Files.writeString(sourceRoot.resolve("module-info.java"), descriptor);
        var packageName = name.equals("unrelated") ? "unrelated" : name;
        var sourcePath = sourceRoot.resolve(packageName).resolve(name.equals("unrelated") ? "U.java" : "A.java");
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, source);
        var output = Files.createDirectories(root.resolve(name));
        var process = new ProcessBuilder("javac", "--release", "27", "-d", output.toString(),
                sourceRoot.resolve("module-info.java").toString(), sourcePath.toString()).inheritIO().start();
        if (process.waitFor() != 0) throw new IllegalStateException("javac failed for " + name);
        return output;
    }
}
