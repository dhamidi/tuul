package project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// Writes a new project: a library, an entrypoint that calls it, a test, and
/// the `vendor/` directory that will hold its dependencies.
///
/// Not a blank slate — everything generated here is meant to be extended rather
/// than deleted, and it is enough for `tuul build`, `tuul run` and `tuul test`
/// to have something real to do.
public final class Scaffold {

    private Scaffold() {}

    /// Creates the project and returns the package its library lives in.
    public static String create(Path directory, String name) throws IOException {
        if (Files.exists(directory)) throw new IOException(directory + " already exists");
        var library = library(name);
        if (library.isEmpty()) throw new IOException("a project name needs at least one letter or digit: " + name);

        write(directory.resolve("src/" + library + "/Greeting.java"), greeting(library));
        write(directory.resolve("src/greet/Greeter.java"), greeter());
        write(directory.resolve("src/cli/" + Layout.ENTRYPOINT), entrypoint(library));
        write(directory.resolve("native/hello.c"), hello());
        write(directory.resolve("test/" + library + "/GreetingTest.java"), test(library));
        write(directory.resolve("test/greet/GreeterTest.java"), greeterTest());
        write(directory.resolve("test/run.java"), runner(library));
        write(directory.resolve(".gitignore"), "build/\n");
        write(directory.resolve("README.md"), readme(name, library));
        Files.createDirectories(directory.resolve("vendor"));
        return library;
    }

