package project;

import com.sun.net.httpserver.HttpServer;
import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import symbols.Vendor;

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
        var retryPomRequests = new AtomicInteger();
        var retryArtifactRequests = new AtomicInteger();
        var missingSourcesRequests = new AtomicInteger();
        var retryJar = jar("retry");
        server.createContext("/com/acme/retry/1.0/retry-1.0.pom", exchange -> {
            if (retryPomRequests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                status(exchange, 429);
            }
            else serve(exchange, pom("com.acme", "retry", "1.0"));
        });
        server.createContext("/com/acme/retry/1.0/retry-1.0.jar", exchange -> {
            if (retryArtifactRequests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(200, retryJar.length + 20L);
                try (var output = exchange.getResponseBody()) {
                    output.write(retryJar, 0, retryJar.length / 2);
                }
            } else serve(exchange, retryJar);
        });
        server.createContext("/com/acme/retry/1.0/retry-1.0.jar.sha256",
                exchange -> serve(exchange, sha256(retryJar)));
        server.createContext("/com/acme/retry/1.0/retry-1.0-sources.jar", exchange -> {
            missingSourcesRequests.incrementAndGet();
            status(exchange, 404);
        });
        server.createContext("/com/acme/bad/1.0/bad-1.0.pom",
                exchange -> serve(exchange, pom("com.acme", "bad", "1.0")));
        server.createContext("/com/acme/bad/1.0/bad-1.0.jar", exchange -> serve(exchange, jar("bad")));
        server.createContext("/com/acme/bad/1.0/bad-1.0.jar.sha256",
                exchange -> serve(exchange, "0000000000000000000000000000000000000000000000000000000000000000"));
        for (var artifact : List.of("duplicate-one", "duplicate-two")) {
            server.createContext("/com/acme/" + artifact + "/1.0/" + artifact + "-1.0.pom",
                    exchange -> serve(exchange, pom("com.acme", artifact, "1.0")));
            server.createContext("/com/acme/" + artifact + "/1.0/" + artifact + "-1.0.jar",
                    exchange -> serve(exchange, jar(Map.of("same/Type.class", new byte[] {0, 1, 2}))));
        }
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
            var vendor = Vendor.of(List.of(root.resolve("vendor")));
            Check.that("runtime binaries exclude source and javadoc archives",
                    vendor.runtime().size() == 2 && vendor.runtime().stream().noneMatch(path ->
                            path.toString().endsWith("-sources.jar") || path.toString().endsWith("-javadoc.jar")));
            Check.that("documentation archives have separate selected views",
                    vendor.sources().size() == 2 && vendor.javadocs().size() == 2);
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

            var excluded = client.into(new Layout(root), List.of("com.acme:one:1.0"), List.of(repository),
                    new StringWriter(), Add.Mode.EVENTS,
                    new Add.Options(java.util.Set.of("com.acme:two"), java.util.Set.of(), false, false));
            var excludedResolution = Files.readString(root.resolve("vendor/.tuul/resolution.json"));
            Check.that("an explicit graph exclusion is persistent",
                    excluded.ok() && excludedResolution.contains("\"exclusions\":[\"com.acme:two\"]")
                            && Vendor.of(List.of(root.resolve("vendor"))).runtime().size() == 1);

            var retried = client.into(new Layout(root), List.of("com.acme:retry:1.0"),
                    List.of(repository), new StringWriter(), Add.Mode.EVENTS);
            Check.equal("retryable POM and body failures recover", List.of(), retried.failed());
            Check.equal("the POM retry count is bounded", 2, retryPomRequests.get());
            Check.equal("the response-body retry count is bounded", 2, retryArtifactRequests.get());
            Check.equal("a definitive supplement 404 is not retried", 1, missingSourcesRequests.get());
            var retryResolution = Files.readString(root.resolve("vendor/.tuul/resolution.json"));
            Check.that("checksums and optional misses enter the resolution record",
                    retryResolution.contains("\"checksumStatus\":\"verified\"")
                            && retryResolution.contains("com.acme:retry:1.0:sources"));

            var beforeFailure = retryResolution;
            var failed = client.into(new Layout(root), List.of("com.acme:bad:1.0"),
                    List.of(repository), new StringWriter(), Add.Mode.EVENTS);
            Check.that("a checksum mismatch fails the required binary", !failed.ok());
            Check.equal("a failed staged add keeps the active resolution", beforeFailure,
                    Files.readString(root.resolve("vendor/.tuul/resolution.json")));

            String duplicateFailure;
            try {
                client.into(new Layout(root), List.of("com.acme:duplicate-one:1.0", "com.acme:duplicate-two:1.0"),
                        List.of(repository), new StringWriter(), Add.Mode.EVENTS);
                duplicateFailure = "";
            } catch (IOException expected) {
                duplicateFailure = expected.getMessage();
            }
            Check.that("duplicate classes report every owner and do not publish",
                    duplicateFailure.contains("same.Type") && duplicateFailure.contains("duplicate-one")
                            && duplicateFailure.contains("duplicate-two")
                            && Files.readString(root.resolve("vendor/.tuul/resolution.json")).equals(beforeFailure));
            var accepted = client.into(new Layout(root),
                    List.of("com.acme:duplicate-one:1.0", "com.acme:duplicate-two:1.0"), List.of(repository),
                    new StringWriter(), Add.Mode.EVENTS,
                    new Add.Options(java.util.Set.of(), java.util.Set.of("same.Type"), false, false));
            Check.that("an explicit duplicate exception is persistent",
                    accepted.ok() && Files.readString(root.resolve("vendor/.tuul/resolution.json"))
                            .contains("\"duplicateExceptions\":[\"same.Type\"]"));
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
                    serve(exchange, jar(body));
                });
    }

    private static void supplement(HttpServer server, AtomicInteger requests,
            String group, String artifact, String version, String kind) {
        server.createContext(path(group, artifact, version, artifact + "-" + version + "-" + kind + ".jar"),
                exchange -> {
                    requests.incrementAndGet();
                    serve(exchange, jar(kind));
                });
    }

    private static String path(String group, String artifact, String version, String file) {
        return "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + file;
    }

    private static void serve(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        serve(exchange, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void serve(com.sun.net.httpserver.HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Length", String.valueOf(bytes.length));
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void status(com.sun.net.httpserver.HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static String pom(String group, String artifact, String version) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>"
                + artifact + "</artifactId><version>" + version + "</version></project>";
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] jar(String body) throws IOException {
        return jar(Map.of("fixture.txt", body.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] jar(Map<String, byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
