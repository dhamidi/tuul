package docs;

import application.Message;
import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import json.Json;

/// `tuul docs` as the application behind it, driven by the messages the command
/// line builds.
public final class DocsTest {

    private DocsTest() {}

    public static void run() throws IOException {
        var root = sources();
        entrypoints(root);
        members(root);
        recursive(root);
        asJson(root);
    }

    /// A project may have two entrypoints. Every `main.java` compiles to the
    /// same implicitly declared class `main`, so two of them made javac stop
    /// with `duplicate class: main` — and one broken compile made every
    /// question fail, including questions about the JDK.
    private static void entrypoints(Path root) throws IOException {
        var answer = ask(root, Message.of("docs.query").with("symbol", "greeting.Greeter"));
        Check.equal("two entrypoints do not stop tuul docs", 0, answer.exit());
        Check.that("and the symbol is described", answer.out().contains("class greeting.Greeter"));

        var jdk = ask(root, Message.of("docs.query").with("symbol", "java.lang.String"));
        Check.equal("a question about the JDK survives a second entrypoint too", 0, jdk.exit());
        Check.that("and is answered", jdk.out().contains("java.lang.String"));

        var entrypoint = ask(root, Message.of("docs.query").with("symbol", "main"));
        Check.equal("an entrypoint is no symbol, so asking about it is an honest no", 1, entrypoint.exit());
        Check.that("and it says so", entrypoint.err().contains("unknown symbol: main"));
    }

    /// Reading a package took a question per type in it. `--members` asks once.
    private static void members(Path root) throws IOException {
        var bare = ask(root, Message.of("docs.query").with("symbol", "greeting"));
        Check.that("a package on its own lists the names it holds", bare.out().contains("greeting.Greeter"));
        Check.that("and says what the package is for", bare.out().contains("Greetings, and who they are for."));
        Check.that("but says nothing about what a type in it is for",
                !bare.out().contains("Says hello to somebody."));

        var members = ask(root, Message.of("docs.query").with("symbol", "greeting").with("members", true));
        Check.equal("asking for the members answers", 0, members.exit());
        Check.that("the package is still described", members.out().contains("package greeting"));
        Check.that("and every type it holds is described with it",
                members.out().contains("Says hello to somebody."));
        Check.that("all of them", members.out().contains("What a greeting is written in."));
        Check.that("with their members, which is what a reader came for",
                members.out().contains("String greet(String name)"));
        Check.that("a subpackage is left out, because the question was about this package",
                !members.out().contains("greeting.formal.Salutation"));
    }

    private static void recursive(Path root) throws IOException {
        var deep = ask(root, Message.of("docs.query").with("symbol", "greeting").with("recursive", true));
        Check.that("--recursive goes into the subpackages", deep.out().contains("package greeting.formal"));
        Check.that("and describes what they hold", deep.out().contains("A greeting for a stranger."));
        Check.that("without losing the package that was asked about",
                deep.out().contains("Says hello to somebody."));
    }

    private static void asJson(Path root) throws IOException {
        var answer = ask(root, Message.of("docs.query")
                .with("symbol", "greeting").with("members", true).with("json", true));
        Check.that("the JSON says which symbol it is about",
                Json.parse(answer.out()) instanceof Json.Object described
                        && described.string("class", "").equals("greeting"));
        Check.equal("and carries one description per member",
                2,
                Json.parse(answer.out()) instanceof Json.Object described ? described.list("members").size() : 0);
    }

    /// What one invocation printed and what it exited with.
    private record Answer(int exit, String out, String err) {}

    /// The index goes in a directory of its own: the one under `build/` belongs
    /// to the project rather than to a test.
    private static Answer ask(Path root, Message message) throws IOException {
        var out = new StringWriter();
        var err = new StringWriter();
        var kept = Files.createTempDirectory("tuul-docs-index");
        kept.toFile().deleteOnExit();
        var state = App.of(State.of(List.of(root), List.of(), kept.resolve("index.db")), out, err)
                .dispatch(message);
        return new Answer(state.exit(), out.toString(), err.toString());
    }

    /// A project with two entrypoints, a package, and a package under it.
    private static Path sources() throws IOException {
        var root = Files.createTempDirectory("tuul-docs");
        root.toFile().deleteOnExit();
        Files.writeString(Files.createDirectories(root.resolve("cli")).resolve("main.java"), """
                /// The command line.
                void main(String[] args) {
                    java.lang.System.out.println("cli");
                }
                """);
        Files.writeString(Files.createDirectories(root.resolve("serve")).resolve("main.java"), """
                /// The server.
                void main(String[] args) {
                    java.lang.System.out.println("serve");
                }
                """);
        var greeting = Files.createDirectories(root.resolve("greeting"));
        Files.writeString(greeting.resolve("package-info.java"), """
                /** Greetings, and who they are for. */
                package greeting;
                """);
        Files.writeString(greeting.resolve("Greeter.java"), """
                package greeting;

                /** Says hello to somebody. */
                public final class Greeter {

                    /** Greets one person by name. */
                    public String greet(String name) {
                        return "hi " + name;
                    }
                }
                """);
        Files.writeString(greeting.resolve("Tone.java"), """
                package greeting;

                /** What a greeting is written in. */
                public enum Tone {

                    /** For somebody you know. */
                    WARM,

                    /** For somebody you do not. */
                    PLAIN
                }
                """);
        var formal = Files.createDirectories(greeting.resolve("formal"));
        Files.writeString(formal.resolve("Salutation.java"), """
                package greeting.formal;

                /** A greeting for a stranger. */
                public record Salutation(String text) {}
                """);
        return root;
    }
}
