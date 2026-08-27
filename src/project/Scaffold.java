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
        write(directory.resolve("src/cli/" + Layout.ENTRYPOINT), entrypoint(library));
        write(directory.resolve("test/" + library + "/GreetingTest.java"), test(library));
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

                /// The command line: greet whoever is named on it. A thin call
                /// through to the library — if this file grows logic of its own,
                /// that logic belongs in src/%s.

                void main(String[] args) {
                    var greeting = new Greeting("Hello");
                    if (args.length == 0) {
                        System.out.println(greeting.greet("world"));
                        return;
                    }
                    for (var name : args) System.out.println(greeting.greet(name));
                }
                """.formatted(library, library);
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
                `vendor/` is the dependency list: whatever is in it, the project
                is built against.
                """.formatted(name, library, library);
    }
}
