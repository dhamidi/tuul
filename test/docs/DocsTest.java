package docs;

import application.Message;
import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import json.Json;
import symbols.Catalog;
import symbols.Document;
import symbols.TypeInfo;

/// `tuul docs` as the application behind it, driven by the messages the command
/// line builds.
public final class DocsTest {

    private DocsTest() {}

    public static void run() throws IOException {
        var root = Path.of("fixture");
        lookups(root);
        members(root);
        recursive(root);
        asJson(root);
        search(root);
        documents(root);
    }

    /// An entrypoint is not part of the symbol catalog. A platform symbol is.
    private static void lookups(Path root) throws IOException {
        var answer = ask(root, Message.of("docs.query").with("symbol", "greeting.Greeter"));
        Check.equal("a project symbol is answered", 0, answer.exit());
        Check.that("the symbol is described", answer.out().contains("class greeting.Greeter"));

        var jdk = ask(root, Message.of("docs.query").with("symbol", "java.lang.String"));
        Check.equal("a platform symbol is answered", 0, jdk.exit());
        Check.that("the platform symbol is described", jdk.out().contains("java.lang.String"));

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

    private static void search(Path root) throws IOException {
        var text = ask(root, Message.of("docs.query").with("search", "Greeter"));
        Check.that("text search identifies a dependency origin when it is useful",
                text.out().contains("greeting.Greeter  [example:greeting:1.0]"));
        Check.equal("a search that answers before five seconds stays quiet", "", text.err());

        var delay = new AtomicReference<Duration>();
        var waiting = ask(root, Message.of("docs.query").with("search", "Greeter"), (duration, output) -> {
            delay.set(duration);
            output.write();
            return () -> {};
        });
        Check.equal("the indexing notice waits for five seconds of inactivity", Duration.ofSeconds(5), delay.get());
        Check.equal("an inactive search reports that it is indexing", "indexing...\n", waiting.err());

        var json = ask(root, Message.of("docs.query").with("search", "Greeter").with("json", true));
        var matches = Json.parse(json.out()) instanceof Json.Object response
                ? response.list("matches") : List.<Json>of();
        Check.that("search JSON carries the symbol, origin, and source location",
                !matches.isEmpty() && matches.getFirst() instanceof Json.Object found
                        && found.string("symbol", "").equals("greeting.Greeter")
                        && found.string("origin", "").equals("example:greeting:1.0")
                        && found.string("source", "").endsWith("greeting-1.0-sources.jar!/greeting/Greeter.java"));
    }

    private static void documents(Path root) throws IOException {
        var package_ = ask(root, Message.of("docs.query").with("symbol", "greeting"));
        Check.that("a package lists its documents", package_.out().contains("tutorial  Write a greeting"));

        var document = ask(root, Message.of("docs.query").with("symbol", "greeting/tutorial"));
        Check.equal("a document request answers", 0, document.exit());
        Check.equal("a document request prints Markdown", "# Write a greeting\n\nCreate one greeting.\n", document.out());

        var json = ask(root, Message.of("docs.query")
                .with("symbol", "greeting/tutorial").with("json", true));
        Check.that("a document JSON object carries its package and body",
                Json.parse(json.out()) instanceof Json.Object description
                        && description.string("package", "").equals("greeting")
                        && description.string("doc", "").contains("Create one greeting"));
    }

    /// What one invocation printed and what it exited with.
    private record Answer(int exit, String out, String err) {}

    private static Answer ask(Path root, Message message) throws IOException {
        var out = new StringWriter();
        var err = new StringWriter();
        var state = App.of(State.of(List.of(root), List.of(), Path.of("fixture-index")), out, err,
                (_, _, _) -> catalog())
                .dispatch(message);
        return new Answer(state.exit(), out.toString(), err.toString());
    }

    private static Answer ask(Path root, Message message, App.Inactivity inactivity) throws IOException {
        var out = new StringWriter();
        var err = new StringWriter();
        var state = App.of(State.of(List.of(root), List.of(), Path.of("fixture-index")), out, err,
                (_, _, _) -> catalog(), inactivity)
                .dispatch(message);
        return new Answer(state.exit(), out.toString(), err.toString());
    }

    private static Catalog catalog() {
        var symbols = new LinkedHashMap<String, TypeInfo>();
        symbols.put("greeting", group("greeting", "Greetings, and who they are for.",
                List.of("greeting.formal", "greeting.Greeter", "greeting.Tone")));
        symbols.put("greeting.formal", group("greeting.formal", "", List.of("greeting.formal.Salutation")));
        symbols.put("greeting.Greeter", type("greeting.Greeter", TypeInfo.Kind.CLASS,
                "Says hello to somebody.", List.of(new TypeInfo.Method(
                        "greet", "java.lang.String",
                        List.of(new TypeInfo.Parameter("java.lang.String", "name")),
                        List.of("public"), "Greets one person by name.", List.of(), 0))));
        symbols.put("greeting.Tone", type("greeting.Tone", TypeInfo.Kind.ENUM,
                "What a greeting is written in.", List.of()));
        symbols.put("greeting.formal.Salutation", type("greeting.formal.Salutation", TypeInfo.Kind.RECORD,
                "A greeting for a stranger.", List.of()));
        symbols.put("java.lang.String", type("java.lang.String", TypeInfo.Kind.CLASS,
                "Character strings.", List.of()));
        return new MemoryCatalog(Map.copyOf(symbols));
    }

    private static TypeInfo group(String name, String doc, List<String> nested) {
        return new TypeInfo(name, TypeInfo.Kind.PACKAGE, List.of(), List.of(), "", List.of(), List.of(), nested,
                List.of(), List.of(), doc, List.of(), "", 0);
    }

    private static TypeInfo type(String name, TypeInfo.Kind kind, String doc, List<TypeInfo.Method> methods) {
        return new TypeInfo(name, kind, List.of("public"), List.of(), "", List.of(), List.of(), List.of(),
                methods, List.of(), doc, List.of(), "", 0);
    }

    private record MemoryCatalog(Map<String, TypeInfo> symbols) implements Catalog {

        private static final Document TUTORIAL = new Document(
                "greeting", "tutorial", "", "Write a greeting",
                "# Write a greeting\n\nCreate one greeting.\n", "fixture/greeting/tutorial.md");

        @Override
        public Optional<TypeInfo> lookup(String name) {
            return Optional.ofNullable(symbols.get(name));
        }

        @Override
        public Optional<Document> document(String packageName, String kind, String slug) {
            return packageName.equals("greeting") && kind.equals("tutorial") && slug.isEmpty()
                    ? Optional.of(TUTORIAL) : Optional.empty();
        }

        @Override
        public List<Document> documents(String packageName) {
            return packageName.equals("greeting") ? List.of(TUTORIAL) : List.of();
        }

        @Override
        public List<String> names() {
            return symbols.keySet().stream().toList();
        }

        @Override
        public List<Root> roots() {
            return List.of(new Root("project", "This project", List.of("greeting")));
        }

        @Override
        public List<Match> search(String text, int limit) {
            return List.of(new Match("greeting.Greeter", "CLASS", "public", "Says hello to somebody.",
                    "example:greeting:1.0", "vendor/example/greeting/1.0/greeting-1.0-sources.jar!"
                            + "/greeting/Greeter.java"));
        }
    }
}
