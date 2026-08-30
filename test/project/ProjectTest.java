package project;

import ffi.Library;
import application.Message;
import ffi.Platform;
import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.module.ModuleFinder;
import symbols.Sources;
import tuul.Version;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

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
        vendored(layout, project);
        installs(layout);
        carries(project);
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

    /// A dependency that is on the classpath to compile and missing to run is a
    /// dependency that fails on its first call. This runs the project the way
    /// `tuul run` and `tuul test` do — through the application — because that is
    /// where the classpath is assembled.
    private static void vendored(Layout layout, Path project) throws IOException {
        var classes = new LinkedHashMap<String, byte[]>();
        var source = Files.createTempDirectory("tuul-tiny");
        source.toFile().deleteOnExit();
        Files.createDirectories(source.resolve("tiny"));
        Files.writeString(source.resolve("tiny/Tiny.java"), """
                package tiny;

                public final class Tiny {

                    public static String hello() {
                        return "from a vendored jar";
                    }
                }
                """);
        Sources.compile(List.of(source)).forEach((type, bytes) -> classes.put(type.replace('.', '/') + ".class", bytes));
        Files.createDirectories(project.resolve("vendor/tiny"));
        try (var jar = new JarOutputStream(Files.newOutputStream(project.resolve("vendor/tiny/tiny-1.0.jar")))) {
            for (var entry : classes.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }

        Files.writeString(project.resolve("src/cli/main.java"), """
                import tiny.Tiny;

                void main(String[] args) {
                    System.out.println(Tiny.hello());
                }
                """);
        Files.writeString(project.resolve("test/run.java"), """
                void main() {
                    if (!tiny.Tiny.hello().isBlank()) System.out.println("all tests passed");
                }
                """);

        Check.equal("a vendored jar is on the classpath that runs the application",
                "from a vendored jar\n",
                ran(layout, Message.of("project.run")));
        Check.that("and on the one that runs the tests",
                ran(layout, Message.of("project.test")).contains("all tests passed"));
    }

    /// tuul putting itself into a project is tuul producing an ordinary
    /// dependency: two jars and a compiled SQLite for every platform, in a
    /// directory `vendor/` already understands.
    private static void installs(Layout layout) throws IOException, InterruptedException {
        var installed = Install.into(layout, false, new StringWriter());
        var jar = installed.directory().resolve(Version.artifact() + ".jar");
        var sources = installed.directory().resolve(Version.artifact() + "-sources.jar");
        Check.that("it writes a jar named for the version", Files.isRegularFile(jar));
        Check.that("and a sources jar beside it", Files.isRegularFile(sources));
        Check.equal("it vendors a library for every platform tuul ships, because vendor/ is committed",
                Platform.SHIPPED.stream().map(Platform::directory).toList(),
                installed.platforms());
        Check.that("and each one is the library that platform loads",
                Platform.SHIPPED.stream().allMatch(platform -> Files.isRegularFile(
                        installed.directory().resolve("native/" + platform.directory() + "/" + platform.library("sqlite3")))));
        Check.that("no C among them: nothing here has to compile anything",
                !Files.exists(installed.directory().resolve("native/sqlite3")));
        Check.that("it says how much it packed", installed.classes() > 100 && installed.sources() > 30);

        var entries = entries(jar);
        Check.that("the libraries are in it", entries.contains("json/Json.class"));
        Check.that("the named module declaration is in it", entries.contains("module-info.class"));
        Check.that("the sources carry the same module declaration", entries(sources).contains("module-info.java"));
        Check.that("no entrypoint is: a default-package class would take a name it does not own",
                entries.stream().noneMatch(entry -> entry.endsWith(".class")
                        && !entry.contains("/") && !entry.equals("module-info.class")));
        var module = ModuleFinder.of(jar).find("tuul").orElseThrow().descriptor();
        Check.that("the installed jar is the explicit module tuul", !module.isAutomatic());
        Check.that("one module exports the libraries", module.exports().stream()
                .anyMatch(exported -> exported.source().equals("application")));
        Check.that("and the manifest says which tuul this is",
                new String(read(jar, "META-INF/MANIFEST.MF")).contains("Implementation-Version: " + Version.NUMBER));
        Check.that("the dependency manifest does not name the separate CLI",
                !new String(read(jar, "META-INF/MANIFEST.MF")).contains("Main-Class:"));

        Install.into(layout, false, new StringWriter());
        try (var again = Files.list(installed.directory())) {
            Check.equal("installing twice replaces the artifact rather than stacking a second copy of every class",
                    2,
                    (int) again.filter(path -> path.getFileName().toString().endsWith(".jar")).count());
        }

        var sourced = Install.into(layout, true, new StringWriter());
        Check.that("--source vendors the C instead, for a platform with no library",
                Files.isRegularFile(sourced.directory().resolve("native/sqlite3/sqlite3.c")));
        Check.equal("and says it vendored no libraries", List.of(), sourced.platforms());
        Check.that("the libraries are gone, since the two are alternatives rather than additions",
                Platform.SHIPPED.stream().noneMatch(platform ->
                        Files.exists(sourced.directory().resolve("native/" + platform.directory()))));
    }

    /// How a distribution carries its libraries: inside the jar, so an
    /// installed tuul is one file that can still install itself. The fallback
    /// is a `native` directory beside the jar, which is what an unpacked
    /// install looks like.
    private static void carries(Path project) throws IOException {
        var packed = project.resolve("dist");
        Files.createDirectories(packed);
        var platform = Platform.SHIPPED.getFirst();

        var inside = packed.resolve(Version.artifact() + ".jar");
        jar(inside, Map.of("native/" + platform.directory() + "/" + platform.library("sqlite3"), "not really a library".getBytes()));
        try (var libraries = Home.at(inside).libraries()) {
            Check.that("a jar carrying libraries answers with the ones inside it",
                    Files.isRegularFile(libraries.root().resolve(platform.directory()).resolve(platform.library("sqlite3"))));
        }

        var beside = packed.resolve("beside").resolve(Version.artifact() + ".jar");
        Files.createDirectories(beside.getParent().resolve("native").resolve(platform.directory()));
        jar(beside, Map.of("json/Json.class", "not really a class".getBytes()));
        Files.writeString(beside.getParent().resolve("native").resolve(platform.directory()).resolve(platform.library("sqlite3")), "x");
        try (var libraries = Home.at(beside).libraries()) {
            Check.that("a jar with none inside falls back to the directory beside it",
                    Files.isDirectory(libraries.root().resolve(platform.directory())));
        }
    }

    private static void jar(Path path, Map<String, byte[]> entries) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static List<String> entries(Path jar) throws IOException {
        try (var file = new JarFile(jar.toFile())) {
            return file.stream().map(JarEntry::getName).toList();
        }
    }

    private static byte[] read(Path jar, String entry) throws IOException {
        try (var file = new JarFile(jar.toFile()); var in = file.getInputStream(file.getEntry(entry))) {
            return in.readAllBytes();
        }
    }

    private static String ran(Layout layout, Message message) {
        var out = new StringWriter();
        var err = new StringWriter();
        var state = App.of(State.of(layout.root()), out, err).dispatch(message);
        Check.equal("running " + message.type() + " succeeds: " + err, 0, state.exit());
        return out.toString();
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
