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
import web.uploads.Limits;

/// Exercises the source-neutral compiler with a real multipart revision.
public final class RevisionCompilerIntegrationTest {

    private RevisionCompilerIntegrationTest() {}

    public static void run() throws Exception {
        var staging = Files.createTempDirectory("tuul-revision-upload");
        var output = Files.createTempDirectory("tuul-revision-output");
        var reload = new Reload();
        var source = new HttpRevisionSource(staging, Limits.DEFAULT, request -> true);
        var compiler = new RevisionCompiler(output, List.of(Path.of("build/classes").toAbsolutePath()));
        try {
            source.map(compiler::compile).start(reload::submit);
            upload(source, "one", "one");
            Check.equal("the uploaded source is compiled and activated", "one",
                    Memory.handle(reload.handler(), Memory.get("/")).text());
            Check.equal("the revision identity is preserved through compilation", 64,
                    reload.status().activeRevision().length());

            upload(source, "two", "two");
            Check.equal("a second upload replaces the running handler", "two",
                    Memory.handle(reload.handler(), Memory.get("/")).text());

            upload(source, "broken", "this is not java");
            Check.equal("a broken upload leaves the last-good handler active", "two",
                    Memory.handle(reload.handler(), Memory.get("/")).text());
            Check.equal("the broken upload is rejected", 1L, reload.status().rejected());
            Check.equal("javac diagnostics are compile problems", "compile",
                    reload.status().problems().getFirst().phase());
            Check.equal("rejected candidate output is removed", 1L, count(output));
        } finally {
            reload.close();
            source.close();
            remove(staging);
            remove(output);
        }
    }

    private static void upload(HttpRevisionSource source, String label, String response) {
        var sourceText = label.equals("broken") ? "this is not valid Java\n"
                : "public final class main implements reload.Program {\n"
                + "  public reload.Generation define() {\n"
                + "    return reload.Generation.of((request, response) -> web.Responses.text(\""
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

    private static long count(Path root) throws Exception {
        try (var paths = Files.list(root)) { return paths.count(); }
    }

    private static void remove(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }
}