    /// A directory name is not a package name: `hello-world` holds the library
    /// `helloworld`.
    public static String library(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String greeting(String library) {
        return """
                package %s;

                /**
                 * Greets whoever is named.
                 *
                 * @since 1.0
                 */
                public final class Greeting {

                    private final String opening;

                    /**
                     * A greeting that opens with the given word.
                     *
                     * @param opening how to open, such as "Hello"
                     */
                    public Greeting(String opening) {
                        this.opening = opening;
                    }

                    /**
                     * Greets one name.
                     *
                     * @param name who to greet
                     * @return the greeting, addressed to name
                     * @throws IllegalArgumentException if name is blank
                     */
                    public String greet(String name) {
                        if (name.isBlank()) throw new IllegalArgumentException("a name is required");
                        return opening + ", " + name + "!";
                    }
                }
                """.formatted(library);
    }

    private static String entrypoint(String library) {
        return """
                import %s.Greeting;
                import greet.Greeter;

                /// The command line: greet whoever is named on it. A thin call
                /// through to the libraries — if this file grows logic of its
                /// own, that logic belongs in src/%s.
                ///
                /// Every name goes out through native/hello.c, which calls back
                /// in to print it.

                void main(String[] args) {
                    var greeting = new Greeting("Hello");
                    var names = args.length == 0 ? new String[] {"world"} : args;
                    for (var name : names) Greeter.greet(name, greeted -> System.out.println(greeting.greet(greeted)));
                }
                """.formatted(library, library);
    }

    /// The native module: C that takes a function pointer and calls it back.
    /// It is a template for wiring in a real library, not a toy to delete.
    private static String hello() {
        return """
                #include <stddef.h>

                /* Called back with the name, once, if there is one. */
                typedef void (*on_greeting)(const char *name);

                int greet(const char *name, on_greeting greeted) {
                    if (name == NULL || name[0] == '\\0') return 0;
                    greeted(name);
                    return 1;
                }
                """;
    }

    /// The Java side: a downcall into C, and a Java method handed to C as a
    /// function pointer. java.lang.foreign end to end — no JNI, no javah, no
    /// generated glue.
    private static String greeter() {
        return """
                package greet;

                import static java.lang.foreign.ValueLayout.ADDRESS;
                import static java.lang.foreign.ValueLayout.JAVA_INT;

                import java.lang.foreign.Arena;
                import java.lang.foreign.FunctionDescriptor;
                import java.lang.foreign.Linker;
                import java.lang.foreign.MemorySegment;
                import java.lang.foreign.SymbolLookup;
                import java.lang.invoke.MethodHandle;
                import java.lang.invoke.MethodHandles;
                import java.lang.invoke.MethodType;
                import java.nio.file.Path;
                import java.util.function.Consumer;

                /// The Java side of native/hello.c.
                ///
                /// greet() is a downcall into C; the Consumer handed to it
                /// becomes a real function pointer that C calls back through.
                /// The library is the one `tuul build` leaves in build/native,
                /// which is why this runs from the project directory.
                public final class Greeter {

                    private static final Linker LINKER = Linker.nativeLinker();

                    private static final SymbolLookup HELLO =
                            SymbolLookup.libraryLookup(Path.of("build/native", System.mapLibraryName("hello")), Arena.global());

                    private static final MethodHandle GREET = LINKER.downcallHandle(
                            HELLO.find("greet").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

                    private static final FunctionDescriptor CALLBACK = FunctionDescriptor.ofVoid(ADDRESS);

                    private static final MethodHandle GREETED = greeted();

                    private Greeter() {}

                    /// Greets one name, and answers whether C called back.
                    public static boolean greet(String name, Consumer<String> onGreeting) {
                        try (var arena = Arena.ofConfined()) {
                            var callback = LINKER.upcallStub(GREETED.bindTo(onGreeting), CALLBACK, arena);
                            return (int) GREET.invokeExact(arena.allocateFrom(name), callback) == 1;
                        } catch (Throwable e) {
                            throw new IllegalStateException("calling greet() failed", e);
                        }
                    }

                    /// What C calls. The name arrives as a pointer, and is read
                    /// into a String before anything else touches it.
                    private static void greeted(Consumer<String> onGreeting, MemorySegment name) {
                        onGreeting.accept(name.reinterpret(Long.MAX_VALUE).getString(0));
                    }

                    private static MethodHandle greeted() {
                        try {
                            return MethodHandles.lookup().findStatic(Greeter.class, "greeted",
                                    MethodType.methodType(void.class, Consumer.class, MemorySegment.class));
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("greeted() is missing", e);
                        }
                    }
                }
                """;
    }

    private static String greeterTest() {
        return """
                package greet;

                import java.util.ArrayList;
                import java.util.List;

                public final class GreeterTest {

                    private GreeterTest() {}

                    public static void run() {
                        var greeted = new ArrayList<String>();
                        if (!Greeter.greet("tuul", greeted::add)) throw new AssertionError("C should have called back");
                        if (!greeted.equals(List.of("tuul"))) throw new AssertionError("the callback saw " + greeted);
                        if (Greeter.greet("", greeted::add)) throw new AssertionError("an empty name should be refused");
                    }
                }
                """;
    }

    private static String test(String library) {
        return """
                package %s;

                public final class GreetingTest {

                    private GreetingTest() {}

                    public static void run() {
                        var greeting = new Greeting("Hello");
                        expect("Hello, tuul!", greeting.greet("tuul"));
                        refuses(greeting, " ");
                    }

                    private static void expect(String expected, String actual) {
                        if (expected.equals(actual)) return;
                        throw new AssertionError("expected " + expected + ", got " + actual);
                    }

                    private static void refuses(Greeting greeting, String name) {
                        try {
                            greeting.greet(name);
                        } catch (IllegalArgumentException expected) {
                            return;
                        }
                        throw new AssertionError("a blank name should be refused");
                    }
                }
                """.formatted(library);
    }

    private static String runner(String library) {
        return """
                /// Every test, in one process. `tuul test` compiles this
                /// directory and runs this file.

                void main() {
                    %s.GreetingTest.run();
                    greet.GreeterTest.run();
                    System.out.println("all tests passed");
                }
                """.formatted(library);
    }

    private static String readme(String name, String library) {
        return """
                # %s

                ```sh
                tuul build          # compile src/ into build/
                tuul run -- tuul    # run src/cli/main.java
                tuul test           # compile and run test/
                tuul docs %s.Greeting
                ```

                `src/%s/` is the library — the application lives there.
                `src/cli/main.java` is an entrypoint, and should stay thin.
                `src/resources/` contains files for the classpath root.
                Keep package-local resources beside their library classes.
                `src/greet/` wraps `native/hello.c` through java.lang.foreign:
                a downcall into C, and a Java method C calls back through.
                `native/` is C, one library per file or per directory.
                `vendor/` is the dependency list: whatever is in it, the project
                is built against.
                """.formatted(name, library, library);
    }
}
