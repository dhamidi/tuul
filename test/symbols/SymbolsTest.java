package symbols;

import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import compiler.Compiler;

public final class SymbolsTest {

    private SymbolsTest() {}

    public static void run() throws IOException {
        documentNames();
        controlledCompilation();
        joinsCompilation();
        markdownDoesNotCompile();
    }

    private static void documentNames() {
        Check.equal("an explanation filename names a guide",
                new Document.Name("guide", "", "explanation.md"),
                Document.name(Path.of("explanation.md")).orElseThrow());
        Check.equal("an order prefix is not part of a document slug", "first-script",
                Document.name(Path.of("tutorial-01-first-script.md")).orElseThrow().slug());
        Check.that("an unrelated or uppercase Markdown file is not a package document",
                Document.name(Path.of("README.md")).isEmpty() && Document.name(Path.of("Tutorial.md")).isEmpty());
        Check.equal("line 1 supplies a document title", "First script",
                Document.title("# First script\n\nStart here.\n", "tutorial", "first-script"));
        Check.equal("a slug supplies a missing title", "first script",
                Document.title("Start here.\n", "tutorial", "first-script"));
        var document = new Document("sample", "tutorial", "", "First script",
                "# First script\n\n## Start here\n\nText.\n\n### Detail\n", "src/sample/tutorial.md");
        Check.equal("a document outline contains its level-two sections",
                List.of(new Document.Section("Start here", "start-here")), document.sections());
        Check.that("a document title is not part of its rendered content",
                document.content().startsWith("\n## Start here"));
    }

    public static void integration() throws IOException {
        try (var index = Index.of(List.of(sources()), List.of(), kept())) {
            project(index);
            platform(index);
            javadoc(index);
            platformJavadoc(index);
            packages(index);
            documents(index);
            located(index);
            references(index);
        roots(index);
            rendering(index);
        }
        vendored();
        dependencySearch();
        remembers();
        documentCollisions();
        documentStamp();
        entrypoints();
    }

    /// A caller can control project compilation without replacing dependency
    /// or platform lookup.
    private static void controlledCompilation() throws IOException {
        var root = Files.createTempDirectory("tuul-controlled-symbols");
        var source = Files.createDirectories(root.resolve("symbols"));
        Files.writeString(source.resolve("Fixture.java"), "package symbols; final class Fixture {}");
        Files.writeString(source.resolve("tutorial-01-first.md"), "# First document\n\nStart with one.\n");
        Files.writeString(source.resolve("README.md"), "# Not a package document\n");
        Files.writeString(root.resolve("main.java"), "void main() {}");
        var calls = new AtomicInteger();
        Compiler compiler = (request, classes) -> {
            calls.incrementAndGet();
            Check.equal("the symbol compiler does not receive an entrypoint",
                    List.of("Fixture.java"), request.sources().stream().map(path -> path.getFileName().toString()).toList());
            try (var in = SymbolsTest.class.getResourceAsStream("SymbolsTest$Fixture.class");
                    var out = classes.open("symbols.SymbolsTest$Fixture")) {
                in.transferTo(out);
            }
            return new Compiler.Result(1, List.of());
        };
        try (var index = Index.of(List.of(root), List.of(), compiler, new MemoryStore())) {
            Check.that("an injected compiler supplies project classes",
                    index.lookup("symbols.SymbolsTest.Fixture").isPresent());
            Check.that("the catalog compiles its project once", index.names().contains("symbols.SymbolsTest$Fixture"));
            Check.equal("the injected compiler ran once", 1, calls.get());
            Check.equal("the index discovers only package documents", List.of("first"),
                    index.documents("symbols").stream().map(Document::slug).toList());
        }
    }

