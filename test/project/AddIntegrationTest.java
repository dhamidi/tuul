package project;

import com.sun.net.httpserver.HttpServer;
import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class AddIntegrationTest {
    private AddIntegrationTest() {}

    public static void run() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var artifactRequests = new AtomicInteger();
        pom(server, "/com/acme/one/1.0/one-1.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>one</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>two</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        pom(server, "/com/acme/two/2.0/two-2.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>two</artifactId>
                  <version>2.0</version>
                </project>
                """);
        artifact(server, artifactRequests, "com.acme", "one", "1.0", "one");
        artifact(server, artifactRequests, "com.acme", "two", "2.0", "two");
        supplement(server, artifactRequests, "com.acme", "one", "1.0", "sources");
        supplement(server, artifactRequests, "com.acme", "one", "1.0", "javadoc");
        supplement(server, artifactRequests, "com.acme", "two", "2.0", "sources");
        supplement(server, artifactRequests, "com.acme", "two", "2.0", "javadoc");
        server.start();
        var root = Files.createTempDirectory("tuul-add");
        try (var client = Add.client()) {
            var output = new StringWriter();
            var repository = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            var result = client.into(new Layout(root), List.of("com.acme:one:1.0"),
                    List.of(repository), output, Add.Mode.EVENTS);
            Check.that("transitive artifacts and supplements download",
                    result.ok() && result.downloaded().size() == 6);
            Check.that("all resolved files land in vendor", List.of(
                    "com.acme/one/1.0/one-1.0.jar", "com.acme/one/1.0/one-1.0-sources.jar",
                    "com.acme/one/1.0/one-1.0-javadoc.jar", "com.acme/two/2.0/two-2.0.jar",
                    "com.acme/two/2.0/two-2.0-sources.jar", "com.acme/two/2.0/two-2.0-javadoc.jar").stream()
                    .map(file -> Files.isRegularFile(root.resolve("vendor").resolve(file)))
                    .allMatch(Boolean.TRUE::equals));
            Check.that("a successful add publishes the selected graph",
                    Files.readString(root.resolve("vendor/.tuul/resolution.json")).contains("\"coordinate\":\"com.acme:two:2.0\""));
            Check.that("plain output is an event stream", output.toString().contains("add.resolve com.acme:one:1.0")
                    && output.toString().contains("add.resolved com.acme:one:1.0 2 artifacts")
                    && output.toString().contains("add.done com.acme:two:2.0:sources")
                    && output.toString().contains("add.complete 6 downloaded, 0 cached, 0 failed"));

            var cachedOutput = new StringWriter();
            var cached = client.into(new Layout(root), List.of("com.acme:one:1.0"),
                    List.of(repository), cachedOutput, Add.Mode.EVENTS);
            Check.that("all six files are cache hits", cached.ok() && cached.cached().size() == 6);
            Check.equal("a cache hit does not make an artifact request", 6, artifactRequests.get());
            Check.that("cache output is still an event", cachedOutput.toString().contains(
                    "add.cached com.acme:one:1.0:sources"));
        } finally {
            server.stop(0);
            delete(root);
        }
    }

    private static void pom(HttpServer server, String path, String body) {
        server.createContext(path, exchange -> serve(exchange, body));
    }

    private static void artifact(HttpServer server, AtomicInteger requests,
            String group, String artifact, String version, String body) {
        server.createContext(path(group, artifact, version, artifact + "-" + version + ".jar"),
                exchange -> {
                    requests.incrementAndGet();
                    serve(exchange, body);
                });
    }

    private static void supplement(HttpServer server, AtomicInteger requests,
            String group, String artifact, String version, String kind) {
        server.createContext(path(group, artifact, version, artifact + "-" + version + "-" + kind + ".jar"),
                exchange -> {
                    requests.incrementAndGet();
                    serve(exchange, kind);
                });
    }

    private static String path(String group, String artifact, String version, String file) {
        return "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + file;
    }

    private static void serve(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Length", String.valueOf(bytes.length));
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
