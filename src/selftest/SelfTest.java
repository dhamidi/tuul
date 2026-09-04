package selftest;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import ffi.Platform;
import json.Json;
import project.Launch;
import tuul.Version;

/// tuul, tested the way it is used: a real project in a temporary directory,
/// driven by running the tuul command against it.
///
/// Nothing here reaches into tuul's own classes. Every step spawns the command
/// line as a user would, and asserts on its exit status and its output — so a
/// broken entrypoint, a bad exit code or an unreadable message fails the test
/// the same way it would fail a person.
///
/// A run that passes takes its directory with it. A run that fails leaves the
/// directory exactly as it was, because the state of a failed build is the
/// evidence.
public final class SelfTest {

    private SelfTest() {}

    public static Report run() throws IOException, InterruptedException {
        var root = Files.createTempDirectory("tuul-self-test");
        var checks = new ArrayList<Report.Check>();
        var project = root.resolve("demo");

        scaffolds(root, project, checks);
        addsAndUsesDependency(project, checks);
        installs(project, checks);
        builds(project, checks);
        hotReloads(project, checks);
        runs(project, checks);
        tests(project, checks);
        documents(project, checks);
        usesInstalledTuul(project, checks);
        recovers(project, checks);

        var ok = checks.stream().allMatch(Report.Check::ok);
        if (ok) delete(root);
        return new Report(root, !ok, List.copyOf(checks));
    }

    private static void scaffolds(Path root, Path project, List<Report.Check> checks) throws IOException, InterruptedException {
        var created = tuul(root, "new", "demo");
        check(checks, "tuul new exits cleanly", created.status() == 0, created.output());
        check(checks, "it prints the runnable dev next action",
                created.output().contains("cd ./demo && tuul install && tuul dev"), created.output());
        check(checks, "it writes a library", exists(project, "src/demo/Greeting.java"), listing(project));
        check(checks, "it writes an entrypoint", exists(project, "entrypoints/cli/Main.java"), listing(project));
        check(checks, "it writes a reloadable web entrypoint", exists(project, "entrypoints/web/Main.java"), listing(project));
        check(checks, "it writes a test", exists(project, "test/demo/test/Run.java"), listing(project));
        check(checks, "it writes the vendor directory", Files.isDirectory(project.resolve("vendor")), listing(project));
    }

    private static void addsAndUsesDependency(Path project, List<Report.Check> checks)
            throws IOException, InterruptedException {
        var dependencyTest = project.resolve("test/demo/tests/DependencyTest.java");
        var runner = project.resolve("test/demo/test/Run.java");
        var originalRunner = Files.readString(runner);
        Files.writeString(dependencyTest, DEPENDENCY_TEST);
        Files.writeString(runner, DEPENDENCY_RUNNER);
        var added = tuul(project, "add", ANNOTATIONS_COORDINATE);
        if (added.status() != 0 && repositoryUnavailable(added.output())) {
            Files.deleteIfExists(dependencyTest);
            Files.writeString(runner, originalRunner);
            check(checks, "Maven dependency unavailable; dependency checks skipped", true, added.output());
            return;
        }
        requires(project.resolve("test/module-info.java"), "org.jetbrains.annotations");
        check(checks, "tuul add exits cleanly", added.status() == 0, added.output());
        check(checks, "it vendors the dependency jar",
                exists(project, "vendor/org.jetbrains/annotations/26.0.2/annotations-26.0.2.jar"),
                listing(project.resolve("vendor")));
        check(checks, "it vendors the dependency sources",
                exists(project, "vendor/org.jetbrains/annotations/26.0.2/annotations-26.0.2-sources.jar"),
                listing(project.resolve("vendor")));
        check(checks, "it reports the dependency resolution",
                added.output().contains("add.resolved " + ANNOTATIONS_COORDINATE), added.output());
    }

