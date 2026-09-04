package project;

import ffi.Library;
import application.Message;
import compiler.Compiler;
import ffi.Platform;
import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.module.ModuleFinder;
import symbols.Sources;
import symbols.Vendor;
import tuul.Version;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public final class ProjectTest {

    private ProjectTest() {}

    private static Path app(Layout layout) throws IOException {
        return layout.moduleOutput(layout.application().name());
    }

    private static Path entry(Layout layout, String name) throws IOException {
        var module = layout.entrypointModule().orElseThrow();
        return layout.moduleOutput(module.name());
    }

    private static Path tests(Layout layout) throws IOException {
        return layout.moduleOutput(layout.testModule().orElseThrow().name());
    }

    public static void run() throws IOException, InterruptedException {
        controlsProcesses();
        var root = Files.createTempDirectory("tuul-project");
        root.toFile().deleteOnExit();
        var project = root.resolve("hello-world");

        scaffolds(project);
        var layout = new Layout(project);
        reads(layout);
        controlsCompilation(layout);
        controlsNativeCompilation(layout);
        carries(project);
    }

    private static void controlsProcesses() throws IOException, InterruptedException {
        var seen = new AtomicReference<ProcessRunner.Command>();
        ProcessRunner processes = (command, out) -> {
            seen.set(command);
            out.write("controlled\n");
            return 7;
        };
        var output = new StringWriter();
        var directory = Path.of("fixture");
        var status = Launch.run(List.of("tool", "argument"), directory, output, Map.of("MODE", "test"), processes);
        Check.equal("an injected process runner supplies the exit status", 7, status);
        Check.equal("an injected process runner supplies output", "controlled\n", output.toString());
        Check.equal("the runner receives arguments without a shell", List.of("tool", "argument"),
                seen.get().arguments());
        Check.equal("the runner receives the working directory", directory, seen.get().directory());
        Check.equal("the runner receives environment changes", Map.of("MODE", "test"), seen.get().environment());
        Check.equal("ordinary launch output includes standard error", ProcessRunner.Errors.MERGE, seen.get().errors());
    }

    /// Runs real compilers, child JVMs, FFI, and installation in one fixture.
    ///
    /// The fixture is temporary and contains the scaffolded project required by
    /// both operations. The fast project suite does not call either operation.
    public static void integration() throws IOException, InterruptedException {
        var root = Files.createTempDirectory("tuul-project-integration");
        root.toFile().deleteOnExit();
        var project = root.resolve("hello-world");
        scaffolds(project);
        var layout = new Layout(project);
        compiles(layout, project);
        builds(layout);
        caches(layout);
        launches(layout);
        refuses(layout, project);
        vendored(layout, project);
        installs(layout);
    }

    private static void controlsCompilation(Layout layout) throws IOException {
        var calls = new AtomicInteger();
        Compiler compiler = (request, classes) -> {
            calls.incrementAndGet();
            // Keep the injected seam while emitting real named-module output;
            // strict graph validation must inspect a genuine descriptor.
            return Compiler.system().compile(new Compiler.Request(request.sources(), request.modulePath(),
                    request.module(), request.release(), request.debug(), request.patch(), request.addExports(),
                    request.moduleSources()), classes);
        };

        var built = Build.compile(layout, compiler);
        Check.that("an injected Java compiler builds the project", built.ok());
        Check.that("the injected compiler writes library output",
                Files.isRegularFile(app(layout).resolve("helloworld/Greeting.class")));
        Check.that("the injected compiler writes entrypoint output",
                Files.isRegularFile(entry(layout, "cli").resolve("cli/Main.class")));
        Check.that("an injected Java compiler builds tests", Build.compileTests(layout, compiler).ok());
        Check.that("the injected compiler writes test output",
                Files.isRegularFile(tests(layout).resolve("helloworld/test/Run.class")));

        Build.compile(layout, compiler);
        Build.compileTests(layout, compiler);
        Check.equal("current outputs do not call the injected compiler again", 3, calls.get());
    }

    private static void controlsNativeCompilation(Layout layout) throws IOException, InterruptedException {
        var calls = new AtomicInteger();
        ProcessRunner processes = (command, output) -> {
            calls.incrementAndGet();
            var at = command.arguments().indexOf("-o");
            Files.writeString(Path.of(command.arguments().get(at + 1)), "fixture library");
            return 0;
        };
        var built = Native.build(layout, new StringWriter(), processes);
        Check.equal("an injected process runner builds a native module", List.of("hello"), built.built());
        Check.that("the injected process runner supplies the native output",
                Files.isRegularFile(layout.library("hello")));
        var current = Native.build(layout, new StringWriter(), processes);
        Check.equal("a current native module starts no process", List.of("hello"), current.current());
        Check.equal("the native compiler process ran once", 1, calls.get());
    }

    private static void scaffolds(Path project) throws IOException {
        Check.equal("a directory name is not a package name", "helloworld", Scaffold.library("hello-world"));
        Check.equal("scaffolding reports the library it made", "helloworld", Scaffold.create(project, "hello-world"));
        Check.that("it writes a library", Files.isRegularFile(project.resolve("src/helloworld/Greeting.java")));
        Check.that("it writes an entrypoint", Files.isRegularFile(project.resolve("entrypoints/cli/Main.java")));
        Check.that("it writes a test", Files.isRegularFile(project.resolve("test/helloworld/test/Run.java")));
        Check.that("it writes the vendor directory", Files.isDirectory(project.resolve("vendor")));
        Check.throwing("it will not write over a project that exists",
                () -> uncheck(() -> Scaffold.create(project, "hello-world")));
    }

    /// The tree is the configuration: a named Main entrypoint module is
    /// separate from the application module.
    private static void reads(Layout layout) throws IOException {
        Check.equal("entrypoints are the directories with a named Main", List.of("cli"), layout.entrypoints());
        Check.equal("the application source root is one named module",
                List.of("src"), layout.libraries().stream().map(path -> path.getFileName().toString()).toList());
        Check.equal("cli is the entrypoint to run when none is named", "cli", layout.runEntrypoint("").name());
        Check.equal("and a named one is taken as given", "cli", layout.runEntrypoint("cli").name());
    }

    private static void builds(Layout layout) throws IOException {
        Files.createDirectories(layout.resources());
        Files.writeString(layout.resources().resolve("application.properties"), "spring.application.name=hello\n");
        Files.writeString(layout.src().resolve("helloworld/package.txt"), "package-local\n");
        Files.writeString(layout.entrypointsRoot().resolve("cli/index.html"), "<h1>Hello</h1>\n");
        var built = Build.compile(layout);
        Check.that("a scaffolded project compiles: " + built.problems(), built.ok());
        Check.that("the library lands in its named module output",
                Files.isRegularFile(app(layout).resolve("helloworld/Greeting.class")));
        Check.that("each entrypoint lands in its named module",
                Files.isRegularFile(entry(layout, "cli").resolve("cli/Main.class")));
        Check.that("root resources land at the module root",
                Files.isRegularFile(app(layout).resolve("application.properties")));
        Check.that("a Spring-style root lookup finds application.properties",
                rootResource(layout, "application.properties"));
        Check.that("package-local resources remain beside library classes",
                Files.isRegularFile(app(layout).resolve("helloworld/package.txt")));
        Check.that("entrypoint resources retain their module-root path",
                Files.isRegularFile(entry(layout, "cli").resolve("cli/index.html")));

        var tests = Build.compileTests(layout);
        Check.that("and its tests compile against it: " + tests.problems(), tests.ok());
    }

    private static void caches(Layout layout) throws IOException {
        var classes = app(layout).resolve("helloworld/Greeting.class");
        var classesBefore = Files.getLastModifiedTime(classes);
        Check.that("a current project does not invoke javac again", Build.compile(layout).ok());
        Check.equal("the cached library output is left alone", classesBefore, Files.getLastModifiedTime(classes));

        var tests = tests(layout).resolve("helloworld/test/Run.class");
        var testsBefore = Files.getLastModifiedTime(tests);
        Check.that("current tests do not invoke javac again", Build.compileTests(layout).ok());
        Check.equal("the cached test output is left alone", testsBefore, Files.getLastModifiedTime(tests));

        Files.writeString(layout.resources().resolve("application.properties"), "spring.application.name=changed\n");
        Check.that("changing a root resource invalidates the library build", Build.compile(layout).ok());
        Check.equal("the changed root resource reaches build output", "spring.application.name=changed\n",
                Files.readString(app(layout).resolve("application.properties")));
    }

    private static boolean rootResource(Layout layout, String name) throws IOException {
        try {
            var module = layout.application().name();
            var configuration = ModuleLayer.boot().configuration().resolve(
                    ModuleFinder.of(), ModuleFinder.of(app(layout)), java.util.Set.of(module));
            var controller = ModuleLayer.defineModulesWithOneLoader(configuration,
                    List.of(ModuleLayer.boot()), ProjectTest.class.getClassLoader());
            return controller.layer().findLoader(module).getResource(name) != null;
        } catch (RuntimeException failure) {
            throw new IOException("cannot load project module " + layout.application().name(), failure);
        }
    }

    private static void launches(Layout layout) throws IOException, InterruptedException {
        var out = new StringWriter();
        var status = Launch.run(
                Launch.javaModule(List.of("--enable-native-access=" + layout.application().name()),
                        List.of(app(layout), entry(layout, "cli")),
                        layout.entrypointModule().orElseThrow().name(), "cli.Main", List.of("tuul")),
                layout.root(),
                out);
        Check.equal("a built entrypoint runs", 0, status);
        Check.equal("with the arguments it was given", "Hello, tuul!\n", out.toString());

        var tests = new StringWriter();
        var testModule = layout.testModule().orElseThrow();
        Check.equal("so do the tests",
                0,
                Launch.run(Launch.javaModule(List.of("--enable-native-access=" + layout.application().name()),
                                List.of(app(layout), tests(layout)), testModule.name(), layout.testMain(), List.of()),
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

    /// A dependency is resolved on the module path for both compilation and
    /// execution. This runs the project through the same named launches as
    /// `tuul run` and `tuul test`.
    private static void vendored(Layout layout, Path project) throws IOException {
        var classes = new LinkedHashMap<String, byte[]>();
        var source = Files.createTempDirectory("tuul-tiny");
        source.toFile().deleteOnExit();
        Files.createDirectories(source.resolve("tiny"));
        Files.writeString(source.resolve("module-info.java"), "module tiny { exports tiny; }\n");
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

        Files.writeString(project.resolve("src/module-info.java"), """
                module helloworld.app {
                    requires tiny;
                    exports helloworld;
                    exports greet;
                }
                """);
        Files.writeString(project.resolve("entrypoints/module-info.java"), """
                module helloworld.entrypoints {
                    requires helloworld.app;
                    requires tiny;
                }
                """);
        Files.writeString(project.resolve("test/module-info.java"), """
                module helloworld.test {
                    requires helloworld.app;
                    requires tiny;
                }
                """);

        Files.writeString(project.resolve("entrypoints/cli/Main.java"), """
                package cli;
                import tiny.Tiny;

                public final class Main {
                public static void main(String[] args) {
                    System.out.println(Tiny.hello());
                }
                }
                """);
        Files.writeString(project.resolve("test/helloworld/test/Run.java"), """
                package helloworld.test;
                public final class Run {
                public static void main(String[] args) {
                    if (!tiny.Tiny.hello().isBlank()) System.out.println("all tests passed");
                }
                }
                """);

        Check.equal("a vendored jar is on the module path for the application",
                "from a vendored jar\n",
                ran(layout, Message.of("project.run")));
        Check.that("and on the module path for the tests",
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
        var vendor = Vendor.of(List.of(layout.vendor()));
        var graph = vendor.graph(List.of("tuul"));
        Check.that("the vendor graph puts the binary on the module path and keeps sources off it",
                graph.module("tuul").flatMap(module -> module.origin().path()).orElseThrow().equals(jar)
                        && !vendor.artifacts().containsKey(sources) && vendor.sources().contains(sources));
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
        Check.that("no entrypoint is: a named module owns its entrypoint class",
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
