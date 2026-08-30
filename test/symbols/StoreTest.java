package symbols;

import harness.Check;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import sqlite3.Database;
import sqlite3.SqliteException;

public final class StoreTest {

    private StoreTest() {}

    public static void run() throws IOException {
        format();
        roundTrip();
        documents();
        staleness();
        publication();
        searching();
        constraints();
        rebuilds();
    }

    /// A file that does not say what it is cannot be told from one that does.
    private static void format() throws IOException {
        var file = index("format");
        try (var store = Store.open(file).orElseThrow()) {
            Check.that("the index opens", store != null);
        }
        try (var database = Database.open(file)) {
            Check.equal("it is stamped with tuul's application id", (long) Schema.APPLICATION, pragma(database, "application_id"));
            Check.equal("and the version of the schema in it", (long) Schema.VERSION, pragma(database, "user_version"));
            Check.equal("the journal is write-ahead, so a reader never waits for a writer",
                    "wal", text(database, "pragma journal_mode"));
            Check.that("and the tables are the ones the schema declares",
                    tables(database).containsAll(List.of(
                            "origin", "type", "member", "parameter", "tag", "document", "search")));
        }
    }

    private static void documents() throws IOException {
        try (var store = Store.open(index("documents")).orElseThrow()) {
            var tutorial = new Document("invoicing", "tutorial", "first", "First invoice",
                    "# First invoice\n\nCreate a fixed amount.\n", "src/invoicing/tutorial-01-first.md");
            store.publish("project", "sources", "one", Map.of("invoicing.Invoice", invoice()), List.of(tutorial));
            var origin = store.inspect("project", "sources", "one").orElseThrow();

            Check.equal("a document comes back as it went in", tutorial,
                    store.document(origin.id(), "invoicing", "tutorial", "first").orElseThrow());
            Check.equal("documents keep filename order", List.of(tutorial),
                    store.documents(origin.id(), "invoicing", ""));
            Check.equal("a document body is indexed for search", "invoicing/tutorial/first",
                    store.search("fixed amount", 10).stream()
                            .filter(match -> match.kind().equals("tutorial"))
                            .findFirst().orElseThrow().symbol());
            store.publishIncremental("project", "sources", "one", Map.of("invoicing", invoicing()));
            Check.that("an incremental symbol write keeps package documents",
                    store.document(origin.id(), "invoicing", "tutorial", "first").isPresent());

            store.publish("project", "sources", "two", Map.of(), List.of());
            Check.that("a changed stamp forgets package documents",
                    store.documents(origin.id(), "invoicing", "").isEmpty());
            Check.that("and forgets their search rows",
                    store.search("First invoice", 10).stream().noneMatch(match -> match.kind().equals("tutorial")));
        }
    }

    /// Everything a symbol knows has to survive the trip, or the index is worse
    /// than useless: it is quietly wrong.
    private static void roundTrip() throws IOException {
        try (var store = Store.open(index("round-trip")).orElseThrow()) {
            Check.that("an unpublished origin is absent",
                    store.inspect("project", "sources", "first").isEmpty());
            store.publish("project", "sources", "first", Map.of("invoicing.Invoice", invoice()), List.of());
            var origin = store.inspect("project", "sources", "first").orElseThrow();
            var read = store.type(origin.id(), "invoicing.Invoice").orElseThrow();
            Check.equal("a type comes back as it went in", invoice(), read);
            Check.equal("with its members in order",
                    List.of("compareTo", "id"),
                    read.methods().stream().map(TypeInfo.Method::name).toList());
            Check.equal("its parameters", "invoicing.Invoice other", read.methods().getFirst().parameters().getFirst().text());
            Check.equal("its tags", "@param other the invoice to compare with", read.methods().getFirst().tags().getFirst().line());
            Check.equal("and its fields", "amount", read.fields().getFirst().name());

            Check.equal("an origin that was indexed in full knows every name it holds",
                    List.of("invoicing.Invoice"), store.names(origin.id()));
            Check.that("a name it does not hold is simply absent",
                    store.type(origin.id(), "invoicing.Ledger").isEmpty());

            store.publishIncremental("project", "sources", "first", Map.of("invoicing.Invoice", invoice()));
            Check.equal("writing the same type again replaces it rather than doubling it",
                    List.of("invoicing.Invoice"), store.names(origin.id()));

            Check.equal("where a type is written survives the trip", "src/invoicing/Invoice.java", read.source());
            Check.equal("and which line it starts on", 7, read.line());
            Check.equal("so does a member's line", 42, read.methods().getFirst().line());
            Check.equal("and a field's", 11, read.fields().getFirst().line());

            // A package is a symbol, so it is stored as one. Anything the index
            // forgets is a question that works cold and fails warm, which is
            // worse than one that never worked.
            store.publishIncremental("project", "sources", "first", Map.of("invoicing", invoicing()));
            var read_ = store.type(origin.id(), "invoicing").orElseThrow();
            Check.equal("a package comes back as it went in", invoicing(), read_);
            Check.equal("still a package", TypeInfo.Kind.PACKAGE, read_.kind());
            Check.equal("still holding what it holds",
                    List.of("invoicing.Invoice", "invoicing.Ledger"), read_.nested());
        }
    }

