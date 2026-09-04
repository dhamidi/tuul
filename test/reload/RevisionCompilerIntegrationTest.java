package reload;

import harness.Check;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import web.Headers;
import web.Request;
import web.serve.Memory;
import web.reload.HttpRevisionSource;
import web.reload.ReloadHandler;
import web.uploads.Limits;

/// Exercises the source-neutral compiler with a real multipart revision.
public final class RevisionCompilerIntegrationTest {

    private RevisionCompilerIntegrationTest() {}

    public static void run() throws Exception {
        inMemoryGenerationSupportsResourcesAndModuleMetadata();
        compilerErrorsRemainStructured();
        var staging = Files.createTempDirectory("tuul-revision-upload");
        var reload = new Reload();
        var source = new HttpRevisionSource(staging, Limits.DEFAULT, request -> true);
        var compiler = new RevisionCompiler(List.of(Path.of("build/classes").toAbsolutePath()));
        try {
            source.map(compiler::compile).start(reload::submit);
            var handler = new ReloadHandler(reload);
            upload(source, "one", "one");
            Check.equal("the uploaded source is compiled and activated", "one",
                    Memory.handle(handler, Memory.get("/")).text());
            Check.equal("the revision identity is preserved through compilation", 64,
                    reload.status().activeRevision().length());

            upload(source, "two", "two");
            Check.equal("a second upload replaces the running handler", "two",
                    Memory.handle(handler, Memory.get("/")).text());

            upload(source, "broken", "this is not java");
            Check.equal("a broken upload leaves the last-good handler active", "two",
                    Memory.handle(handler, Memory.get("/")).text());
            Check.equal("the broken upload is rejected", 1L, reload.status().rejected());
            Check.equal("javac diagnostics are compile problems", "compile",
                    reload.status().problems().getFirst().phase());
            try (var paths = Files.walk(staging)) {
                Check.equal("reload does not materialize class output", 0L,
                        paths.filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().endsWith(".class")).count());
            }
        } finally {
            reload.close();
            source.close();
            remove(staging);
        }
    }

    private static void inMemoryGenerationSupportsResourcesAndModuleMetadata() throws Exception {
        var root = Files.createTempDirectory("tuul-revision-memory");
        try {
            var sources = root.resolve("src");
            Files.createDirectories(sources);
            var module = sources.resolve("module-info.java");
            var main = sources.resolve("main.java");
            var resource = root.resolve("config.txt");
            Files.writeString(module, "module sample { }\n");
            Files.writeString(main, "public final class main implements reload.Program {\n"
                    + "  public reload.Generation define() throws Exception {\n"
                    + "    var bytes = main.class.getResourceAsStream(\"/config.txt\").readAllBytes();\n"
                    + "    var value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);\n"
                    + "    if (!value.equals(\"in-memory\")) throw new IllegalStateException(value);\n"
                    + "    return reload.Generation.empty();\n"
                    + "  }\n"
                    + "}\n");
            Files.writeString(resource, "in-memory");
            var revision = Revision.from(root, "main", List.of(module, main), List.of(resource), List.of());
            var compiler = new RevisionCompiler(List.of(Path.of("build/classes").toAbsolutePath()));
            try (var generation = compiler.compile(revision).program().define()) {
                Check.that("in-memory resources are available to the generation", generation != null);
            }
            try (var paths = Files.walk(root)) {
                Check.equal("in-memory compilation writes no class files", 0L,
                        paths.filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().endsWith(".class")).count());
            }
        } finally {
            remove(root);
        }
    }

    private static void compilerErrorsRemainStructured() throws Exception {
        var root = Files.createTempDirectory("tuul-revision-errors");
        try {
            var source = root.resolve("main.java");
            Files.writeString(source, "public final class main {\n");
            var revision = Revision.from(root, "main", List.of(source), List.of(), List.of());
            var compiler = new RevisionCompiler(List.of(Path.of("build/classes").toAbsolutePath()));
            try {
                compiler.compile(revision).program().define();
                Check.that("compiler errors reject the candidate", false);
            } catch (RevisionCompiler.CompilationFailure failure) {
                Check.that("compiler errors retain javac problems", !failure.problems().isEmpty());
                Check.that("compiler errors retain a diagnostic message", !failure.getMessage().isBlank());
            }
        } finally {
            remove(root);
        }
    }

    private static void upload(HttpRevisionSource source, String label, String response) {
        var sourceText = label.equals("broken") ? "this is not valid Java\n"
                : "public final class main implements reload.Program {\n"
                + "  public reload.Generation define() {\n"
                + "    return web.reload.ReloadHandler.attach(reload.Generation.empty(), (request, response) -> web.Responses.text(\""
                + response + "\", response));\n"
                + "  }\n"
                + "}\n";
        var manifest = "{\"entrypoint\":\"web\",\"sources\":[\"src/web/main.java\"],\"resources\":[]}";
        var body = "--tuul\r\nContent-Disposition: form-data; name=\"manifest\"\r\n\r\n"
                + manifest + "\r\n--tuul\r\nContent-Disposition: form-data; name=\"file\"; filename=\"src/web/main.java\"\r\n"
                + "Content-Type: text/plain\r\n\r\n" + sourceText + "\r\n--tuul--\r\n";
        var request = new Request("POST", "/revisions", web.Parameters.NONE,
                Headers.of("Content-Type", "multipart/form-data; boundary=tuul"),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), "", java.util.Map.of());
        var answer = Memory.handle(source.handler(), request);
        Check.equal("the multipart upload is accepted", 200, answer.status());
        Check.that(label + " upload reports a revision", answer.text().contains("revision"));
    }

    private static void remove(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }
}
