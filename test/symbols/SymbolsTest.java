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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class SymbolsTest {

    private SymbolsTest() {}

    public static void run() throws IOException {
        var index = Index.of(List.of(sources()));
        project(index);
        platform(index);
        javadoc(index);
        platformJavadoc(index);
        vendored();
        rendering(index);
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
        Check.that("everything compiled is indexed", index.names().contains("invoicing.Invoice"));
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
        Check.equal("a type carries its doc comment",
                "An invoice for a fixed amount, identified by id. Invoices are compared by amount.",
                invoice.doc());
        Check.equal("so does a method", "Orders invoices by amount, then id.", method(invoice, "compareTo").doc());
        Check.equal("markdown doc comments are read too",
                "The customer this invoice is for.",
                method(invoice, "customer").doc());
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

        var index = Index.of(List.of(source), List.of(vendor));
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

    private static Path sources() throws IOException {
        var root = Files.createTempDirectory("tuul-symbols");
        var invoicing = Files.createDirectories(root.resolve("invoicing"));
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
                     * Orders invoices by amount, then id.
                     *
                     * @param other the invoice to compare with
                     * @return a negative number, zero, or a positive number
                     */
                    @Override
                    public int compareTo(Invoice other) {
                        return amount.compareTo(other.amount);
                    }

                    /// The customer this invoice is for.
                    public String customer() {
                        return "nobody";
                    }
                }
                """);
        root.toFile().deleteOnExit();
        return root;
    }
}