    /// A package: a name, what somebody wrote about it, and what it holds.
    private static TypeInfo invoicing() {
        return new TypeInfo("invoicing", TypeInfo.Kind.PACKAGE, List.of(), List.of(), "", List.of(), List.of(),
                List.of("invoicing.Invoice", "invoicing.Ledger"), List.of(), List.of(),
                "Money owed, and who owes it.", List.of(new TypeInfo.Tag("since", "", "1.0")),
                "src/invoicing/package-info.java", 5);
    }

    /// The stamp is the whole contract: same stamp, same facts.
    private static void staleness() throws IOException {
        try (var store = Store.open(index("staleness")).orElseThrow()) {
            store.publish("project", "sources", "one", Map.of("invoicing.Invoice", invoice()), List.of());
            var first = store.inspect("project", "sources", "one").orElseThrow();

            var again = store.inspect("project", "sources", "one").orElseThrow();
            Check.that("an unchanged stamp is fresh", again.fresh());
            Check.that("and complete, so a miss means the name does not exist", again.complete());

            var moved = store.inspect("project", "sources", "two").orElseThrow();
            Check.that("a changed stamp is not fresh", !moved.fresh());
            Check.that("the previous generation remains complete", moved.complete());
            Check.equal("it is the same origin", first.id(), moved.id());
            Check.equal("and it still holds its names", List.of("invoicing.Invoice"), store.names(moved.id()));
            Check.that("including the type itself", store.type(moved.id(), "invoicing.Invoice").isPresent());
        }
    }

    /// A freshness check is not publication. Readers keep the old complete
    /// generation until all replacement rows and their stamp commit together.
    private static void publication() throws IOException {
        var file = index("publication");
        try (var store = Store.open(file).orElseThrow()) {
            store.publish("project", "sources", "one", Map.of("invoicing.Invoice", invoice()), List.of());
            var stale = store.inspect("project", "sources", "two").orElseThrow();
            Check.that("inspecting a changed fingerprint reports stale", !stale.fresh());
            Check.that("and leaves the last complete generation readable",
                    store.type(stale.id(), "invoicing.Invoice").isPresent());

            store.publish("project", "sources", "two", Map.of("invoicing.Ledger", invoice()), List.of());
            Check.that("publication replaces the old rows",
                    store.type(stale.id(), "invoicing.Invoice").isEmpty());
            Check.that("and exposes the replacement rows",
                    store.type(stale.id(), "invoicing.Ledger").isPresent());

            var roots = List.of(new Catalog.Root("project", "This project", List.of("invoicing")));
            store.publishRoots(roots);
            Check.equal("the browser root summary makes the round trip", roots, store.roots());
        }
        try (var catalog = Index.catalog(file)) {
            Check.that("the persistent catalog is ready without source paths", catalog.ready());
            Check.that("and reads the committed replacement", catalog.lookup("invoicing.Ledger").isPresent());
        }
    }

    /// FTS5 earns its place by finding what a person half-remembers.
    private static void searching() throws IOException {
        try (var store = Store.open(index("searching")).orElseThrow()) {
            store.publish("project", "sources", "one", Map.of("invoicing.Invoice", invoice()), List.of());
            var origin = store.inspect("project", "sources", "one").orElseThrow();

            Check.equal("a symbol is found by its own documentation",
                    "invoicing.Invoice", store.search("fixed amount", 5).getFirst().symbol());
            Check.equal("a member is found by its own",
                    "invoicing.Invoice#compareTo", store.search("orders invoices", 5).getFirst().symbol());
            Check.that("stemming finds the word that was actually written",
                    !store.search("ordering invoice", 5).isEmpty());
            Check.that("and a tag's text is searchable too, though it lives in another table",
                    store.search("the invoice to compare with", 5).stream()
                            .anyMatch(match -> match.symbol().equals("invoicing.Invoice#compareTo")));
            Check.that("a search for nothing in particular finds nothing",
                    store.search("kubernetes", 5).isEmpty());
            Check.equal("a limit is a limit", 1, store.search("invoice", 1).size());

            Check.equal("a type named exactly comes before its own members",
                    "invoicing.Invoice", store.search("invoicing.Invoice", 5).getFirst().symbol());
            Check.equal("and a bare name finds the type called that, not a member mentioning it",
                    "invoicing.Invoice", store.search("Invoice", 5).getFirst().symbol());

            Check.that("a private member is not offered by search, since no page will show it",
                    store.search("what is owed", 5).isEmpty());
            Check.that("though it is still stored, because tuul docs --all asks for it",
                    store.type(origin.id(), "invoicing.Invoice").orElseThrow().fields().stream()
                            .anyMatch(field -> field.name().equals("amount")));

            Check.that("a query with no words in it finds nothing rather than failing",
                    store.search("...", 5).isEmpty() && store.search("-", 5).isEmpty()
                            && store.search("()", 5).isEmpty());

            store.publish("project", "sources", "two", Map.of(), List.of());
            Check.that("and forgetting a type forgets it from the search as well",
                    store.search("fixed amount", 5).isEmpty());
        }
    }

