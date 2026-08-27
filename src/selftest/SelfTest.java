package selftest;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import json.Json;
import project.Launch;

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
        builds(project, checks);
        runs(project, checks);
        tests(project, checks);
        documents(project, checks);
        recovers(project, checks);

        var ok = checks.stream().allMatch(Report.Check::ok);
        if (ok) delete(root);
        return new Report(root, !ok, List.copyOf(checks));
    }

    private static void scaffolds(Path root, Path project, List<Report.Check> checks) throws IOException, InterruptedException {
        var created = tuul(root, "new", "demo");
        check(checks, "tuul new exits cleanly", created.status() == 0, created.output());
        check(checks, "it writes a library", exists(project, "src/demo/Greeting.java"), listing(project));
        check(checks, "it writes an entrypoint", exists(project, "src/cli/main.java"), listing(project));
        check(checks, "it writes a test", exists(project, "test/run.java"), listing(project));
        check(checks, "it writes the vendor directory", Files.isDirectory(project.resolve("vendor")), listing(project));
    }

    private static void builds(Path project, List<Report.Check> checks) throws IOException, InterruptedException {
        var built = tuul(project, "build");
        check(checks, "tuul build exits cleanly", built.status() == 0, built.output());
        check(checks, "it compiles the library", exists(project, "build/classes/demo/Greeting.class"), built.output());
        check(checks, "it compiles the entrypoint apart from it", exists(project, "build/entry/cli/main.class"), built.output());
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

    private static List<String> command(List<String> arguments) {
        var classpath = List.of(System.getProperty("java.class.path").split(File.pathSeparator)).stream().map(Path::of).toList();
        return Launch.java(List.of("--enable-preview"), classpath, "main", arguments);
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