    private static void builds(Path project, List<Report.Check> checks) throws IOException, InterruptedException {
        var built = tuul(project, "build");
        check(checks, "tuul build exits cleanly", built.status() == 0, built.output());
        check(checks, "it compiles the library", exists(project, "build/modules/demo.app/demo/Greeting.class"), built.output());
        check(checks, "it compiles the entrypoint apart from it", exists(project, "build/modules/demo.entrypoints/cli/Main.class"), built.output());
        check(checks, "it says what it did", built.output().contains("compiled"), built.output());
        check(checks, "it compiles the native module first",
                exists(project, "build/native/" + System.mapLibraryName("hello")),
                built.output());
    }

    private static void runs(Path project, List<Report.Check> checks) throws IOException, InterruptedException {
        var ran = tuul(project, "run", "--", "tuul", "world");
        check(checks, "tuul run exits cleanly", ran.status() == 0, ran.output());
        check(checks, "it passes the arguments through",
                ran.output().contains("Hello, tuul!") && ran.output().contains("Hello, world!"),
                ran.output());

        var bare = tuul(project, "run");
        check(checks, "and runs without arguments too", bare.output().contains("Hello, world!"), bare.output());
        check(checks, "every greeting came back out of C through a callback",
                ran.output().lines().count() == 2 && bare.output().lines().count() == 1,
                ran.output() + bare.output());
    }

    private static void tests(Path project, List<Report.Check> checks) throws IOException, InterruptedException {
        var tested = tuul(project, "test");
        check(checks, "tuul test runs the project's tests", tested.status() == 0, tested.output());
        check(checks, "the generated project uses the added dependency",
                tested.output().contains("dependency works"), tested.output());
        check(checks, "and shows what they said", tested.output().contains("all tests passed"), tested.output());
    }

    private static void documents(Path project, List<Report.Check> checks) throws IOException, InterruptedException {
        var docs = tuul(project, "docs", "demo.Greeting", "--json");
        check(checks, "tuul docs answers for the project's own code", docs.status() == 0, docs.output());

        var described = describe(docs.output());
        check(checks, "as JSON an agent can read", described.string("class", "").equals("demo.Greeting"), docs.output());
        check(checks, "with the doc comment attached",
                described.string("doc", "").startsWith("Greets whoever is named."),
                docs.output());
        check(checks, "and the block tags under it", tags(described).contains("@param name who to greet"), docs.output());

        var wrapper = tuul(project, "docs", "greet.Greeter", "--methods");
        check(checks, "including the code that binds to C",
                wrapper.output().contains("boolean greet(String name, Consumer<String> onGreeting)"),
                wrapper.output());

        var search = tuul(project, "docs", "--search", "greets whoever");
        check(checks, "it searches what it has indexed",
                search.output().contains("demo.Greeting"), search.output());

        var jdk = tuul(project, "docs", "java.lang.String", "--implements");
        check(checks, "it answers for the JDK too", jdk.output().contains("CharSequence"), jdk.output());

        var missing = tuul(project, "docs", "nope.Nope");
        check(checks, "an unknown symbol is a failure, not an empty answer", missing.status() == 1, missing.output());
    }

    /// The whole point of `tuul install`: a project that vendors tuul writes an
    /// application on tuul's own libraries, and SQLite works without anybody
    /// fetching a binary for their machine.
    private static void installs(Path project, List<Report.Check> checks) throws IOException, InterruptedException {
        var installed = tuul(project, "install");
        var directory = installedTuul(project);
        check(checks, "tuul install exits cleanly", installed.status() == 0, installed.output());
        check(checks, "it vendors a jar and its sources",
                Files.isRegularFile(directory.resolve(Version.artifact() + ".jar"))
                        && Files.isRegularFile(directory.resolve(Version.artifact() + "-sources.jar")),
                listing(project.resolve("vendor")));
        check(checks, "and a SQLite for this machine",
                Files.isRegularFile(directory.resolve("native").resolve(Platform.host().directory())
                        .resolve(Platform.host().library("sqlite3"))),
                listing(project.resolve("vendor")));
        check(checks, "and for the machines this project will be cloned onto",
                Platform.SHIPPED.stream().allMatch(platform ->
                        Files.isRegularFile(directory.resolve("native").resolve(platform.directory())
                                .resolve(platform.library("sqlite3")))),
                listing(directory.resolve("native")));
        check(checks, "and no C, since nothing here has to compile any",
                !Files.isDirectory(directory.resolve("native/sqlite3")),
                listing(directory.resolve("native")));
        check(checks, "and Turbo and Stimulus, so a page has its behaviour without a package manager",
                jarred(project, "web/assets/hotwired/turbo.js")
                        && jarred(project, "web/assets/hotwired/stimulus.js"),
                listing(directory));
        check(checks, "carried inside the jar rather than copied beside it",
                !Files.exists(directory.resolve("assets")),
                listing(directory));
        check(checks, "the installed jar is the named module tuul",
                named(project), listing(directory));
        check(checks, "the installed sources carry the module declaration",
                sourced(project, "module-info.java"), listing(directory));
    }