    /// The schema says what it will not hold, so nothing has to remember to
    /// check it.
    private static void constraints() throws IOException {
        var file = index("constraints");
        try (var store = Store.open(file).orElseThrow()) {
            store.publish("project", "sources", "one", Map.of(), List.of());
        }
        try (var database = Database.open(file)) {
            database.execute("pragma foreign_keys = on");
            Check.throwing("a type must belong to an origin that exists",
                    () -> database.execute("insert into type (origin, name, kind, modifiers, superclass, doc)"
                            + " values (999, 'a.B', 'CLASS', '', '', '')"));
            Check.throwing("a tag belongs to a type or a member, never both",
                    () -> database.execute("insert into tag (type, member, position, tag, name, text)"
                            + " values (1, 1, 0, 'param', 'x', 'y')"));
            Check.throwing("and an origin is one of the three kinds there are",
                    () -> database.execute("insert into origin (kind, location, stamp) values ('guess', 'x', 'y')"));
        }
    }

    /// An index is derived data. Anything unrecognisable is replaced, because
    /// the alternative is reading a file whose shape you have guessed at.
    private static void rebuilds() throws IOException {
        var older = index("older");
        try (var store = Store.open(older).orElseThrow()) {
            store.publish("project", "sources", "one", Map.of("invoicing.Invoice", invoice()), List.of());
        }
        try (var database = Database.open(older)) {
            database.execute("pragma user_version = " + (Schema.VERSION + 99));
        }
        try (var store = Store.open(older).orElseThrow()) {
            Check.that("an index from another version is built again, not read",
                    store.inspect("project", "sources", "one").isEmpty());
        }

        var corrupt = index("corrupt");
        Files.writeString(corrupt, "this is not a database, it is a note about one\n");
        try (var store = Store.open(corrupt).orElseThrow()) {
            store.publish("project", "sources", "one", Map.of("invoicing.Invoice", invoice()), List.of());
            var origin = store.inspect("project", "sources", "one").orElseThrow();
            Check.equal("and so is a file that was never one at all",
                    List.of("invoicing.Invoice"), store.names(origin.id()));
        }

        var missing = index("nested/deeper/index.db");
        try (var store = Store.open(missing).orElseThrow()) {
            Check.that("an index makes the directory it lives in", Files.isRegularFile(missing));
            store.publish("project", "sources", "one", Map.of(), List.of());
            Check.that("and works there", store.inspect("project", "sources", "one").orElseThrow().id() > 0);
        }
    }

    private static TypeInfo invoice() {
        var compareTo = new TypeInfo.Method("compareTo", "int",
                List.of(new TypeInfo.Parameter("invoicing.Invoice", "other")),
                List.of("public"),
                "Orders invoices by amount, then id.",
                List.of(new TypeInfo.Tag("param", "other", "the invoice to compare with")),
                42);
        var id = new TypeInfo.Method("id", "java.lang.String", List.of(), List.of("public"), "", List.of(), 0);
        var amount = new TypeInfo.Field("amount", "java.math.BigDecimal", List.of("private", "final"),
                "What is owed.", List.of(), 11);
        return new TypeInfo("invoicing.Invoice", TypeInfo.Kind.RECORD, List.of("public"), List.of("T"),
                "", List.of("java.lang.Comparable<invoicing.Invoice>"),
                List.of("invoicing.Invoice.Paid", "invoicing.Invoice.Owing"),
                List.of("invoicing.Invoice.Kind"),
                List.of(compareTo, id), List.of(amount),
                "An invoice for a fixed amount, identified by id.",
                List.of(new TypeInfo.Tag("since", "", "1.0")),
                "src/invoicing/Invoice.java", 7);
    }

    private static Path index(String name) throws IOException {
        var directory = Files.createTempDirectory("tuul-store");
        directory.toFile().deleteOnExit();
        return directory.resolve(name);
    }

    private static long pragma(Database database, String pragma) {
        try (var rows = database.query("pragma " + pragma)) {
            return rows.next() ? rows.integer(0) : -1;
        }
    }

    private static String text(Database database, String sql) {
        try (var rows = database.query(sql)) {
            return rows.next() ? rows.text(0) : "";
        }
    }

    private static List<String> tables(Database database) {
        var names = new java.util.ArrayList<String>();
        try (var rows = database.query("select name from sqlite_master where type in ('table', 'view')")) {
            while (rows.next()) names.add(rows.text(0));
        }
        return names;
    }
}
