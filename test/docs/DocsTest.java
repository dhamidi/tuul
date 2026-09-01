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
        member(root);
        code(root);
        search(root);
        documents(root);
        readme(root);
        problems(root);
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

    /// A search result names a member as `Type#member`, so that name asks for
    /// it directly and gets the member rather than the whole type.
    private static void member(Path root) throws IOException {
        var greet = ask(root, Message.of("docs.query").with("symbol", "greeting.Greeter#greet"));
        Check.equal("a member is answered", 0, greet.exit());
        Check.that("named the way it was asked", greet.out().startsWith("greeting.Greeter#greet\n"));
        Check.that("with its signature", greet.out().contains("String greet(String name)"));
        Check.that("and the whole of its comment", greet.out().contains("Greets one person by name."));
        Check.that("and nothing about the type's other members", !greet.out().contains("Tone"));

        var json = ask(root, Message.of("docs.query").with("symbol", "greeting.Greeter#greet").with("json", true));
        Check.that("the JSON names the member and keeps only it",
                Json.parse(json.out()) instanceof Json.Object described
                        && described.string("member", "").equals("greet")
                        && described.list("methods").size() == 1);

        var nobody = ask(root, Message.of("docs.query").with("symbol", "greeting.Greeter#wave"));
        Check.equal("a member the type does not have is unknown", 1, nobody.exit());
        Check.that("and says which name was asked", nobody.err().contains("greeting.Greeter#wave"));
    }

    /// `--code` reads the declaration itself: a type's file, a member's lines.
    private static void code(Path root) throws IOException {
        var type = ask(root, Message.of("docs.query").with("symbol", "greeting.Greeter").with("code", true));
        Check.equal("the source of a type is answered", 0, type.exit());
        Check.that("as the file it is written in, imports and all", type.out().startsWith("package greeting;"));

        var member = ask(root, Message.of("docs.query").with("symbol", "greeting.Greeter#greet").with("code", true));
        Check.equal("the source of a member is answered", 0, member.exit());
        Check.that("with the comment above it", member.out().contains("/// Greets one person by name."));
        Check.that("and its body", member.out().contains("return \"hello \" + name;"));
        Check.that("and nothing else from the file", !member.out().contains("package greeting;"));

        var document = ask(root, Message.of("docs.query").with("symbol", "greeting/tutorial").with("code", true));
        Check.equal("the source of a document is its Markdown", "# Write a greeting\n\nCreate one greeting.\n",
                document.out());

        var none = ask(root, Message.of("docs.query").with("symbol", "java.lang.String").with("code", true));
        Check.equal("a symbol with no source says so instead of printing nothing", 1, none.exit());
        Check.that("naming the symbol", none.err().contains("no source for java.lang.String"));
    }

    private static void search(Path root) throws IOException {
        var text = ask(root, Message.of("docs.query").with("search", "Greeter"));
        Check.that("results are grouped under the name they share", text.out().startsWith("greeting\n"));
        Check.that("text search identifies a dependency origin when it is useful",
                text.out().contains("  greeting.Greeter  [example:greeting:1.0]"));
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
        var groups = Json.parse(json.out()) instanceof Json.Object response ? response.list("groups") : List.<Json>of();
        Check.that("search JSON groups its matches by what they share",
                groups.size() == 1 && groups.getFirst() instanceof Json.Object group
                        && group.string("prefix", "").equals("greeting"));
        var matches = groups.getFirst() instanceof Json.Object group ? group.list("matches") : List.<Json>of();
        Check.that("and each match carries the symbol, origin, and source location",
                !matches.isEmpty() && matches.getFirst() instanceof Json.Object found
                        && found.string("symbol", "").equals("greeting.Greeter")
                        && found.string("origin", "").equals("example:greeting:1.0")
                        && found.string("source", "").endsWith("greeting-1.0-sources.jar!/greeting/Greeter.java"));
        Check.that("and says that every word was held",
                Json.parse(json.out()) instanceof Json.Object response && response.flag("every"));

        var some = ask(root, Message.of("docs.query").with("search", "greeter kubernetes"));
        Check.equal("a search no symbol holds every word of still answers", 0, some.exit());
        Check.that("with the symbols that hold some of them", some.out().contains("greeting.Greeter"));
        Check.that("and says so, after the answer",
                some.err().contains("nothing holds every word of \"greeter kubernetes\""));
        var someJson = ask(root, Message.of("docs.query").with("search", "greeter kubernetes").with("json", true));
        Check.that("which the JSON says with a flag",
                Json.parse(someJson.out()) instanceof Json.Object response && !response.flag("every"));

        var nothing = ask(root, Message.of("docs.query").with("search", "kubernetes"));
        Check.equal("a search nothing holds any word of fails", 1, nothing.exit());
        Check.that("and says so", nothing.err().contains("nothing matches kubernetes"));
    }

    private static void documents(Path root) throws IOException {
        var package_ = ask(root, Message.of("docs.query").with("symbol", "greeting"));
        Check.that("a package lists its documents by the name that asks for them",
                package_.out().contains("  greeting/tutorial  Write a greeting"));

        var document = ask(root, Message.of("docs.query").with("symbol", "greeting/tutorial"));
        Check.equal("a document request answers", 0, document.exit());
        Check.equal("a document request prints Markdown", "# Write a greeting\n\nCreate one greeting.\n", document.out());

        var json = ask(root, Message.of("docs.query")
                .with("symbol", "greeting/tutorial").with("json", true));
        Check.that("a document JSON object carries its package and body",
                Json.parse(json.out()) instanceof Json.Object description
                        && description.string("package", "").equals("greeting")
                        && description.string("doc", "").contains("Create one greeting"));

        var listing = ask(root, Message.of("docs.query").with("symbol", "greeting/howto"));
        Check.equal("a kind with no introduction lists what it has", 0, listing.exit());
        Check.equal("one name per line, ready to ask for", "greeting/howto/shout  Shout\n", listing.out());

        var all = ask(root, Message.of("docs.query").with("symbol", "greeting").with("documents", true));
        Check.equal("--documents answers", 0, all.exit());
        Check.that("with the package first", all.out().startsWith("package greeting"));
        Check.that("then every document in full, each under the name that asks for it",
                all.out().contains("--- greeting/tutorial (fixture/greeting/tutorial.md) ---\n# Write a greeting"));
        Check.that("all of them", all.out().contains("--- greeting/howto/shout") && all.out().contains("Louder."));

        var allJson = ask(root, Message.of("docs.query").with("symbol", "greeting").with("documents", true)
                .with("json", true));
        Check.that("the JSON carries each document's body",
                Json.parse(allJson.out()) instanceof Json.Object described
                        && described.list("documents").stream().allMatch(value ->
                                value instanceof Json.Object entry && entry.get("doc") instanceof Json.Str));
    }

    /// A package's README is a document like the others, filed first.
    private static void readme(Path root) throws IOException {
        var package_ = ask(root, Message.of("docs.query").with("symbol", "greeting.formal"));
        Check.that("a package with no package-info says what it is for from its README",
                package_.out().contains("How to address a stranger."));
        Check.that("and lists the README by name", package_.out().contains("  greeting.formal/readme  Formal greetings"));

        var readme = ask(root, Message.of("docs.query").with("symbol", "greeting.formal/readme"));
        Check.equal("the README is answered by that name", 0, readme.exit());
        Check.that("as the Markdown it is", readme.out().startsWith("# Formal greetings\n"));
    }

    /// A project that does not compile is answered from the last index that
    /// did, and the reader is told.
    private static void problems(Path root) throws IOException {
        var answer = ask(root, Message.of("docs.query").with("symbol", "greeting.Greeter"),
                (_, _, _) -> new Broken(catalog()));
        Check.equal("the answer is still an answer", 0, answer.exit());
        Check.that("from the last good index", answer.out().contains("class greeting.Greeter"));
        Check.that("and the reader is warned", answer.err().contains("warning: the project does not compile"));
        Check.that("with javac's own words", answer.err().contains("Greeter.java:3 ';' expected"));
    }

    /// What one invocation printed and what it exited with.
    private record Answer(int exit, String out, String err) {}

    private static Answer ask(Path root, Message message) throws IOException {
        return ask(root, message, (_, _, _) -> catalog());
    }

    private static Answer ask(Path root, Message message, symbols.CatalogFactory catalogs) throws IOException {
        var out = new StringWriter();
        var err = new StringWriter();
        var state = App.of(State.of(List.of(root), List.of(), Path.of("fixture-index")), out, err, catalogs)
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

    private static final String GREETER = """
            package greeting;

            /// Says hello to somebody.
            public final class Greeter {

                /// Greets one person by name.
                public String greet(String name) {
                    return "hello " + name;
                }
            }
            """;

    private static Catalog catalog() {
        var symbols = new LinkedHashMap<String, TypeInfo>();
        symbols.put("greeting", group("greeting", "Greetings, and who they are for.",
                List.of("greeting.formal", "greeting.Greeter", "greeting.Tone")));
        symbols.put("greeting.formal", group("greeting.formal", "", List.of("greeting.formal.Salutation")));
        symbols.put("greeting.Greeter", type("greeting.Greeter", TypeInfo.Kind.CLASS,
                "Says hello to somebody.", List.of(new TypeInfo.Method(
                        "greet", "java.lang.String",
                        List.of(new TypeInfo.Parameter("java.lang.String", "name")),
                        List.of("public"), "Greets one person by name.", List.of(), 7)))
                .at("fixture/greeting/Greeter.java"));
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

    /// The catalog of a project whose sources stopped compiling: the same
    /// answers, and a problem to go with them.
    private record Broken(Catalog last) implements Catalog {

        @Override
        public Optional<TypeInfo> lookup(String name) {
            return last.lookup(name);
        }

        @Override
        public List<String> names() {
            return last.names();
        }

        @Override
        public List<Root> roots() {
            return last.roots();
        }

        @Override
        public List<Match> search(String text, int limit) {
            return last.search(text, limit);
        }

        @Override
        public List<String> problems() {
            return List.of("the project does not compile, so answers about it come from the last build that did:\n"
                    + "  fixture/greeting/Greeter.java:3 ';' expected");
        }
    }

    private record MemoryCatalog(Map<String, TypeInfo> symbols) implements Catalog {

        private static final Document TUTORIAL = new Document(
                "greeting", "tutorial", "", "Write a greeting",
                "# Write a greeting\n\nCreate one greeting.\n", "fixture/greeting/tutorial.md");

        private static final Document SHOUT = new Document(
                "greeting", "howto", "shout", "Shout",
                "# Shout\n\nLouder.\n", "fixture/greeting/howto-shout.md");

        private static final Document README = new Document(
                "greeting.formal", Document.README, "", "Formal greetings",
                "# Formal greetings\n\nHow to address a stranger.\n\nMore below.\n", "fixture/greeting/formal/README.md");

        @Override
        public Optional<TypeInfo> lookup(String name) {
            return Optional.ofNullable(symbols.get(name));
        }

        @Override
        public List<Document> documents(String packageName) {
            return switch (packageName) {
                case "greeting" -> List.of(TUTORIAL, SHOUT);
                case "greeting.formal" -> List.of(README);
                default -> List.of();
            };
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
        public Optional<String> source(String location) {
            return location.equals("fixture/greeting/Greeter.java") ? Optional.of(GREETER) : Optional.empty();
        }

        /// Every word, or nothing: `kubernetes` is a word no symbol here holds.
        @Override
        public List<Match> search(String text, int limit) {
            return text.toLowerCase(java.util.Locale.ROOT).contains("kubernetes") ? List.of() : greeter();
        }

        @Override
        public List<Match> searchAny(String text, int limit) {
            return text.toLowerCase(java.util.Locale.ROOT).contains("greeter") ? greeter() : List.of();
        }

        private static List<Match> greeter() {
            return List.of(new Match("greeting.Greeter", "CLASS", "public", "Says hello to somebody.",
                    "example:greeting:1.0", "vendor/example/greeting/1.0/greeting-1.0-sources.jar!"
                            + "/greeting/Greeter.java"));
        }
    }
}
