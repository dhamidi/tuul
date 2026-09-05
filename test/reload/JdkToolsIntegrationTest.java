package reload;

import harness.Check;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Compiles two named tool generations and checks root-only loading and retirement.
public final class JdkToolsIntegrationTest {
    private JdkToolsIntegrationTest() {}

    /// Compiles, loads, lists, runs, and closes two tool generations from files.
    public static void run() throws Exception {
        var project = Files.createTempDirectory("tuul-jdk-tools");
        var marker = project.resolve("closed.txt");
        var previous = System.getProperty("tuul.jdk.tools.marker");
        System.setProperty("tuul.jdk.tools.marker", marker.toString());
        try {
            var dependency = dependency(project);
            var first = root(project, "one", "first tool", 11);
            var second = root(project, "two", "second tool", 22);
            var firstRevision = Revision.from("sample.tools", List.of(dependency, first), List.of());
            var secondRevision = Revision.from("sample.tools", List.of(dependency, second), List.of());
            var compiler = new RevisionCompiler(List.of(),
                    candidate -> JdkServices.define(candidate, java.util.spi.ToolProvider.class));
            try (var reload = new Reload()) {
                var catalog = new JdkToolCatalog(reload);
                reload.submit(compiler.compile(firstRevision));
                Check.equal("the first generation lists only its root tool", List.of(
                        new JdkToolCatalog.Tool("sample-tool", "first tool")), catalog.list());
                var firstOut = new StringWriter();
                var firstCode = catalog.run("sample-tool", new PrintWriter(firstOut),
                        new PrintWriter(new StringWriter()), "input");
                Check.equal("the first tool returns its exit code", 11, firstCode);
                Check.equal("the first tool writes its generation output", "one:input", firstOut.toString());

                reload.submit(compiler.compile(secondRevision));
                Check.equal("the replacement lists the new root tool", List.of(
                        new JdkToolCatalog.Tool("sample-tool", "second tool")), catalog.list());
                var secondOut = new StringWriter();
                var secondCode = catalog.run("sample-tool", new PrintWriter(secondOut),
                        new PrintWriter(new StringWriter()), "input");
                Check.equal("the replacement tool returns its exit code", 22, secondCode);
                Check.equal("the replacement tool writes its generation output", "two:input", secondOut.toString());
                Check.equal("the retired closeable provider closes after replacement", "closed-one",
                        Files.readString(marker));
            }
            Check.equal("the active closeable provider closes with reload", "closed-two",
                    Files.readString(marker));
            try (var paths = Files.walk(project)) {
                Check.equal("compilation writes no class file under the project", 0L,
                        paths.filter(Files::isRegularFile)
                                .filter(path -> path.toString().endsWith(".class")).count());
            }
        } finally {
            if (previous == null) System.clearProperty("tuul.jdk.tools.marker");
            else System.setProperty("tuul.jdk.tools.marker", previous);
            try (var paths = Files.walk(project)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static Revision.SourceModule dependency(Path project) throws Exception {
        var root = Files.createDirectories(project.resolve("dependency"));
        var info = write(root, "module-info.java", "module sample.dep { provides java.util.spi.ToolProvider with dep.DependencyTool; }\n");
        var source = write(root, "dep/DependencyTool.java", "package dep;\n"
                + "public final class DependencyTool implements java.util.spi.ToolProvider {\n"
                + " public String name() { return \"dependency-tool\"; }\n"
                + " public int run(java.io.PrintWriter out, java.io.PrintWriter err, String... args) { return 99; }\n"
                + "}\n");
        return new Revision.SourceModule("sample.dep", root, info, List.of(info, source), List.of());
    }

    private static Revision.SourceModule root(Path project, String value, String description,
            int code) throws Exception {
        var root = Files.createDirectories(project.resolve("root-" + value));
        var info = write(root, "module-info.java", "module sample.tools { requires sample.dep;"
                + " provides java.util.spi.ToolProvider with tools.Tool; }\n");
        var source = write(root, "tools/Tool.java", "package tools;\n"
                + "public final class Tool implements java.util.spi.ToolProvider, java.lang.AutoCloseable {\n"
                + " public String name() { return \"sample-tool\"; }\n"
                + " public java.util.Optional<String> description() { return java.util.Optional.of(\""
                + description + "\"); }\n"
                + " public int run(java.io.PrintWriter out, java.io.PrintWriter err, String... args) {\n"
                + "  out.print(\"" + value + ":\" + args[0]); return " + code + "; }\n"
                + " public void close() throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of("
                + "java.lang.System.getProperty(\"tuul.jdk.tools.marker\")), \"closed-" + value + "\"); }\n"
                + "}\n");
        return new Revision.SourceModule("sample.tools", root, info, List.of(info, source), List.of());
    }

    private static Path write(Path root, String name, String text) throws Exception {
        var path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        return path;
    }
}