    /// Uses the installed APIs after the scaffold's native module has already
    /// built. Emptying `PATH` then proves that the installed SQLite is complete
    /// without also asking the generated project to compile its own C code.
    private static void usesInstalledTuul(Path project, List<Report.Check> checks)
            throws IOException, InterruptedException {
        Files.writeString(project.resolve("src/module-info.java"), MODULE);
        Files.writeString(project.resolve("src/demo/Notes.java"), NOTES);
        Files.writeString(project.resolve("entrypoints/cli/Main.java"), ENTRYPOINT);
        Files.writeString(project.resolve("test/demo/test/Run.java"), RUNNER);
        requires(project.resolve("test/module-info.java"), "tuul");

        var built = without(project, "build");
        check(checks, "a project that requires tuul builds with no compiler on the PATH",
                built.status() == 0, built.output());
        check(checks, "without compiling SQLite, which arrived compiled",
                !built.output().contains("compiling native module sqlite3")
                        && !exists(project, "build/native/" + Platform.host().library("sqlite3")),
                built.output());

        var ran = without(project, "run", "--", "the first note");
        check(checks, "and runs on argparse, application and sqlite3 together",
                ran.status() == 0 && ran.output().contains("notes: 1"),
                ran.output());
        check(checks, "on the SQLite that was vendored, not one the machine happened to have",
                ran.output().contains("sqlite " + version(project)),
                ran.output());

        var tested = without(project, "test");
        check(checks, "its tests run against them too",
                tested.status() == 0 && tested.output().contains("all tests passed"),
                tested.output());

        var docs = tuul(project, "docs", "application.Application");
        check(checks, "and tuul docs answers about a vendored tuul type",
                docs.output().contains("An application holds a state, the handlers that update it"),
                docs.output());

        check(checks, "tuul found its own sqlite3 from a directory that is not its own",
                exists(project, "build/index.db"),
                listing(project.resolve("build")));
    }

    /// What the vendored SQLite says its version is, asked of the library the
    /// project just built rather than assumed.
    private static String version(Path project) {
        return sqlite3.Database.version();
    }

    /// The failure path matters as much as the happy one: a build that cannot
    /// work has to say so, and has to keep working once the problem is gone.
    private static void recovers(Path project, List<Report.Check> checks) throws IOException, InterruptedException {
        var broken = project.resolve("src/demo/Broken.java");
        Files.writeString(broken, "package demo;\n\nclass Broken { oops }\n");
        var failed = tuul(project, "build");
        check(checks, "a broken source fails the build", failed.status() != 0, failed.output());
        check(checks, "and says which file", failed.output().contains("Broken.java"), failed.output());

        Files.delete(broken);
        var again = tuul(project, "build");
        check(checks, "removing it builds again", again.status() == 0, again.output());
    }

