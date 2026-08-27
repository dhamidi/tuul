package project;

import ffi.Library;
import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
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
        compiles(layout, project);
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
                List.of("greet", "helloworld"),
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

    /// The native pipeline end to end: C on disk, a shared library out of the
    /// compiler, and a Java call into it.
    private static void compiles(Layout layout, Path project) throws IOException, InterruptedException {
        Files.createDirectories(layout.nativeRoot());
        var source = layout.nativeRoot().resolve("answer.c");
        Files.writeString(source, "int answer(void) { return 42; }\n");

        var built = Native.build(layout, new StringWriter());
        Check.that("the native modules compile: " + built.problems(), built.ok());
        Check.equal("a loose .c file is a library named after it", List.of("answer", "hello"), built.built());
        Check.that("and lands in build/native", Files.isRegularFile(layout.library("answer")));

        var again = Native.build(layout, new StringWriter());
        Check.equal("nothing is recompiled when nothing changed", List.of(), again.built());
        Check.equal("it is reported as current instead", List.of("answer", "hello"), again.current());

        var answer = Library.open(layout.library("answer"))
                .function("answer", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        Check.equal("and Java can call it", 42, call(answer));

        Files.writeString(source, "int answer(void) { oops }\n");
        var broken = Native.build(layout, new StringWriter());
        Check.that("a broken module fails the build", !broken.ok());
        Check.that("in the compiler's words", String.join("", broken.problems()).contains("answer"));
        Check.that("and leaves no half-built library behind", !Files.isRegularFile(layout.library("answer")));
        Files.delete(source);
    }

    private static int call(MethodHandle function) {
        try {
            return (int) function.invokeExact();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
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
