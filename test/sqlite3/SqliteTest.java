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