    /// Starts the real development command, talks to its HTTP server, changes
    /// the entrypoint, and proves the process survives both a reload and a
    /// rejected revision. Every wait has a deadline; the child is terminated
    /// in the finally block even when an assertion or request fails.
    private static void hotReloads(Path project, List<Report.Check> checks)
            throws IOException, InterruptedException {
        var classesBefore = classFiles(project);
        var port = freePort();
        var child = new ProcessBuilder(command(List.of("dev", "--port", Integer.toString(port))))
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        var output = new StringBuilder();
        var reader = Thread.ofVirtual().start(() -> capture(child, output));
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        var uri = URI.create("http://127.0.0.1:" + port + "/");
        var source = project.resolve("entrypoints/web/Main.java");
        var original = Files.readString(source);
        try {
            var first = await(client, uri, "Hello from demo!", Duration.ofSeconds(20));
            check(checks, "tuul dev serves the initial generation", first.reached(), detail(first.body(), output));
            check(checks, "tuul dev creates no reload output directory",
                    !Files.exists(project.resolve("build/reload")), listing(project.resolve("build")));

            var changed = original.replace("Hello from demo!\\n", "Hello from changed!\\n");
            Files.writeString(source, changed);
            var second = await(client, uri, "Hello from changed!", Duration.ofSeconds(20));
            check(checks, "saving Java activates a new generation", second.reached(), detail(second.body(), output));
            var pid = child.pid();

            Files.writeString(source, "package demo.web; public final class Main implements reload.Program { broken }\\n");
            // A rejected candidate must leave the last good generation serving.
            var retained = await(client, uri, "Hello from changed!", Duration.ofSeconds(5));
            check(checks, "a compiler failure keeps the last generation", retained.reached(), detail(retained.body(), output));
            check(checks, "the compiler failure is reported", awaitOutput(output, "compile:", Duration.ofSeconds(5)),
                    detail("no compile problem reported", output));

            Files.writeString(source, changed.replace("Hello from changed!\\n", "Hello from repaired!\\n"));
            var repaired = await(client, uri, "Hello from repaired!", Duration.ofSeconds(20));
            check(checks, "fixing the source activates again", repaired.reached(), detail(repaired.body(), output));
            check(checks, "reload keeps the host process", child.pid() == pid && child.isAlive(), detail("pid " + child.pid(), output));
            check(checks, "reload materializes no class files",
                    classFiles(project) == classesBefore, listing(project.resolve("build")));
        } finally {
            Files.writeString(source, original);
            child.destroy();
            if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly();
            if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly();
            reader.join(3_000);
        }
    }

    private static void capture(Process process, StringBuilder output) {
        try (var input = process.getInputStream()) {
            input.transferTo(new java.io.OutputStream() {
                @Override public void write(int value) {
                    synchronized (output) { output.append((char) value); }
                }
            });
        } catch (IOException ignored) {
            // The host may close its stream as it shuts down.
        }
    }

