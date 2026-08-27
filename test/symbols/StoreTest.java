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
        staleness();
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
                    tables(database).containsAll(List.of("origin", "type", "member", "parameter", "tag", "search")));
        }
    }

    /// Everything a symbol knows has to survive the trip, or the index is worse
    /// than useless: it is quietly wrong.
    private static void roundTrip() throws IOException {
        try (var store = Store.open(index("round-trip")).orElseThrow()) {
            var origin = store.origin("project", "sources", "first");
            Check.that("a new origin is not fresh", !origin.fresh());

            store.write(origin.id(), Map.of("invoicing.Invoice", invoice()), true);
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

            store.write(origin.id(), Map.of("invoicing.Invoice", invoice()), true);
            Check.equal("writing the same type again replaces it rather than doubling it",
                    List.of("invoicing.Invoice"), store.names(origin.id()));
        }
    }

    /// The stamp is the whole contract: same stamp, same facts.
    private static void staleness() throws IOException {
        try (var store = Store.open(index("staleness")).orElseThrow()) {
            var first = store.origin("project", "sources", "one");
            store.write(first.id(), Map.of("invoicing.Invoice", invoice()), true);

            var again = store.origin("project", "sources", "one");
            Check.that("an unchanged stamp is fresh", again.fresh());
            Check.that("and complete, so a miss means the name does not exist", again.complete());

            var moved = store.origin("project", "sources", "two");
            Check.that("a changed stamp is not fresh", !moved.fresh());
            Check.that("nor complete", !moved.complete());
            Check.equal("it is the same origin", first.id(), moved.id());
            Check.equal("and it has forgotten what it held", List.of(), store.names(moved.id()));
            Check.that("including the type itself", store.type(moved.id(), "invoicing.Invoice").isEmpty());
        }
    }

    /// FTS5 earns its place by finding what a person half-remembers.
    private static void searching() throws IOException {
        try (var store = Store.open(index("searching")).orElseThrow()) {
            var origin = store.origin("project", "sources", "one");
            store.write(origin.id(), Map.of("invoicing.Invoice", invoice()), true);

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

            store.origin("project", "sources", "two");
            Check.that("and forgetting a type forgets it from the search as well",
                    store.search("fixed amount", 5).isEmpty());
        }
    }

    /// The schema says what it will not hold, so nothing has to remember to
    /// check it.
    private static void constraints() throws IOException {
        var file = index("constraints");
        try (var store = Store.open(file).orElseThrow()) {
            store.origin("project", "sources", "one");
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
            store.write(store.origin("project", "sources", "one").id(), Map.of("invoicing.Invoice", invoice()), true);
        }
        try (var database = Database.open(older)) {
            database.execute("pragma user_version = " + (Schema.VERSION + 99));
        }
        try (var store = Store.open(older).orElseThrow()) {
            Check.that("an index from another version is built again, not read",
                    !store.origin("project", "sources", "one").fresh());
        }

        var corrupt = index("corrupt");
        Files.writeString(corrupt, "this is not a database, it is a note about one\n");
        try (var store = Store.open(corrupt).orElseThrow()) {
            var origin = store.origin("project", "sources", "one");
            store.write(origin.id(), Map.of("invoicing.Invoice", invoice()), true);
            Check.equal("and so is a file that was never one at all",
                    List.of("invoicing.Invoice"), store.names(origin.id()));
        }

        var missing = index("nested/deeper/index.db");
        try (var store = Store.open(missing).orElseThrow()) {
            Check.that("an index makes the directory it lives in", Files.isRegularFile(missing));
            Check.that("and works there", store.origin("project", "sources", "one").id() > 0);
        }
    }

    private static TypeInfo invoice() {
        var compareTo = new TypeInfo.Method("compareTo", "int",
                List.of(new TypeInfo.Parameter("invoicing.Invoice", "other")),
                List.of("public"),
                "Orders invoices by amount, then id.",
                List.of(new TypeInfo.Tag("param", "other", "the invoice to compare with")));
        var id = new TypeInfo.Method("id", "java.lang.String", List.of(), List.of("public"), "", List.of());
        var amount = new TypeInfo.Field("amount", "java.math.BigDecimal", List.of("private", "final"),
                "What is owed.", List.of());
        return new TypeInfo("invoicing.Invoice", TypeInfo.Kind.RECORD, List.of("public"), List.of("T"),
                "", List.of("java.lang.Comparable<invoicing.Invoice>"), List.of(compareTo, id), List.of(amount),
                "An invoice for a fixed amount, identified by id.",
                List.of(new TypeInfo.Tag("since", "", "1.0")));
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