    /// Two synchronous callers for one fingerprint wait for the same javac job.
    private static void joinsCompilation() throws IOException {
        var root = Files.createTempDirectory("tuul-joined-symbols");
        var source = Files.createDirectories(root.resolve("symbols"));
        Files.writeString(source.resolve("Fixture.java"), "package symbols; final class Fixture {}");
        var indexFile = root.resolve("build/index.db");
        var calls = new AtomicInteger();
        var compiling = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Compiler compiler = (request, classes) -> {
            calls.incrementAndGet();
            compiling.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("the joined compile was not released");
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new IOException("the joined compile was interrupted", stopped);
            }
            try (var in = SymbolsTest.class.getResourceAsStream("SymbolsTest$Fixture.class");
                    var out = classes.open("symbols.SymbolsTest$Fixture")) {
                in.transferTo(out);
            }
            return new Compiler.Result(1, List.of());
        };
        try (var first = Index.of(List.of(root), List.of(), indexFile, compiler);
                var second = Index.of(List.of(root), List.of(), indexFile, compiler)) {
            var one = Thread.startVirtualThread(first::ensureCurrent);
            try {
                Check.that("the first indexing job starts", compiling.await(5, TimeUnit.SECONDS));
                var two = Thread.startVirtualThread(second::ensureCurrent);
                release.countDown();
                one.join();
                two.join();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new IOException("waiting for joined indexing was interrupted", stopped);
            }
            Check.equal("concurrent callers compile one fingerprint once", 1, calls.get());
        }
    }

    /// Package prose has its own fingerprint and publication path.
    private static void markdownDoesNotCompile() throws IOException {
        var root = Files.createTempDirectory("tuul-markdown-symbols");
        var source = Files.createDirectories(root.resolve("symbols"));
        Files.writeString(source.resolve("Fixture.java"), "package symbols; final class Fixture {}");
        var guide = source.resolve("guide.md");
        Files.writeString(guide, "# First\n\nOne.\n");
        var indexFile = root.resolve("build/index.db");
        var calls = new AtomicInteger();
        Compiler compiler = (request, classes) -> {
            calls.incrementAndGet();
            try (var in = SymbolsTest.class.getResourceAsStream("SymbolsTest$Fixture.class");
                    var out = classes.open("symbols.SymbolsTest$Fixture")) {
                in.transferTo(out);
            }
            return new Compiler.Result(1, List.of());
        };
        try (var first = Index.of(List.of(root), List.of(), indexFile, compiler)) {
            first.ensureCurrent();
        }
        Files.writeString(guide, "# Second\n\nTwo, changed.\n");
        try (var second = Index.of(List.of(root), List.of(), indexFile, compiler)) {
            second.ensureCurrent();
            Check.that("a Markdown edit publishes the new document",
                    second.document("symbols", "guide", "").orElseThrow().body().contains("changed"));
        }
        Check.equal("a Markdown-only refresh does not call javac", 1, calls.get());
    }

    /// A project may have more than one entrypoint, and asking about it still
    /// works.
    ///
    /// Every `main.java` compiles to the same implicitly declared class `main`,
    /// so two of them made javac stop with `duplicate class: main` and every
    /// question about the project — and about the JDK, which shares the
    /// compile — failed with it.
    private static void entrypoints() throws IOException {
        var root = Files.createTempDirectory("tuul-entrypoints");
        root.toFile().deleteOnExit();
        var cli = Files.createDirectories(root.resolve("cli"));
        Files.writeString(cli.resolve("main.java"), """
                /// The command line.
                void main(String[] args) {
                    java.lang.System.out.println("cli");
                }
                """);
        var serve = Files.createDirectories(root.resolve("serve"));
        Files.writeString(serve.resolve("main.java"), """
                /// The server.
                void main(String[] args) {
                    java.lang.System.out.println("serve");
                }
                """);
        var lib = Files.createDirectories(root.resolve("greeting"));
        Files.writeString(lib.resolve("Greeter.java"), """
                package greeting;

                /** Says hello. */
                public final class Greeter {

                    /** Greets somebody by name. */
                    public String greet(String name) {
                        return "hi " + name;
                    }
                }
                """);
        try (var index = Index.of(List.of(root), List.of(), kept())) {
            Check.equal("two entrypoints do not stop the index",
                    "greeting.Greeter", index.lookup("greeting.Greeter").orElseThrow().name());
            Check.that("a question about the JDK survives a second entrypoint too",
                    index.lookup("java.lang.String").isPresent());
            Check.that("an entrypoint is not a symbol", index.lookup("main").isEmpty());
            Check.that("and it is not in the listing either",
                    index.names().stream().noneMatch(name -> name.equals("main")));
        }
    }

    /// An index of its own for every test, in a directory that goes away with
    /// it — the one in `build/` belongs to the project, not to the tests.
    private static Path kept() throws IOException {
        var directory = Files.createTempDirectory("tuul-index");
        directory.toFile().deleteOnExit();
        return directory.resolve("index.db");
    }

    private static void project(Index index) {
        var invoice = index.lookup("invoicing.Invoice").orElseThrow();
        Check.equal("a record is a record", TypeInfo.Kind.RECORD, invoice.kind());
        Check.equal("declarations read like source", "record invoicing.Invoice", invoice.declaration());
        Check.equal("interfaces keep their type arguments",
                List.of("java.lang.Comparable<invoicing.Invoice>"),
                invoice.interfaces());
        Check.equal("parameters keep their names",
                "int compareTo(invoicing.Invoice other)",
                method(invoice, "compareTo").signature());
        Check.that("compiler-generated bridges are hidden",
                invoice.methods().stream()
                        .filter(method -> method.name().equals("compareTo"))
                        .noneMatch(method -> method.parameters().getFirst().type().equals("java.lang.Object")));
        Check.that("nested types are found", index.lookup("invoicing.Invoice.Kind").isPresent());
        Check.equal("a type says what it declares, which is otherwise invisible",
                List.of("invoicing.Invoice.Kind", "invoicing.Invoice.State"),
                invoice.nested().stream().sorted().toList());

        var state = index.lookup("invoicing.Invoice.State").orElseThrow();
        Check.equal("a sealed type says what its cases are — the only thing on the page of an "
                        + "interface that declares no methods",
                List.of("invoicing.Invoice.State.Owing", "invoicing.Invoice.State.Paid"),
                state.permits().stream().sorted().toList());
        Check.that("and does not name them a second time as nested types", state.nested().isEmpty());
        Check.that("everything compiled is indexed", index.names().contains("invoicing.Invoice"));
    }

    private static void documents(Index index) {
        Check.equal("a package keeps its documents in filename order",
                List.of("guide", "tutorial"),
                index.documents("invoicing").stream().map(Document::kind).toList());
        Check.equal("an ordered filename loses its order prefix", "reasons",
                index.document("invoicing", "guide", "reasons").orElseThrow().slug());
        Check.equal("a document keeps its Markdown body", "# First invoice\n\nCreate a fixed invoice.\n",
                index.document("invoicing", "tutorial", "").orElseThrow().body());
        Check.that("document text participates in full-text search",
                index.search("design reasons", 10).stream()
                        .anyMatch(match -> match.symbol().equals("invoicing/guide/reasons")));
    }

    private static void documentCollisions() throws IOException {
        var root = Files.createTempDirectory("tuul-document-collision");
        var source = Files.createDirectories(root.resolve("collision"));
        Files.writeString(source.resolve("One.java"), "package collision; public final class One {}");
        Files.writeString(source.resolve("guide.md"), "# Guide\n");
        Files.writeString(source.resolve("explanation.md"), "# Explanation\n");
        try (var index = Index.of(List.of(root), List.of(), kept())) {
            index.documents("collision");
            Check.that("two document files cannot normalize to one identity", false);
        } catch (IllegalStateException collision) {
            Check.that("a document collision names both source files",
                    collision.getMessage().contains("guide.md") && collision.getMessage().contains("explanation.md"));
        }
    }

    private static void documentStamp() throws IOException {
        var root = Files.createTempDirectory("tuul-document-stamp");
        var source = Files.createDirectories(root.resolve("stamped"));
        Files.writeString(source.resolve("One.java"), "package stamped; public final class One {}");
        var tutorial = source.resolve("tutorial.md");
        Files.writeString(tutorial, "# First\n\nOne.\n");
        var store = kept();
        try (var index = Index.of(List.of(root), List.of(), store)) {
            Check.that("the first document body is indexed",
                    index.document("stamped", "tutorial", "").orElseThrow().body().contains("One."));
        }
        Files.writeString(tutorial, "# First\n\nA different body.\n");
        try (var index = Index.of(List.of(root), List.of(), store)) {
            Check.that("a Markdown edit invalidates the project origin",
                    index.document("stamped", "tutorial", "").orElseThrow().body().contains("different body"));
        }
    }

    /// The JDK is part of the index — no source needed, the class files are
    /// right there in `jrt:/`.
    private static void platform(Index index) {
        var string = index.lookup("java.lang.String").orElseThrow();
        Check.that("the JDK is indexed too", string.interfaces().contains("java.lang.Comparable<java.lang.String>"));
        Check.that("an unknown symbol is absent", index.lookup("nothing.At.All").isEmpty());
    }

    private static void javadoc(Index index) {
        var invoice = index.lookup("invoicing.Invoice").orElseThrow();
        Check.equal("a type carries its doc comment, paragraphs and all",
                "An invoice for a fixed amount, identified by id.\n\nInvoices are compared by amount.",
                invoice.doc());
        Check.equal("so does a method", "Orders invoices by amount, then id. See Invoice.Kind.",
                method(invoice, "compareTo").doc());
        Check.equal("markdown doc comments are read too, brackets and all",
                "The customer this invoice is for.\n\nNot [Invoice.Kind], and not [Nowhere] either.",
                method(invoice, "customer").doc());
        // `[Invoice.Kind]` in a `///` comment is javadoc's reference link, and
        // javac hands it over parsed rather than as the text it was written as.
        // Writing back what was parsed dropped the brackets, which left the
        // word in the prose and no way for anything downstream to tell it had
        // ever been a cross-reference — see the pair above and [References].
        // `{@link}` keeps its old shape, because nobody typed a bracket there.
        Check.that("a markdown reference keeps the syntax that makes it one",
                method(invoice, "customer").doc().contains("[Invoice.Kind]"));
        Check.that("and an inline tag does not grow one it never had",
                !method(invoice, "compareTo").doc().contains("["));

        var kind = index.lookup("invoicing.Invoice.Kind").orElseThrow();
        Check.equal("a nested type finds its comment in the enclosing file", "What stage an invoice is at.", kind.doc());
        Check.equal("a field's comment lands on the field",
                "Nothing has been sent yet.",
                kind.fields().stream().filter(field -> field.name().equals("DRAFT")).findFirst().orElseThrow().doc());
        Check.equal("an undocumented member says so", "", method(invoice, "id").doc());
        Check.equal("block tags come along with the prose",
                List.of("@param other the invoice to compare with", "@return a negative number, zero, or a positive number"),
                method(invoice, "compareTo").tags().stream().map(TypeInfo.Tag::line).toList());
        Check.equal("a tag with no name keeps its text",
                new TypeInfo.Tag("since", "", "1.0"),
                invoice.tags().getFirst());
    }

    /// Class files carry no comments, so the JDK's own documentation comes out
    /// of `lib/src.zip` — found through the module that `jrt:/` reports.
    private static void platformJavadoc(Index index) {
        var string = index.lookup("java.lang.String").orElseThrow();
        Check.that("the JDK's types are documented",
                string.doc().startsWith("The String class represents character strings."));
        Check.that("so are its methods",
                method(string, "length").doc().startsWith("Returns the length of this string."));
        Check.that("markup is flattened to text, not left as tags",
                !string.doc().contains("{@") && !string.doc().contains("<p>"));
        Check.that("HTML paragraphs stay paragraphs, so the JDK's prose is not one wall of text",
                string.doc().contains("\n\n"));
        Check.that("and a <pre> example keeps the layout somebody wrote it with",
                string.doc().contains("\n    String str = \"abc\";"));
        Check.that("@see carries what it points at, rather than pointing nowhere",
                string.tags().stream()
                        .filter(tag -> tag.tag().equals("see"))
                        .anyMatch(tag -> tag.text().contains("java.lang.StringBuffer")));

        Check.that("overloads are matched by parameter type, not by name",
                overload(string, "valueOf", "char").doc().startsWith("Returns the string representation of the char argument."));
        Check.that("even when the overloads have the same arity",
                overload(string, "valueOf", "int").doc().startsWith("Returns the string representation of the int argument."));

        Check.equal("parameter names are recovered from the source, since the JDK ships none",
                "java.lang.String substring(int beginIndex, int endIndex)",
                string.methods().stream()
                        .filter(method -> method.name().equals("substring"))
                        .filter(method -> method.parameters().size() == 2)
                        .findFirst()
                        .orElseThrow()
                        .signature());

        var entry = index.lookup("java.util.Map.Entry").orElseThrow();
        Check.that("a nested JDK type is documented from its enclosing source",
                entry.doc().startsWith("A map entry (key-value pair)."));
        Check.that("and so are its methods",
                method(entry, "getKey").doc().startsWith("Returns the key corresponding to this entry."));
    }

    /// A tuul project's dependencies are the jars in `vendor/` — nothing else
    /// records them. Symbols come out of the binary jar, documentation out of
    /// the sources jar beside it, and the project's own code compiles against
    /// both.
    private static void vendored() throws IOException {
        var project = Files.createTempDirectory("tuul-vendor");
        project.toFile().deleteOnExit();
        var source = source(project);
        var vendor = vendor(project);

        var index = Index.of(List.of(source), List.of(vendor), kept());
        Check.that("project sources compile against vendored jars", index.lookup("using.Uses").isPresent());

        var greeter = index.lookup("greeting.Greeter").orElseThrow();
        Check.equal("a vendored type is a symbol like any other",
                "java.lang.String greet(java.lang.String name)",
                method(greeter, "greet").signature());
        Check.that("documented from the sources jar beside it",
                greeter.doc().startsWith("Greets people by name."));
        Check.equal("block tags and all",
                List.of("@param name who to greet", "@return the greeting", "@throws IllegalArgumentException if name is empty"),
                method(greeter, "greet").tags().stream().map(TypeInfo.Tag::line).toList());
        Check.equal("a vendored field is documented too",
                "How the greeting starts.",
                greeter.fields().stream().filter(field -> field.name().equals("PREFIX")).findFirst().orElseThrow().doc());
        Check.that("a nested type inside a jar is found",
                index.lookup("greeting.Greeter.Style").isPresent());

        var plain = index.lookup("plain.Widget").orElseThrow();
        Check.equal("a jar with no sources still answers", "plain.Widget", plain.name());
        Check.equal("it just has nothing to say about itself", "", plain.doc());

        Check.that("the project still wins over a dependency of the same name",
                index.lookup("using.Uses").orElseThrow().name().equals("using.Uses"));
        index.close();
    }

    /// A search starts from a cold persistent index. It must not depend on an
    /// earlier exact lookup to discover a selected dependency.
    private static void dependencySearch() throws IOException {
        var project = Files.createTempDirectory("tuul-vendor-search");
        project.toFile().deleteOnExit();
        var vendor = vendor(project);
        var indexFile = project.resolve("build/index.db");

        try (var index = Index.of(List.of(), List.of(vendor), indexFile)) {
            Check.that("exact dependency lookup works before search",
                    index.lookup("plain.Widget").isPresent());
            var result = index.search("Greeter", 10).stream()
                    .filter(match -> match.symbol().equals("greeting.Greeter"))
                    .findFirst().orElseThrow();
            Check.equal("a cold search finds a dependency type", TypeInfo.Kind.CLASS.name(), result.kind());
            Check.equal("a dependency result names its owning artifact",
                    "unmanaged:greeting-1.0.jar", result.origin());
            Check.that("a dependency result locates its source archive",
                    result.source().contains("greeting-1.0-sources.jar"));
            Check.that("exact dependency lookup works after search",
                    index.lookup("greeting.Greeter").orElseThrow().doc().startsWith("Greets people by name."));
        }

        var changed = """
                package greeting;

                /** Greets people after its source archive changes. */
                public class Greeter {
                    public static final String PREFIX = "Hello, ";
                    public enum Style { PLAIN, LOUD }
                    public String greet(String name) { return PREFIX + name; }
                }
                """;
        jar(vendor.resolve("greeting/greeting-1.0-sources.jar"),
                Map.of("greeting/Greeter.java", changed.getBytes()));
        try (var index = Index.of(List.of(), List.of(vendor), indexFile)) {
            var result = index.search("Greeter", 10).stream()
                    .filter(match -> match.symbol().equals("greeting.Greeter"))
                    .findFirst().orElseThrow();
            Check.that("a changed source archive refreshes dependency documentation",
                    result.doc().startsWith("Greets people after its source archive changes."));
        }
    }

    /// What the index is for: the second answer costs nothing, and a source
    /// tree that has moved on says so.
    private static void remembers() throws IOException {
        var kept = kept();
        var root = sources();
        var file = root.resolve("invoicing/Invoice.java");
        var written = Files.readString(file);
        var when = Files.getLastModifiedTime(file);

        try (var index = Index.of(List.of(root), List.of(), kept)) {
            Check.equal("a first lookup indexes the project",
                    "An invoice for a fixed amount, identified by id.\n\nInvoices are compared by amount.",
                    index.lookup("invoicing.Invoice").orElseThrow().doc());
            Check.that("and every type in it, not only the one asked for",
                    index.names().containsAll(List.of("invoicing.Invoice", "invoicing.Invoice$Kind")));
        }

        // The stamp is the path, the size and the modification time of every
        // source. Break the file without changing any of those and the index
        // must still answer — which it can only do without asking javac,
        // because javac would refuse this.
        var broken = "/*".repeat(written.length() / 2) + (written.length() % 2 == 1 ? " " : "");
        Files.writeString(file, broken);
        Files.setLastModifiedTime(file, when);
        Check.equal("the sources are the same size as before", written.length(), broken.length());

        try (var index = Index.of(List.of(root), List.of(), kept)) {
            Check.equal("an unchanged stamp answers from the index, without compiling anything",
                    "invoicing.Invoice", index.lookup("invoicing.Invoice").orElseThrow().name());
            Check.that("even for a name that is not there, which is the answer that used to cost a compile",
                    index.lookup("invoicing.Nothing").isEmpty());
        }

        Files.writeString(file, written + "\n// a comment, and a new size\n");
        try (var index = Index.of(List.of(root), List.of(), kept)) {
            Check.that("a source that has changed is compiled again",
                    index.lookup("invoicing.Invoice").isPresent());
        }

        Files.delete(file);
        try (var index = Index.of(List.of(root), List.of(), kept)) {
            Check.that("and a source that is gone takes its symbols with it",
                    index.lookup("invoicing.Invoice").isEmpty());
        }
    }

    private static Path source(Path project) throws IOException {
        var source = Files.createDirectories(project.resolve("src/using"));
        Files.writeString(source.resolve("Uses.java"), """
                package using;

                import greeting.Greeter;

                public class Uses {

                    public String hello() {
                        return new Greeter().greet("tuul");
                    }
                }
                """);
        return project.resolve("src");
    }

    /// Builds what `tuul add` would leave behind: one directory per artifact,
    /// a binary jar and its sources jar inside.
    private static Path vendor(Path project) throws IOException {
        var greeting = """
                package greeting;

                /**
                 * Greets people by name.
                 *
                 * @since 1.0
                 */
                public class Greeter {

                    /** How the greeting starts. */
                    public static final String PREFIX = "Hello, ";

                    /** How loud to be. */
                    public enum Style { PLAIN, LOUD }

                    /**
                     * Greets someone.
                     *
                     * @param name who to greet
                     * @return the greeting
                     * @throws IllegalArgumentException if {@code name} is empty
                     */
                    public String greet(String name) {
                        if (name.isEmpty()) throw new IllegalArgumentException("empty");
                        return PREFIX + name;
                    }
                }
                """;
        var widget = """
                package plain;

                public class Widget {

                    public int size() {
                        return 1;
                    }
                }
                """;
        artifact(project.resolve("vendor/greeting"), "greeting-1.0", "greeting/Greeter.java", greeting, true);
        artifact(project.resolve("vendor/plain"), "plain-2.3", "plain/Widget.java", widget, false);
        return project.resolve("vendor");
    }

    private static void artifact(Path directory, String name, String file, String source, boolean sources) throws IOException {
        var built = Files.createTempDirectory("tuul-artifact");
        built.toFile().deleteOnExit();
        var path = built.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);

        var classes = new LinkedHashMap<String, byte[]>();
        Sources.compile(List.of(built)).forEach((type, bytes) -> classes.put(type.replace('.', '/') + ".class", bytes));
        Files.createDirectories(directory);
        jar(directory.resolve(name + ".jar"), classes);
        if (sources) jar(directory.resolve(name + "-sources.jar"), Map.of(file, source.getBytes()));
    }

    private static void jar(Path path, Map<String, byte[]> entries) throws IOException {
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
    }

    /// A package and a module are symbols. Until they were, a reader who
    /// wanted to know what a package was for had nowhere to go — and every
    /// package in the JDK says, in the one file nothing was reading.
    private static void packages(Index index) {
        var invoicing = index.lookup("invoicing").orElseThrow();
        Check.equal("a project package is a symbol", TypeInfo.Kind.PACKAGE, invoicing.kind());
        Check.equal("and package-info.java is where it says what it is for",
                "Money owed, and who owes it.", invoicing.doc());
        Check.equal("with its tags, like anything else documented",
                List.of("@since 1.0"), invoicing.tags().stream().map(TypeInfo.Tag::line).toList());
        Check.equal("it declares no members, because a package has none", List.of(), invoicing.methods());
        Check.that("and it holds the types written in it",
                invoicing.nested().contains("invoicing.Invoice"));
        Check.that("only the ones somebody outside could use",
                invoicing.nested().stream().noneMatch(name -> name.contains("$")));

        var util = index.lookup("java.util").orElseThrow();
        Check.equal("a JDK package is one too", TypeInfo.Kind.PACKAGE, util.kind());
        Check.that("read out of src.zip, since the JDK ships no package-info.class",
                util.doc().startsWith("Contains the collections framework"));
        Check.that("holding its public types", util.nested().contains("java.util.ArrayList"));
        Check.that("and its subpackages", util.nested().contains("java.util.concurrent"));
        Check.that("subpackages come before the types, because they are the other question",
                util.nested().indexOf("java.util.concurrent") < util.nested().indexOf("java.util.ArrayList"));
        Check.that("what a package holds is what could be used from outside it",
                !util.nested().contains("java.util.TimSort"));

        var base = index.lookup("java.base").orElseThrow();
        Check.equal("a module is a symbol as well", TypeInfo.Kind.MODULE, base.kind());
        Check.that("module-info.java says what it is for",
                base.doc().startsWith("Defines the foundational APIs"));
        Check.that("and it holds the packages it exports", base.nested().contains("java.util"));
        Check.that("not the ones it keeps to itself, nor the ones it opened to one other module",
                base.nested().stream().noneMatch(name -> name.startsWith("jdk.internal")));

        Check.that("a name that is neither a type nor a package is still unknown",
                index.lookup("invoicing.nowhere").isEmpty());
        Check.that("and neither is a fragment of one",
                index.lookup("invoic").isEmpty());
    }

    /// Where a symbol is written. A reader who wants to see the code has to be
    /// told which file and which line, and a class file compiled with `-g:none`
    /// carries neither — so it comes from the source that was parsed for the
    /// doc comment, which knows exactly.
    /// What there is, before anything has been named — the one question the
    /// index could not answer, because the root has no name to ask about.
    private static void roots(Index index) {
        var roots = index.roots();
        Check.that("there are roots at all", !roots.isEmpty());

        var named = roots.stream().map(Catalog.Root::name).toList();
        Check.that("the project is one of them", named.contains(Index.PROJECT));
        Check.that("and so is the JDK", named.contains(Index.PLATFORM));
        Check.that("a root with nothing in it is not offered, so a project with no jars is not told it has some",
                roots.stream().noneMatch(root -> root.contents().isEmpty()));

        var project = roots.stream().filter(root -> root.name().equals(Index.PROJECT)).findFirst().orElseThrow();
        Check.that("the project root holds the package the fixture is written in",
                project.contents().contains("invoicing"));
        Check.that("and holds packages rather than types",
                project.contents().stream().noneMatch(name -> name.contains("Invoice")));

        var jdk = roots.stream().filter(root -> root.name().equals(Index.PLATFORM)).findFirst().orElseThrow();
        Check.that("the JDK root holds java.base", jdk.contents().contains("java.base"));
        Check.that("and leaves out what exports nothing anybody can import",
                jdk.contents().stream().noneMatch(name -> name.equals("jdk.internal.vm.ci")));

        Check.that("every name in a root is one the index can be asked about",
                index.lookup(project.contents().getFirst()).isPresent()
                        && index.lookup("java.base").isPresent());
        Check.that("and asking twice is answered from what was worked out the first time",
                index.roots() == roots || index.roots().equals(roots));
    }

    private static void located(Index index) {
        var invoice = index.lookup("invoicing.Invoice").orElseThrow();
        Check.that("a project type says which file it is written in",
                invoice.source().endsWith("invoicing/Invoice.java"));
        Check.that("and which line the declaration starts on", invoice.line() > 0);

        var compareTo = invoice.methods().stream()
                .filter(method -> method.name().equals("compareTo"))
                .findFirst()
                .orElseThrow();
        Check.that("so does a member", compareTo.line() > 0);
        Check.that("and it is further down the file than the type", compareTo.line() > invoice.line());

        var string = index.lookup("java.lang.String").orElseThrow();
        Check.that("a JDK type names the archive and the entry inside it",
                string.source().endsWith("src.zip!/java.base/java/lang/String.java"));
        Check.that("with a line, because src.zip is the source that was read", string.line() > 0);

        var util = index.lookup("java.util").orElseThrow();
        Check.that("and so does a package, through its package-info",
                util.source().endsWith("!/java.base/java/util/package-info.java"));
    }

    private static void rendering(Index index) throws IOException {
        var described = Docs.describe(index.lookup("invoicing.Invoice").orElseThrow(), false);
        Check.equal("the description names the symbol", "invoicing.Invoice", described.string("class", ""));
        Check.that("private members stay out", described.list("fields").isEmpty());
        Check.that("public members are in", described.list("methods").size() > 1);

        Check.equal("a section can be selected alone",
                "{\"implements\":[\"java.lang.Comparable<invoicing.Invoice>\"]}",
                Docs.select(described, Set.of("implements")).text());
        Check.that("the doc is a section of its own",
                Docs.select(described, Set.of("doc")).text().startsWith("{\"doc\":\"An invoice"));

        var out = new StringWriter();
        Docs.text(described, Set.of("implements"), out);
        Check.equal("text drops the package noise", "Comparable<Invoice>\n", out.toString());

        var full = new StringWriter();
        Docs.text(described, Set.of(), full);
        Check.that("the full rendering leads with the declaration",
                full.toString().startsWith("public record invoicing.Invoice\n  An invoice for a fixed amount"));
        Check.that("the full rendering lists members", full.toString().contains("  int compareTo(Invoice other)\n"));
        Check.that("a member's first sentence sits under it",
                full.toString().contains("      Orders invoices by amount, then id.\n"));

        var doc = new StringWriter();
        Docs.text(described, Set.of("doc"), doc);
        Check.that("--doc prints the whole comment, not a summary of it",
                doc.toString().contains("Invoices are compared"));

        var all = Docs.describe(index.lookup("invoicing.Invoice").orElseThrow(), true);
        Check.that("--all reaches private members", !all.list("fields").isEmpty());
    }

    private static TypeInfo.Method method(TypeInfo type, String name) {
        return type.methods().stream().filter(method -> method.name().equals(name)).findFirst().orElseThrow();
    }

    private static TypeInfo.Method overload(TypeInfo type, String name, String parameter) {
        return type.methods().stream()
                .filter(method -> method.name().equals(name))
                .filter(method -> method.parameters().size() == 1)
                .filter(method -> method.parameters().getFirst().type().equals(parameter))
                .findFirst()
                .orElseThrow();
    }

    /// Javadoc's reference links, resolved — and, as often, not resolved.
    ///
    /// `[Invoice#compareTo(Invoice)]` in a `///` comment is a cross-reference to
    /// a Java symbol wearing CommonMark's punctuation, and a markdown parser
    /// reads it as a shortcut reference to a definition the comment never
    /// writes: brackets, on the page, where a link was meant. What is checked
    /// here is the half of the answer that is neither markdown's nor a browser's
    /// — whether the index has the thing, and what the thing is called.
    ///
    /// The misses matter more than the hits. A name the index cannot find has to
    /// stay as text: a dead link says the page is wrong about its own project,
    /// where brackets only say a name.
    private static void references(Index index) {
        Check.equal("a bare name in a package comment is a type in that package",
                "/invoicing.Invoice", link(index, "invoicing", "Invoice"));
        Check.equal("a member reference points at the type, and says which member",
                "/invoicing.Invoice#compareTo", link(index, "invoicing", "Invoice#compareTo(Invoice)"));
        Check.equal("a member written with no parameters resolves the same way",
                "/invoicing.Invoice#customer", link(index, "invoicing", "Invoice#customer()"));
        Check.equal("and so does one written with no brackets at all",
                "/invoicing.Invoice#amount", link(index, "invoicing", "Invoice#amount"));
        Check.equal("a nested type is named the way somebody would write it",
                "/invoicing.Invoice.Kind", link(index, "invoicing", "Invoice.Kind"));
        Check.equal("a fully qualified name means itself, wherever it is read from",
                "/java.lang.String", link(index, "invoicing", "java.lang.String"));

        Check.equal("read from inside a type, a bare name is one of its nested types",
                "/invoicing.Invoice.Kind", link(index, "invoicing.Invoice", "Kind"));
        Check.equal("and failing that, a neighbour in the same package",
                "/invoicing.Invoice", link(index, "invoicing.Invoice", "Invoice"));
        Check.equal("an empty type is the type the comment is on",
                "/invoicing.Invoice#customer", link(index, "invoicing.Invoice", "#customer()"));

        Check.that("a type the index has never heard of stays as text",
                link(index, "invoicing", "Nowhere") == null);
        Check.that("a member the type does not declare stays as text, though the type exists",
                link(index, "invoicing", "Invoice#refund()") == null);
        Check.that("prose in brackets is not a reference and is not looked up",
                link(index, "invoicing", "the docs") == null);
        Check.that("nor is punctuation somebody bracketed",
                link(index, "invoicing", "1.2.3") == null);
        Check.that("an empty label is nothing at all", link(index, "invoicing", "") == null);

        var scoped = References.of(index, "invoicing", SymbolsTest::url);
        Check.equal("the same label asked twice answers the same thing",
                scoped.destination("Invoice"), scoped.destination("Invoice"));

        var rendered = markdown.Markdown.html("""
                An [Invoice] is compared by [Invoice#compareTo(Invoice)], which
                is not the same as [Invoice#refund()] or [the docs].
                """, scoped);
        Check.that("a comment renders the references it can as links",
                rendered.contains("<a href=\"/invoicing.Invoice\">Invoice</a>")
                        && rendered.contains("<a href=\"/invoicing.Invoice#compareTo\">"));
        Check.that("and leaves the ones it cannot as the text that was written",
                rendered.contains("[Invoice#refund()]") && rendered.contains("[the docs]"));
    }

    /// Where the tests pretend a symbol lives. A real one is a route, which is
    /// the caller's business and never [References]'.
    private static String url(String symbol, String member) {
        return "/" + symbol + (member.isEmpty() ? "" : "#" + member);
    }

    private static String link(Index index, String scope, String label) {
        return References.of(index, scope, SymbolsTest::url).destination(label);
    }

    private static Path sources() throws IOException {
        var root = Files.createTempDirectory("tuul-symbols");
        var invoicing = Files.createDirectories(root.resolve("invoicing"));
        Files.writeString(invoicing.resolve("package-info.java"), """
                /**
                 * Money owed, and who owes it.
                 *
                 * @since 1.0
                 */
                package invoicing;
                """);
        Files.writeString(invoicing.resolve("tutorial.md"), "# First invoice\n\nCreate a fixed invoice.\n");
        Files.writeString(invoicing.resolve("guide-01-reasons.md"), "# Design reasons\n\nKeep amounts exact.\n");
        Files.writeString(invoicing.resolve("README.md"), "# Checkout map\n");
        Files.writeString(invoicing.resolve("Invoice.java"), """
                package invoicing;

                import java.math.BigDecimal;

                /**
                 * An invoice for a fixed amount, identified by {@code id}.
                 *
                 * <p>Invoices are compared by amount.
                 *
                 * @since 1.0
                 */
                public record Invoice(String id, BigDecimal amount) implements Comparable<Invoice> {

                    /** What stage an invoice is at. */
                    public enum Kind {

                        /** Nothing has been sent yet. */
                        DRAFT,

                        /** The customer has it. */
                        SENT
                    }

                    /**
                     * Orders invoices by amount, then id. See {@link Invoice.Kind}.
                     *
                     * @param other the invoice to compare with
                     * @return a negative number, zero, or a positive number
                     */
                    @Override
                    public int compareTo(Invoice other) {
                        return amount.compareTo(other.amount);
                    }

                    /// The customer this invoice is for.
                    ///
                    /// Not [Invoice.Kind], and not [Nowhere] either.
                    public String customer() {
                        return "nobody";
                    }

                    /** What an invoice can be, and nothing else. */
                    public sealed interface State {

                        /** Nobody has paid yet. */
                        record Owing(BigDecimal left) implements State {}

                        /** Somebody did. */
                        record Paid(String when) implements State {}
                    }
                }
                """);
        root.toFile().deleteOnExit();
        return root;
    }

    private static final class Fixture {}

    private static final class MemoryStore implements IndexStore {

        private final Map<String, TypeInfo> types = new LinkedHashMap<>();
        private final List<symbols.Document> documents = new java.util.ArrayList<>();
        private String sourceStamp = "";
        private String documentStamp = "";
        private boolean sourceComplete;
        private boolean documentComplete;

        @Override
        public java.util.Optional<Snapshot> inspect(String kind, String location, String current) {
            var kept = location.equals("documents") ? documentStamp : sourceStamp;
            if (kept.isEmpty()) return java.util.Optional.empty();
            var done = location.equals("documents") ? documentComplete : sourceComplete;
            return java.util.Optional.of(new Snapshot(1, kept.equals(current), done));
        }

        @Override
        public java.util.Optional<TypeInfo> type(long origin, String name) {
            return java.util.Optional.ofNullable(types.get(name));
        }

        @Override
        public java.util.Optional<symbols.Document> document(
                long origin, String packageName, String kind, String slug) {
            return documents.stream()
                    .filter(document -> document.packageName().equals(packageName))
                    .filter(document -> document.kind().equals(kind))
                    .filter(document -> document.slug().equals(slug))
                    .findFirst();
        }

        @Override
        public List<symbols.Document> documents(long origin, String packageName, String kind) {
            return documents.stream()
                    .filter(document -> document.packageName().equals(packageName))
                    .filter(document -> kind.isEmpty() || document.kind().equals(kind))
                    .toList();
        }

        @Override
        public List<String> names(long origin) {
            return List.copyOf(types.keySet());
        }

        @Override
        public List<String> names(long origin, TypeInfo.Kind kind) {
            return types.values().stream().filter(type -> type.kind() == kind).map(TypeInfo::name).sorted().toList();
        }

        @Override
        public List<Catalog.Match> search(String text, int limit) {
            return List.of();
        }

        @Override
        public void publish(String kind, String location, String current,
                Map<String, TypeInfo> written, List<symbols.Document> found) {
            if (location.equals("documents")) {
                documentStamp = current;
                documents.clear();
                documents.addAll(found);
                documentComplete = true;
                return;
            }
            sourceStamp = current;
            types.clear();
            types.putAll(written);
            sourceComplete = true;
        }

        @Override
        public void publishIncremental(String kind, String location, String current, Map<String, TypeInfo> written) {
            types.putAll(written);
        }

        @Override
        public void close() {}
    }
}
