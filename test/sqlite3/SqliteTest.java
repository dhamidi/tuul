package sqlite3;

import harness.Check;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SqliteTest {

    private SqliteTest() {}

    public static void run() throws IOException {
        version();
        values();
        names();
        streams();
        refuses();
        persists();
        prepares();
        transacts();
        migrations();
    }

    /// The two persistence strategies, and the four refusals between them.
    ///
    /// The failure this guards against is data loss, so every check here uses a
    /// database with rows already in it. A migration proved only on an empty
    /// file is not proved.
    private static void migrations() throws IOException {
        var root = Files.createTempDirectory("tuul-migrations");
        root.toFile().deleteOnExit();

        durableCreates(root.resolve("fresh.db"));
        durableMigrates(root.resolve("forward.db"));
        durableAdopts(root.resolve("legacy.db"));
        durableRefusesBackwards(root.resolve("ahead.db"));
        durableRefusesForeign(root.resolve("theirs.db"));
        durableIsAllOrNothing(root.resolve("broken.db"));
        derivedKeepsCurrent(root.resolve("kept.db"));
        derivedRebuildsOld(root.resolve("old.db"));
        derivedRefusesForeign(root.resolve("notours.db"));
    }

    private static final int OURS = 0x74657374;

    private static final int THEIRS = 0x12345678;

    private static Migrations durable() {
        return Migrations.durable(OURS).step("create table notes (id integer primary key, body text not null);");
    }

    /// A new file gets every step, and says so afterwards.
    private static void durableCreates(java.nio.file.Path file) throws IOException {
        try (var database = durable().open(file)) {
            database.execute("insert into notes (body) values ('one')");
            Check.equal("a durable schema stamps the version it reached", 1L, version(database));
            Check.equal("and claims the file", (long) OURS, application(database));
        }
    }

    /// The promise of the whole class: a step added later runs, and the rows
    /// that were already there stay.
    private static void durableMigrates(java.nio.file.Path file) throws IOException {
        try (var database = durable().open(file)) {
            database.execute("insert into notes (body) values ('written before the migration')");
        }
        try (var database = durable().step("alter table notes add column weight real not null default 0;")
                .open(file)) {
            Check.equal("a step added later raises the version", 2L, version(database));
            Check.equal("and the rows written before it are still there",
                    "written before the migration", one(database, "select body from notes"));
            database.execute("update notes set weight = 3 where body like 'written%'");
            Check.equal("in a shape the new step made", "3.0", one(database, "select weight from notes"));
        }
    }

    /// A file written before any of this existed claims nothing. It is adopted
    /// rather than rebuilt, because a durable file is the only copy there is.
    ///
    /// The first step says `if not exists`, and it has to. Adoption works by
    /// running step one over tables that are already there, so a step one that
    /// cannot run twice cannot adopt anything — it fails with *table already
    /// exists* and the file it refuses is the file with all the data in it.
    /// `actors.Journal` is written this way for this reason.
    private static void durableAdopts(java.nio.file.Path file) throws IOException {
        var adopting = Migrations.durable(OURS)
                .step("create table if not exists notes (id integer primary key, body text not null);");
        try (var database = Database.open(file)) {
            database.script("create table notes (id integer primary key, body text not null);");
            database.execute("insert into notes (body) values ('older than versioning')");
        }
        try (var database = adopting.open(file)) {
            Check.equal("an unstamped file is adopted, not emptied",
                    "older than versioning", one(database, "select body from notes"));
            Check.equal("and is stamped once it has been", 1L, version(database));
            Check.equal("with the application that took it", (long) OURS, application(database));
        }
    }

    /// A file from a later version of the program is refused. Reading it would
    /// answer questions about a shape this does not know.
    private static void durableRefusesBackwards(java.nio.file.Path file) throws IOException {
        try (var database = durable().step("alter table notes add column weight real;").open(file)) {
            database.execute("insert into notes (body) values ('from the future')");
        }
        refused("a file from a later version is refused rather than answered", durable(), file);
        try (var database = Database.open(file)) {
            Check.equal("and nothing in it was touched", 2L, version(database));
            Check.equal("least of all the rows", "from the future", one(database, "select body from notes"));
        }
    }

    private static void durableRefusesForeign(java.nio.file.Path file) throws IOException {
        try (var database = Database.open(file)) {
            database.script("pragma application_id = " + THEIRS + ";"
                    + "create table theirs (x); insert into theirs values (1);");
        }
        refused("another application's database is refused", durable(), file);
        try (var database = Database.open(file)) {
            Check.equal("and left exactly as it was", (long) THEIRS, application(database));
            Check.equal("with its own tables", "1", one(database, "select x from theirs"));
        }
    }

    /// A step that fails leaves nothing behind, including the version.
    private static void durableIsAllOrNothing(java.nio.file.Path file) throws IOException {
        try (var database = durable().step("create table half (id integer);")
                .step("this is not sql at all;")
                .open(file)) {
            Check.that("a step that cannot run fails the open", false);
        } catch (RuntimeException expected) {
            Check.that("a step that cannot run fails the open", true);
        }
        try (var database = Database.open(file)) {
            Check.equal("and the version stays where it was", 0L, version(database));
            Check.equal("with no table from the half that ran", "0",
                    one(database, "select count(*) from sqlite_master where name = 'half'"));
        }
    }

    private static Migrations derived(int version) {
        return Migrations.derived(OURS, version)
                .step("create table notes (id integer primary key, body text not null);");
    }

    private static void derivedKeepsCurrent(java.nio.file.Path file) throws IOException {
        try (var database = derived(1).open(file)) {
            database.execute("insert into notes (body) values ('kept')");
        }
        try (var database = derived(1).open(file)) {
            Check.equal("a derived file at the right version is opened as it is",
                    "kept", one(database, "select body from notes"));
        }
    }

    /// The other half of the derived rule, and the one worth a test: an old
    /// file is *thrown away*. That is correct only because something else can
    /// build it again.
    private static void derivedRebuildsOld(java.nio.file.Path file) throws IOException {
        try (var database = derived(1).open(file)) {
            database.execute("insert into notes (body) values ('recomputable')");
        }
        try (var database = derived(2).open(file)) {
            Check.equal("a derived file at an old version is rebuilt from nothing", "0",
                    one(database, "select count(*) from notes"));
            Check.equal("at the version that was asked for", 2L, version(database));
        }
    }

    /// Rebuilding means deleting, so the one file it must never rebuild is one
    /// that another program claimed.
    private static void derivedRefusesForeign(java.nio.file.Path file) throws IOException {
        try (var database = Database.open(file)) {
            database.script("pragma application_id = " + THEIRS + ";"
                    + "create table theirs (x); insert into theirs values (7);");
        }
        refused("a derived schema refuses another application's file rather than deleting it",
                derived(1), file);
        try (var database = Database.open(file)) {
            Check.equal("which is still there", "7", one(database, "select x from theirs"));
        }
    }

    /// Opens and expects a refusal, by type rather than by "something threw".
    ///
    /// [Check#throwing] would be satisfied by an [IOException] from a path that
    /// cannot be written, which is a different failure and would pass this test
    /// for a reason it is not testing.
    private static void refused(String what, Migrations migrations, java.nio.file.Path file) {
        try (var database = migrations.open(file)) {
            Check.that(what, false);
        } catch (SchemaException refusal) {
            Check.that(what, true);
        } catch (IOException unreadable) {
            Check.that(what + " — threw IOException instead", false);
        }
    }

    private static long version(Database database) {
        try (var rows = database.query("pragma user_version")) {
            return rows.next() ? rows.integer(0) : -1;
        }
    }

    private static long application(Database database) {
        try (var rows = database.query("pragma application_id")) {
            return rows.next() ? rows.integer(0) : -1;
        }
    }

    private static String one(Database database, String sql) {
        try (var rows = database.query(sql)) {
            return rows.next() ? rows.text(0) : "";
        }
    }

    /// A statement prepared once and run many times, which is what a bulk write
    /// is made of.
    private static void prepares() {
        try (var database = Database.memory()) {
            database.execute("create table notes (id integer primary key, body text, weight real)");
            try (var insert = Statement.of(database, "insert into notes (body, weight) values (?, ?)")) {
                Check.equal("a run answers with the row it made", 1L, insert.run("first", 1.0));
                Check.equal("and again, without preparing again", 2L, insert.run("second", 2.0));
                insert.run(null, 3.0);
            }
            Check.equal("every run landed", 3L, count(database, "select count(*) from notes"));
            Check.equal("bindings are cleared between runs, so a null stays null",
                    1L, count(database, "select count(*) from notes where body is null"));

            try (var insert = Statement.of(database, "insert into notes (body) values (?)")) {
                Check.throwing("a statement that fails says so", () -> insert.run(List.of()));
                Check.equal("and is still usable afterwards", 4L, insert.run("fourth"));
            }
            Check.throwing("bad SQL is refused when it is prepared, not when it is run",
                    () -> Statement.of(database, "insert into nowhere values (?)"));
        }
    }

    /// Bulk writing belongs in a transaction — and a transaction that fails
    /// leaves nothing behind.
    private static void transacts() {
        try (var database = Database.memory()) {
            database.execute("create table notes (id integer primary key)");
            database.transaction(() -> {
                for (var id = 1; id <= 100; id++) database.execute("insert into notes values (?)", id);
            });
            Check.equal("everything in a transaction lands", 100L, count(database, "select count(*) from notes"));

            Check.throwing("a transaction that throws passes the failure on", () -> database.transaction(() -> {
                database.execute("insert into notes values (101)");
                throw new IllegalStateException("thought better of it");
            }));
            Check.equal("and takes its changes back with it", 100L, count(database, "select count(*) from notes"));

            database.script("""
                    create table one (n integer);
                    create table two (n integer);
                    insert into one values (1);
                    """);
            Check.equal("a script runs every statement in it", 1L, count(database, "select count(*) from one"));
            Check.equal("including the ones that make tables", 0L, count(database, "select count(*) from two"));
            Check.throwing("and stops at the first that fails", () -> database.script("select 1; select nope;"));
        }
    }

    private static void version() {
        Check.that("the amalgamation reports its version: " + Database.version(),
                Database.version().startsWith("3."));
    }

    private static void values() {
        try (var database = Database.memory()) {
            database.execute("create table notes (id integer primary key, body text, weight real, data blob)");
            var changed = database.execute(
                    "insert into notes (body, weight, data) values (?, ?, ?)", "first", 1.5, new byte[] {1, 2, 3});
            Check.equal("an insert reports what it changed", 1L, changed);
            Check.equal("and the row it made", 1L, database.lastId());
            database.execute("insert into notes (body, weight, data) values (?, ?, ?)", "second", 2.5, null);

            try (var rows = database.query("select id, body, weight, data from notes order by id")) {
                Check.that("a query has rows", rows.next());
                Check.equal("integers come back", 1L, rows.integer(0));
                Check.equal("text comes back", "first", rows.text(1));
                Check.equal("reals come back", 1.5, rows.real(2));
                Check.equal("blobs come back", "[1, 2, 3]", Arrays.toString(rows.blob(3)));
                Check.equal("types are what SQLite says they are", Type.TEXT, rows.type(1));
                Check.equal("and a value knows its own type", 1.5, rows.value(2));

                Check.that("the cursor moves on", rows.next());
                Check.equal("null is null, not empty", Type.NULL, rows.type(3));
                Check.that("and reads as null", rows.isNull(3));
                Check.that("the cursor ends", !rows.next());
            }

            Check.equal("parameters are bound, not spliced",
                    0L,
                    count(database, "select count(*) from notes where body = ?", "'; drop table notes; --"));
            Check.equal("booleans bind as integers", 1L, count(database, "select ?", true));
            Check.equal("and so do the other whole numbers", 42L, count(database, "select ?", 42));
        }
    }

    private static void names() {
        try (var database = Database.memory()) {
            database.execute("create table people (name text, age integer)");
            database.execute("insert into people values (?, ?)", "Ada", 36);
            try (var rows = database.query("select name, age from people")) {
                rows.next();
                Check.equal("columns can be read by name", "Ada", rows.text("name"));
                Check.equal("whatever their position", 36L, rows.integer("age"));
                Check.equal("and the names are the query's own", "age", rows.name(1));
                Check.equal("a row knows how wide it is", 2, rows.columns());
                Check.throwing("an unknown column is a failure", () -> rows.text("nope"));
            }
        }
    }

    /// A cursor holds one row, not a result set: the point of binding SQLite
    /// this way is that a big query costs no more than a small one.
    private static void streams() {
        try (var database = Database.memory()) {
            database.execute("create table numbers (n integer)");
            database.execute("""
                    insert into numbers
                    with recursive series(n) as (select 1 union all select n + 1 from series where n < 10000)
                    select n from series
                    """);

            var total = 0L;
            var seen = 0;
            try (var rows = database.query("select n from numbers")) {
                while (rows.next()) {
                    total += rows.integer(0);
                    seen++;
                }
            }
            Check.equal("every row arrives", 10000, seen);
            Check.equal("and arrives intact", 50005000L, total);
        }
    }

    private static void refuses() {
        try (var database = Database.memory()) {
            Check.throwing("bad SQL is refused", () -> database.execute("select from where"));
            database.execute("create table one (id integer primary key)");
            database.execute("insert into one values (1)");
            Check.throwing("so is a constraint violation", () -> database.execute("insert into one values (1)"));
            Check.throwing("so is a value with no SQLite type", () -> database.execute("select ?", List.of()));

            try {
                database.execute("select from where");
            } catch (SqliteException e) {
                Check.that("the failure is SQLite's own words: " + e.getMessage(),
                        e.getMessage().contains("syntax error"));
                Check.that("with its result code", e.code() > 0);
            }
        }
    }

    private static void persists() throws IOException {
        var file = Files.createTempDirectory("tuul-sqlite").resolve("notes.db");
        file.getParent().toFile().deleteOnExit();

        try (var database = Database.open(file)) {
            database.execute("create table notes (body text)");
            database.execute("insert into notes values (?)", "written to disk");
        }
        Check.that("a file database is a file", Files.isRegularFile(file));

        try (var database = Database.open(file)) {
            var bodies = new ArrayList<String>();
            try (var rows = database.query("select body from notes")) {
                while (rows.next()) bodies.add(rows.text(0));
            }
            Check.equal("and it is still there afterwards", List.of("written to disk"), bodies);
        }
    }

    private static long count(Database database, String sql, Object... parameters) {
        try (var rows = database.query(sql, parameters)) {
            return rows.next() ? rows.integer(0) : -1;
        }
    }
}