    private static HttpResult await(HttpClient client, URI uri, String expected, Duration timeout)
            throws IOException, InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(500)).GET().build();
        var last = "";
        while (System.nanoTime() < deadline) {
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                last = response.body();
                if (response.statusCode() == 200 && last.contains(expected)) return new HttpResult(true, last);
            } catch (IOException transientFailure) {
                last = transientFailure.getMessage() == null ? transientFailure.toString() : transientFailure.getMessage();
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        }
        return new HttpResult(false, last);
    }

    private static int freePort() throws IOException {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean awaitOutput(StringBuilder output, String expected, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (output) {
                if (output.indexOf(expected) >= 0) return true;
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        }
        synchronized (output) { return output.indexOf(expected) >= 0; }
    }

    private static String detail(String response, StringBuilder output) {
        synchronized (output) {
            return response + (output.isEmpty() ? "" : "\n" + output);
        }
    }

    private record HttpResult(boolean reached, String body) {}

    private static final String NOTES = """
            package demo;

            import application.Application;
            import application.Effect;
            import application.Message;
            import application.Step;
            import java.io.Writer;
            import java.nio.file.Path;
            import sqlite3.Database;

            /// An application whose state is a database.
            public final class Notes {

                private Notes() {}

                public static Database open(Path file) {
                    return Database.open(file);
                }

                public static Application<Long> of(Database database, Writer out) {
                    database.execute("create table if not exists notes (id integer primary key, body text)");
                    return Application.<Long>of(0L)
                            .on("note.write", (kept, message) -> Step.of(kept + 1,
                                    Effect.of("note.store").with("body", message.string("body", ""))))
                            .effect("note.store", (effect, emit) -> {
                                database.execute("insert into notes (body) values (?)", effect.string("body", ""));
                                emit.emit(Message.of("note.stored"));
                            })
                            .on("note.stored", (kept, message) -> Step.of(kept, Effect.of("note.say")))
                            .effect("note.say", (effect, emit) -> {
                                try (var rows = database.query("select count(*) from notes")) {
                                    rows.next();
                                    out.write("notes: " + rows.integer(0) + " (sqlite " + Database.version() + ")\\n");
                                    out.flush();
                                }
                            });
                }
            }
            """;

    private static final String MODULE = """
            module demo.app {
                requires tuul;

                exports demo;
                exports greet;
            }
            """;

    private static final String ENTRYPOINT = """
            package cli;

            import application.Message;
            import argparse.Command;
            import argparse.Parsed;
            import demo.Notes;
            import java.io.OutputStreamWriter;
            import java.nio.file.Path;
            import java.util.List;
import java.util.Map;

            public final class Main {
            public static void main(String[] args) throws Exception {
                var out = new OutputStreamWriter(System.out);
                var command = Command.named("demo", "keep notes")
                        .value("store", "where to keep them", "notes.db")
                        .argument("body", "what to write down");
                if (!(command.parse(List.of(args)) instanceof Parsed.Values values)) {
                    System.err.println("say what to write down");
                    return;
                }
                try (var database = Notes.open(Path.of(values.values().string("store", "notes.db")))) {
                    Notes.of(database, out)
                            .dispatch(Message.of("note.write").with("body", values.values().string("body", "")));
                }
            }
            }
            """;

    private static final String RUNNER = """
            package demo.test;

            import java.io.StringWriter;
            import java.nio.file.Files;

            public final class Run {
            public static void main(String[] args) throws Exception {
                var file = Files.createTempDirectory("demo").resolve("notes.db");
                var out = new StringWriter();
                try (var database = demo.Notes.open(file)) {
                    demo.Notes.of(database, out)
                            .dispatch(application.Message.of("note.write").with("body", "first"));
                }
                if (!out.toString().contains("notes: 1")) throw new AssertionError(out.toString());
                if (!Files.isRegularFile(file)) throw new AssertionError("no database on disk");
                greet.tests.GreeterTest.run();
                System.out.println("all tests passed");
            }
            }
            """;

    private static final String ANNOTATIONS_COORDINATE = "org.jetbrains:annotations:26.0.2";

    private static final String DEPENDENCY_TEST = """
            package demo.tests;

            import org.jetbrains.annotations.NotNull;

            public final class DependencyTest {

                private DependencyTest() {}

                public static void run() {
                    @NotNull var value = "dependency";
                    if (!value.equals("dependency")
                            || !NotNull.class.getName().equals("org.jetbrains.annotations.NotNull")) {
                        throw new AssertionError("the dependency returned the wrong value");
                    }
                    System.out.println("dependency works");
                }
            }
            """;

    private static final String DEPENDENCY_RUNNER = """
            package demo.test;

            /// Every test, in one process. `tuul test` compiles this
            /// directory and runs this file.

            public final class Run {
            public static void main(String[] args) throws Exception {
                demo.tests.GreetingTest.run();
                greet.tests.GreeterTest.run();
                demo.tests.DependencyTest.run();
                System.out.println("all tests passed");
            }
            }
            """;

    private static boolean repositoryUnavailable(String output) {
        return output.contains("HTTP 404 ") || output.contains("HTTP 500 ");
    }

    private static void requires(Path descriptor, String module) throws IOException {
        var source = Files.readString(descriptor);
        if (source.contains("requires " + module + ";")) return;
        var close = source.lastIndexOf('}');
        if (close < 0) throw new IOException("invalid module descriptor: " + descriptor);
        Files.writeString(descriptor, source.substring(0, close)
                + "    requires " + module + ";\n" + source.substring(close));
    }

    private static void check(List<Report.Check> checks, String what, boolean ok, String detail) {
        checks.add(new Report.Check(what, ok, ok ? "" : detail.strip()));
    }

    private record Ran(int status, String output) {}

    /// Runs the tuul command line itself, in the given directory.
    private static Ran tuul(Path directory, String... arguments) throws IOException, InterruptedException {
        var out = new StringWriter();
        var status = Launch.run(command(List.of(arguments)), directory, out);
        return new Ran(status, out.toString());
    }

    /// tuul with nothing on its PATH, so there is no compiler for it to find.
    /// Java is started by its absolute path and javac is in the process
    /// already, so everything except compiling C still works — which is the
    /// point: a project that vendored a library must not need one.
    private static Ran without(Path directory, String... arguments) throws IOException, InterruptedException {
        var out = new StringWriter();
        var status = Launch.run(command(List.of(arguments)), directory, out, Map.of("PATH", ""));
        return new Ran(status, out.toString());
    }

    private static List<String> command(List<String> arguments) {
        var tuul = codeSource(SelfTest.class);
        var sibling = tuul.resolveSibling("tuul-cli.jar");
        var cli = Files.exists(sibling) ? sibling : tuul.getParent().getParent()
                .resolve("bootstrap/cli");
        var modules = List.of(tuul, cli);
        return Launch.javaModule(List.of("--enable-native-access=tuul"), modules,
                "tuul.cli", "tuul.cli.Main", arguments);
    }

    private static Path codeSource(Class<?> type) {
        try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize(); }
        catch (Exception failure) { throw new IllegalStateException("cannot locate named Tuul module", failure); }
    }

    private static Json.Object describe(String output) {
        return Json.parse(output) instanceof Json.Object described ? described : Json.Object.of();
    }

    private static List<String> tags(Json.Object described) {
        var lines = new ArrayList<String>();
        for (var method : described.list("methods")) {
            if (!(method instanceof Json.Object entry)) continue;
            for (var tag : entry.list("tags")) {
                if (tag instanceof Json.Object written) {
                    lines.add(("@" + written.string("tag", "") + " " + written.string("name", "")).strip()
                            + " " + written.string("text", ""));
                }
            }
        }
        return lines;
    }

    private static boolean exists(Path project, String path) {
        return Files.isRegularFile(project.resolve(path));
    }

    private static long classFiles(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .count();
        }
    }

    /// Whether the vendored tuul jar holds an entry. Assets travel inside it
    /// now, beside the classes of the package that ships them, so this is where
    /// a project's copy of Turbo is.
    private static boolean jarred(Path project, String entry) {
        return archived(project, Version.artifact() + ".jar", entry);
    }

    private static boolean sourced(Path project, String entry) {
        return archived(project, Version.artifact() + "-sources.jar", entry);
    }

    private static boolean archived(Path project, String artifact, String entry) {
        try (var jar = new java.util.jar.JarFile(
                installedTuul(project).resolve(artifact).toFile())) {
            return jar.getEntry(entry) != null;
        } catch (IOException missing) {
            return false;
        }
    }

    private static boolean named(Path project) {
        var jar = installedTuul(project).resolve(Version.artifact() + ".jar");
        return ModuleFinder.of(jar).find("tuul").filter(reference -> !reference.descriptor().isAutomatic()).isPresent();
    }

    private static Path installedTuul(Path project) {
        return project.resolve("vendor/dev.tuul/tuul").resolve(Version.NUMBER);
    }

    /// What is actually there, for a check that expected something else.
    private static String listing(Path project) {
        if (!Files.isDirectory(project)) return "no " + project;
        try (var tree = Files.walk(project)) {
            return tree.map(project::relativize).map(Path::toString).sorted().reduce("", (all, path) -> all + path + "\n");
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static void delete(Path root) throws IOException {
        try (var tree = Files.walk(root)) {
            tree.sorted(Comparator.reverseOrder()).forEach(SelfTest::remove);
        }
    }

    private static void remove(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
