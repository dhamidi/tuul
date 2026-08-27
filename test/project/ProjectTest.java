package project;

import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ProjectTest {

    private ProjectTest() {}

    public static void run() throws IOException, InterruptedException {
        var root = Files.createTempDirectory("tuul-project");
        root.toFile().deleteOnExit();
        var project = root.resolve("hello-world");

        scaffolds(project);
        var layout = new Layout(project);
        reads(layout);
        builds(layout);
        launches(layout);
        refuses(layout, project);
    }

    private static void scaffolds(Path project) throws IOException {
        Check.equal("a directory name is not a package name", "helloworld", Scaffold.library("hello-world"));
        Check.equal("scaffolding reports the library it made", "helloworld", Scaffold.create(project, "hello-world"));
        Check.that("it writes a library", Files.isRegularFile(project.resolve("src/helloworld/Greeting.java")));
        Check.that("it writes an entrypoint", Files.isRegularFile(project.resolve("src/cli/main.java")));
        Check.that("it writes a test", Files.isRegularFile(project.resolve("test/run.java")));
        Check.that("it writes the vendor directory", Files.isDirectory(project.resolve("vendor")));
        Check.throwing("it will not write over a project that exists",
                () -> uncheck(() -> Scaffold.create(project, "hello-world")));
    }

    /// The tree is the configuration: a directory holding main.java is an
    /// entrypoint, the rest are libraries.
    private static void reads(Layout layout) throws IOException {
        Check.equal("entrypoints are the directories with a main.java", List.of("cli"), layout.entrypoints());
        Check.equal("everything else is a library",
                List.of("helloworld"),
                layout.libraries().stream().map(path -> path.getFileName().toString()).toList());
        Check.equal("cli is the entrypoint to run when none is named", "cli", layout.entrypoint(""));
        Check.equal("and a named one is taken as given", "cli", layout.entrypoint("cli"));
    }

    private static void builds(Layout layout) throws IOException {
        var built = Build.compile(layout);
        Check.that("a scaffolded project compiles: " + built.problems(), built.ok());
        Check.that("the library lands in build/classes",
                Files.isRegularFile(layout.classes().resolve("helloworld/Greeting.class")));
        Check.that("each entrypoint lands apart from it, since every main.java is class main",
                Files.isRegularFile(layout.entry("cli").resolve("main.class")));

        var tests = Build.compileTests(layout);
        Check.that("and its tests compile against it: " + tests.problems(), tests.ok());
    }

    private static void launches(Layout layout) throws IOException, InterruptedException {
        var out = new StringWriter();
        var status = Launch.run(
                Launch.java(List.of(), List.of(layout.classes(), layout.entry("cli")), "main", List.of("tuul")),
                layout.root(),
                out);
        Check.equal("a built entrypoint runs", 0, status);
        Check.equal("with the arguments it was given", "Hello, tuul!\n", out.toString());

        var tests = new StringWriter();
        Check.equal("so do the tests",
                0,
                Launch.run(Launch.java(List.of(), List.of(layout.classes(), layout.tests()), "run", List.of()),
                        layout.root(), tests));
        Check.that("and they say so", tests.toString().contains("all tests passed"));
    }

    private static void refuses(Layout layout, Path project) throws IOException {
        var broken = project.resolve("src/helloworld/Broken.java");
        Files.writeString(broken, "package helloworld;\n\nclass Broken { oops }\n");
        var built = Build.compile(layout);
        Check.that("a broken source stops the build", !built.ok());
        Check.that("and the file is named", String.join("", built.problems()).contains("Broken.java"));
        Files.delete(broken);
        Check.that("removing it builds again", Build.compile(layout).ok());

        Check.that("a directory that is not a project says so",
                !Build.compile(new Layout(project.resolve("nowhere"))).ok());
    }

    private static void uncheck(Body body) {
        try {
            body.run();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private interface Body {
        void run() throws IOException;
    }
}
