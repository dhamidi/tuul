package reload;

import harness.Check;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/// Exercises compilation of a real two-module source closure and fresh layer.
public final class RevisionCompilerModuleLayerTest {

    private RevisionCompilerModuleLayerTest() {}

    public static void main(String[] args) throws Exception {
        Check.suite("reload.RevisionCompilerModuleLayerTest", () -> run());
        if (Check.report() != 0) throw new AssertionError("test failed");
    }

    /// Compiles two named modules, loads the root from its layer, and checks
    /// that resources and classes remain outside the revision tree.
    public static void run() throws Exception {
        var root = Files.createTempDirectory("tuul-module-layer");
        try {
            var depRoot = Files.createDirectories(root.resolve("dep"));
            var appRoot = Files.createDirectories(root.resolve("app"));
            var depInfo = write(depRoot, "module-info.java", "module sample.dep { exports dep; opens dep; }\n");
            var depSource = write(depRoot, "dep/Value.java", "package dep; public record Value(String text) { public boolean resource() throws Exception { return new String(Value.class.getResourceAsStream(\"shared.txt\").readAllBytes()).equals(\"dependency\"); } }\n");
            var appInfo = write(appRoot, "module-info.java", "module sample.app { requires sample.dep; requires tuul; exports app; opens app; provides reload.Program with app.Main; }\n");
            var appSource = write(appRoot, "app/Main.java", "package app; public final class Main implements reload.Program {\n"
                    + " public reload.Generation define() throws Exception {\n"
                    + "  if (new String(Main.class.getResourceAsStream(\"/app/shared.txt\").readAllBytes()).isEmpty()) throw new IllegalStateException();\n"
                    + "  if (!new dep.Value(\"ok\").resource()) throw new IllegalStateException();\n"
                    + "  return reload.Generation.empty(); } }\n");
            var answer = write(appRoot, "app/shared.txt", "ok");
            var depAnswer = write(depRoot, "dep/shared.txt", "dependency");
            var dep = new Revision.SourceModule("sample.dep", depRoot, depInfo,
                    List.of(depInfo, depSource), List.of(new Revision.ResourceEntry("dep/shared.txt", depAnswer)));
            var app = new Revision.SourceModule("sample.app", appRoot, appInfo,
                    List.of(appInfo, appSource), List.of(new Revision.ResourceEntry("app/shared.txt", answer)));
            var host = Path.of(System.getProperty("tuul.test.module", "build/modules/tuul")).toAbsolutePath();
            var revision = Revision.from("sample.app", List.of(dep, app), List.of(host));
            var layer = new java.util.concurrent.atomic.AtomicReference<ModuleLayer>();
            var oldResource = new AtomicReference<URL>();
            var compiler = new RevisionCompiler(List.of(),
                    candidate -> {
                        layer.set(candidate);
                        var loader = candidate.findLoader("sample.app");
                        var currentResource = loader.getResource("app/shared.txt");
                        var priorResource = oldResource.getAndSet(currentResource);
                        if (priorResource != null) {
                            Check.equal("retired resource URL keeps its generation bytes", "ok",
                                    new String(priorResource.openStream().readAllBytes()));
                        }
                        var appType = loader.loadClass("app.Main");
                        var dependencyType = loader.loadClass("dep.Value");
                        var appModule = candidate.findModule("sample.app").orElseThrow();
                        Check.that("application package is open to the host",
                                appModule.isOpen("app", RevisionCompilerModuleLayerTest.class.getModule()));
                        try (var reader = candidate.configuration().findModule("sample.app").orElseThrow()
                                .reference().open(); var resource = reader.open("app/shared.txt").orElseThrow()) {
                            Check.that("module reader retains application resource",
                                    !new String(resource.readAllBytes()).isEmpty());
                        }
                        Check.equal("application class belongs to its named module", "sample.app",
                                appType.getModule().getName());
                        Check.equal("dependency class belongs to its named module", "sample.dep",
                                dependencyType.getModule().getName());
                        var appReference = candidate.configuration().findModule("sample.app").orElseThrow().reference();
                        try (var reader = appReference.open()) {
                            Check.that("application resource stays in its module",
                                    !new String(reader.open("app/shared.txt").orElseThrow().readAllBytes()).isEmpty());
                        }
                        var dependencyReference = candidate.configuration().findModule("sample.dep").orElseThrow().reference();
                        try (var reader = dependencyReference.open()) {
                            Check.equal("dependency resource stays in its module", "dependency",
                                    new String(reader.open("dep/shared.txt").orElseThrow().readAllBytes()));
                        }
                        return new ProgramGenerationFactory().define(candidate);
                    });
            try (var generation = compiler.compile(revision).program().define()) {
                Check.that("candidate module is named", layer.get().findModule("sample.app").orElseThrow().isNamed());
                Check.that("layer factory creates a generation", generation != null);
            }
            Files.writeString(answer, "changed");
            var next = Revision.from("sample.app", List.of(dep, app), List.of(host));
            try (var generation = compiler.compile(next).program().define()) {
                Check.that("replacement layer is fresh", layer.get().findModule("sample.app").orElseThrow().isNamed());
            }
            try (var paths = Files.walk(root)) {
                Check.equal("candidate classes never reach the revision tree", 0L,
                        paths.filter(Files::isRegularFile)
                                .filter(path -> path.toString().endsWith(".class")).count());
            }
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static Path write(Path root, String name, String text) throws Exception {
        var path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        return path;
    }
}
