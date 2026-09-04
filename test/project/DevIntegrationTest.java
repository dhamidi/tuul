package project;

import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/// Exercises the project compiler, child loader, watcher, and fixed-port HTTP
/// boundary together. It is intentionally an integration suite: javac,
/// filesystem events, and a real socket are the behavior under test.
public final class DevIntegrationTest {

    private DevIntegrationTest() {}

    public static void run() throws Exception {
        var root = Files.createTempDirectory("tuul-dev");
        try {
            project(root);
            var output = new StringWriter();
            var errors = new StringWriter();
            var thread = Thread.ofVirtual().start(() -> {
                try {
                    Dev.run(new Layout(root), "", 0, output, errors);
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
            try (var client = HttpClient.newHttpClient()) {
                var port = awaitPort(output, errors);
                Check.equal("the first generation answers", "one\n", get(client, port));
                assertNoReloadOutput(root, "before the first reload");
                Files.writeString(root.resolve("entrypoints/web/Main.java"), source("two"));
                await("the changed generation answers", () -> "two\n".equals(get(client, port)));
                assertNoReloadOutput(root, "after the first reload");

                Files.writeString(root.resolve("entrypoints/web/Main.java"), broken());
                await("the broken revision is reported", () -> errors.toString().contains("compile:"));
                Check.equal("a broken revision leaves the last good generation active", "two\n", get(client, port));
                Check.that("the compiler problem is reported", errors.toString().contains("compile:"));
            } finally {
                thread.interrupt();
                thread.join();
            }
        } finally {
            remove(root);
        }
        namedProject();
        externalProject();
        singleModuleExternalProject();
    }

    private static void namedProject() throws Exception {
        var root = Files.createTempDirectory("tuul-dev-module");
        try {
            Files.createDirectories(root.resolve("src/demo"));
            Files.createDirectories(root.resolve("entrypoints/web"));
            Files.createDirectories(root.resolve("src/resources"));
            Files.createDirectories(root.resolve("vendor"));
            Files.writeString(root.resolve("src/module-info.java"), """
                    module demo.app {
                        requires tuul;
                        exports demo;
                    }
                    """);
            Files.writeString(root.resolve("entrypoints/module-info.java"), """
                    module demo.entrypoints {
                        requires demo.app;
                        requires tuul;
                        provides reload.Program with demo.web.Main;
                    }
                    """);
            Files.writeString(root.resolve("src/demo/Value.java"), """
                    package demo;
                    public final class Value {
                        private Value() {}
                        public static String text() {
                            try (var input = Value.class.getResourceAsStream("/value.txt")) {
                                return new String(input.readAllBytes());
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        }
                    }
                    """);
            Files.writeString(root.resolve("src/resources/value.txt"), "from-module-resource");
            Files.writeString(root.resolve("entrypoints/web/Main.java"), """
                    package demo.web;
                    import reload.Generation;
                    import reload.Program;
                    import web.Responses;
                    import web.RouteRef;
                    import web.Router;
                    import web.reload.ReloadHandler;
                    import demo.Value;

                    public final class Main implements Program {
                        private static final RouteRef HOME = RouteRef.of("home", "/");
                        public Generation define() {
                            return ReloadHandler.attach(Generation.empty(), Router.of().get(HOME,
                                    (request, response) -> Responses.text(Value.text() + "\\n", response)));
                        }
                    }
                    """);
            copyTuul(root);
            var output = new StringWriter();
            var errors = new StringWriter();
            var thread = Thread.ofVirtual().start(() -> {
                try {
                    Dev.run(new Layout(root), "web", 0, output, errors);
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
            try (var client = HttpClient.newHttpClient()) {
                var port = awaitPort(output, errors);
                Check.equal("a named project loads its module generation", "from-module-resource\n", get(client, port));
                assertNoReloadOutput(root, "before the named reload");
                Files.writeString(root.resolve("src/resources/value.txt"), "changed-module-resource");
                await("the named generation reloads with its module resource", () ->
                        "changed-module-resource\n".equals(get(client, port)));
                assertNoReloadOutput(root, "after the named reload");
            } finally {
                thread.interrupt();
                thread.join();
            }
        } finally {
            remove(root);
        }
    }

    private static void project(Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("entrypoints/web"));
        Files.createDirectories(root.resolve("entrypoints/cli"));
        Files.createDirectories(root.resolve("vendor"));
        Files.writeString(root.resolve("src/module-info.java"), "module demo.app { requires tuul; }\n");
        Files.writeString(root.resolve("entrypoints/module-info.java"), """
                module demo.entrypoints {
                    requires demo.app;
                    requires tuul;
                    provides reload.Program with demo.web.Main;
                }
                """);
        Files.writeString(root.resolve("entrypoints/web/Main.java"), source("one"));
        Files.writeString(root.resolve("entrypoints/cli/Main.java"), "package cli; public final class Main { public static void main(String[] args) {} }\n");
        copyTuul(root);
    }

    private static void externalProject() throws Exception {
        var root = Files.createTempDirectory("tuul-dev-external");
        try {
            Files.createDirectories(root.resolve("src").resolve("external"));
            Files.createDirectories(root.resolve("entrypoints/http"));
            Files.createDirectories(root.resolve("vendor"));
            Files.writeString(root.resolve("src/module-info.java"), "module demo.external.app { }");
            Files.writeString(root.resolve("entrypoints/module-info.java"), """
                    module demo.external.entrypoints {
                        requires demo.external.app;
                        requires jdk.httpserver;
                        provides com.sun.net.httpserver.HttpHandler with external.Handler;
                    }
                    """);
            Files.writeString(root.resolve("entrypoints/http/Handler.java"), externalHandler("external-one"));
            var output = new StringWriter();
            var errors = new StringWriter();
            var thread = Thread.ofVirtual().start(() -> {
                try {
                    Dev.run(new Layout(root), "http", 0, output, errors);
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
            try (var client = HttpClient.newHttpClient()) {
                var port = awaitPort(output, errors);
                Check.equal("an external JDK-only module answers", "external-one\n", get(client, port));
                assertNoReloadOutput(root, "before the external reload");
                Files.writeString(root.resolve("entrypoints/http/Handler.java"), externalHandler("external-two"));
                await("the external handler reloads", () -> "external-two\n".equals(get(client, port)));
                assertNoReloadOutput(root, "after the external reload");
                Check.that("the external module does not import tuul", !Files.readString(
                        root.resolve("entrypoints/http/Handler.java")).contains("tuul."));
            } finally {
                thread.interrupt();
                thread.join();
            }
        } finally {
            remove(root);
        }
    }

    private static String externalHandler(String answer) {
        return """
                package external;

                import com.sun.net.httpserver.HttpExchange;
                import com.sun.net.httpserver.HttpHandler;
                import java.nio.charset.StandardCharsets;

                public final class Handler implements HttpHandler {
                    public void handle(HttpExchange exchange) throws java.io.IOException {
                        var body = "%s\\n".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                    }
                }
                """.formatted(answer);
    }

    private static void singleModuleExternalProject() throws Exception {
        var root = Files.createTempDirectory("tuul-dev-single-external");
        try {
            Files.createDirectories(root.resolve("src/external"));
            Files.createDirectories(root.resolve("vendor"));
            Files.writeString(root.resolve("src/module-info.java"), """
                    module demo.external {
                        requires jdk.httpserver;
                        provides com.sun.net.httpserver.HttpHandler with external.Handler;
                    }
                    """);
            Files.writeString(root.resolve("src/external/Handler.java"), externalHandler("single-one"));
            var output = new StringWriter();
            var errors = new StringWriter();
            var thread = Thread.ofVirtual().start(() -> {
                try {
                    Dev.run(new Layout(root), "", 0, output, errors);
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
            try (var client = HttpClient.newHttpClient()) {
                var port = awaitPort(output, errors);
                Check.equal("a single named JDK module answers", "single-one\n", get(client, port));
                Files.writeString(root.resolve("src/external/Handler.java"), externalHandler("single-two"));
                await("the single named module reloads", () -> "single-two\n".equals(get(client, port)));
                assertNoReloadOutput(root, "after the single-module reload");
            } finally {
                thread.interrupt();
                thread.join();
            }
        } finally {
            remove(root);
        }
    }

    private static void copyTuul(Path root) throws IOException {
        var home = Home.find();
        var jar = root.resolve("vendor/tuul.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var files = Files.walk(home.classes())) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    var name = home.classes().relativize(file).toString().replace('\\', '/');
                    output.putNextEntry(new JarEntry(name));
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }
        }
    }

    private static String source(String answer) {
        return """
                package demo.web;

                import reload.Generation;
                import reload.Program;
                import web.Responses;
                import web.RouteRef;
                import web.Router;
                import web.reload.ReloadHandler;

                public final class Main implements Program {
                    private static final RouteRef HOME = RouteRef.of("home", "/");
                    public Generation define() {
                        return ReloadHandler.attach(Generation.empty(), Router.of().get(HOME,
                                (request, response) -> Responses.text("%s\\n", response)));
                    }
                }
                """.formatted(answer);
    }

    private static String broken() {
        return source("broken").replace("public Generation define()", "public Generation define(");
    }

    private static int awaitPort(StringWriter output, StringWriter errors) throws Exception {
        await("the development server starts", () -> output.toString().contains("development server at"));
        var line = output.toString().lines().filter(value -> value.startsWith("development server at")).findFirst()
                .orElseThrow(() -> new IllegalStateException("dev did not start:\n" + errors));
        return Integer.parseInt(line.substring(line.lastIndexOf(':') + 1).trim());
    }

    private static String get(HttpClient client, int port) throws IOException, InterruptedException {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static void await(String what, Checkable condition) throws Exception {
        var deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.holds()) return;
            Thread.sleep(50);
        }
        Check.that(what, false);
    }

    private static void assertNoReloadOutput(Path root, String phase) throws IOException {
        Check.that(phase + " writes no class files", classFiles(root) == 0);
        Check.that(phase + " creates no reload directory", !Files.exists(root.resolve("build/reload")));
    }

    private static long classFiles(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .count();
        }
    }

    @FunctionalInterface
    private interface Checkable {
        boolean holds() throws Exception;
    }

    private static void remove(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
