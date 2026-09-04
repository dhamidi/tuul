package web.reload;

import harness.Check;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import reload.Generation;
import reload.MemoryRevisionSource;
import reload.Revision;
import web.Headers;
import web.Request;
import web.serve.Memory;
import web.serve.Recorded;
import web.uploads.Limits;

/// Filesystem integration checks for multipart revision submission.
/// No socket or watcher is used.
public final class HttpRevisionSourceTest {

    private HttpRevisionSourceTest() {}

    public static void run() throws Exception {
        queuesInMemoryUntilStarted();
        mapsAStagedRevisionForHostCompilation();
        includesDependenciesInIdentity();
        submitsACompleteRevision();
        refusesUnauthorizedAndHostileEntries();
        removesAPartialRevision();
    }

    private static void mapsAStagedRevisionForHostCompilation() throws Exception {
        var source = new MemoryRevisionSource();
        var mapped = source.map(revision -> Revision.of(revision.identity(), Generation::empty));
        var seen = new ArrayList<Revision>();
        mapped.start(seen::add);
        source.submit(Revision.of("source", Generation::empty));
        Check.equal("the host can transform a source revision before reload", "source", seen.getFirst().identity());
        Check.that("the transform returns the host's compiled program", seen.getFirst().program() != null);
        mapped.close();
    }

    private static void includesDependenciesInIdentity() throws Exception {
        var root = Files.createTempDirectory("tuul-revision-digest");
        var source = root.resolve("main.java");
        var dependency = root.resolve("library.jar");
        Files.writeString(source, "class main {}\n");
        Files.writeString(dependency, "one\n");
        var first = Revision.from(root, "main", List.of(source), List.of(), List.of(dependency));
        Files.writeString(dependency, "two\n");
        var second = Revision.from(root, "main", List.of(source), List.of(), List.of(dependency));
        Check.that("dependency bytes contribute to the revision identity",
                !first.identity().equals(second.identity()));
        remove(root);
    }

    private static void queuesInMemoryUntilStarted() {
        var source = new MemoryRevisionSource();
        var seen = new ArrayList<Revision>();
        var one = Revision.of("one", Generation::empty);
        source.submit(one);
        source.start(seen::add);
        Check.equal("the in-memory source retains a revision before start", List.of(one), seen);
        var two = Revision.of("two", Generation::empty);
        source.submit(two);
        Check.equal("started submissions are delivered immediately", List.of(one, two), seen);
        source.close();
    }

    private static void submitsACompleteRevision() throws Exception {
        var staging = Files.createTempDirectory("tuul-revisions");
        var source = new HttpRevisionSource(staging, Limits.DEFAULT, request -> true);
        var received = new ArrayList<Revision>();
        source.start(received::add);
        var body = multipart("{\"entrypoint\":\"web\",\"sources\":[\"src/web/main.java\"],\"resources\":[]}",
                List.of(new File("src/web/main.java", "class main {}")));
        var answer = Memory.handle(source.handler(), request(body));
        Check.equal("a revision upload is accepted", 200, answer.status());
        Check.equal("the answer contains the submitted revision identity", 64,
                jsonString(answer.text(), "revision").length());
        Check.equal("one revision reaches the source callback", 1, received.size());
        Check.equal("the selected entrypoint is preserved", "web", received.getFirst().entrypoint());
        Check.equal("the uploaded source is staged", "class main {}",
                Files.readString(received.getFirst().sources().getFirst()));
        source.close();
        remove(staging);
    }

    private static void refusesUnauthorizedAndHostileEntries() throws Exception {
        var staging = Files.createTempDirectory("tuul-revisions-denied");
        var source = new HttpRevisionSource(staging, Limits.DEFAULT, request -> false);
        source.start(revision -> { throw new AssertionError("denied upload reached callback"); });
        var denied = Memory.handle(source.handler(), request(multipart("{}", List.of())));
        Check.equal("authorization is required by the injected policy", 403, denied.status());

        var allowed = new HttpRevisionSource(staging, Limits.DEFAULT, request -> true);
        allowed.start(revision -> { throw new AssertionError("hostile upload reached callback"); });
        var hostileBody = multipart(
                "{\"entrypoint\":\"web\",\"sources\":[\"src/web/main.java\"],\"resources\":[]}",
                List.of(new File("../main.java", "bad")));
        var hostile = Memory.handle(allowed.handler(), request(hostileBody));
        Check.equal("parent entries are refused", 400, hostile.status());
        Check.that("a refused request leaves no staged directory", empty(staging));
        source.close();
        allowed.close();
        remove(staging);
    }

    private static void removesAPartialRevision() throws Exception {
        var staging = Files.createTempDirectory("tuul-revisions-partial");
        var source = new HttpRevisionSource(staging, Limits.DEFAULT.part(8), request -> true);
        source.start(revision -> { throw new AssertionError("oversize upload reached callback"); });
        var largeBody = multipart(
                "{\"entrypoint\":\"web\",\"sources\":[\"main.java\"],\"resources\":[]}",
                List.of(new File("main.java", "0123456789")));
        var large = Memory.handle(source.handler(), request(largeBody));
        Check.equal("a bounded upload is refused", 413, large.status());
        Check.that("a failed upload removes partial files", empty(staging));
        source.close();
        remove(staging);
    }

    private static Request request(String body) {
        return new Request("POST", "/revisions", web.Parameters.NONE,
                Headers.of("Content-Type", "multipart/form-data; boundary=tuul"),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), "", java.util.Map.of());
    }

    private static String multipart(String manifest, List<File> files) {
        var body = new StringBuilder();
        body.append("--tuul\r\nContent-Disposition: form-data; name=\"manifest\"\r\n\r\n")
                .append(manifest).append("\r\n");
        for (var file : files) body.append("--tuul\r\nContent-Disposition: form-data; name=\"file\"; filename=\"")
                .append(file.name()).append("\"\r\nContent-Type: text/plain\r\n\r\n")
                .append(file.content()).append("\r\n");
        return body.append("--tuul--\r\n").toString();
    }

    private static String jsonString(String text, String key) {
        var marker = "\"" + key + "\":\"";
        var start = text.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        var end = text.indexOf('"', start);
        return end < 0 ? "" : text.substring(start, end);
    }

    private static boolean empty(Path root) throws Exception {
        try (var files = Files.list(root)) { return files.findAny().isEmpty(); }
    }

    private static void remove(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }

    private record File(String name, String content) {}
}
