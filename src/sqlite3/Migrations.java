package sqlite3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// How a SQLite file says what shape it is in, and how it gets to the shape the
/// program expects.
///
/// SQLite has two numbers in every database header for this, and they cost
/// nothing: `application_id` says which program owns the file, and
/// `user_version` says which version of that program's schema it holds. A file
/// that carries both is an application file format. A file that carries neither
/// is a pile of tables that any program will happily add more tables to.
///
/// ## The two strategies
///
/// There are two honest answers to "the file is at version 3 and I want version
/// 5", and which one is right depends on what the file holds rather than on
/// taste.
///
/// [#derived(int, int)] is for a file that can be computed again. The version
/// is a stamp on a shape. Any mismatch throws the file away and builds it from
/// nothing, because the source of truth is somewhere else and rebuilding costs
/// only time. A search index is this.
///
/// [#durable(int)] is for a file that *is* the truth. It is never discarded,
/// because nothing could put it back. The version counts how many steps have
/// been applied, so the shape moves forward one step at a time and the data
/// stays. A log of what happened is this.
///
/// Naming them apart is the point. The rule that a derived file may be deleted
/// and a durable file may not is the difference between a rebuild and losing
/// somebody's data, and it should be visible at the call site rather than
/// implied by which method the author remembered.
///
/// ## Writing steps
///
/// ```
/// Migrations.durable(0x746c6f67)
///         .step("create table commands (seq integer primary key, body text not null);")
///         .step("alter table commands add column at integer not null default 0;")
///         .open(file);
/// ```
///
/// The version of a durable schema is the number of steps, so adding a step is
/// how the version is raised. There is no constant to remember to change, which
/// removes the way this goes wrong most often: a schema that was edited and a
/// version that was not.
///
/// **Never change a step that has shipped.** A file at version 2 has run the
/// first two steps as they were written on the day it was made, and this will
/// not run them again. Editing one changes what new files get and leaves old
/// files alone, so the two disagree for ever. Add a step instead.
///
/// Every step of one open runs in one transaction with the version stamp, so a
/// half-migrated file cannot exist. A step that fails leaves the file exactly
/// as it was.
public final class Migrations {

    /// A file that no program has claimed.
    ///
    /// SQLite writes zero here until somebody sets it, so an empty file and a
    /// file made before this class existed both read as zero. Both are adopted
    /// and stamped. The residual risk is real and worth naming: most programs
    /// never set an `application_id`, so an unrelated database that also reads
    /// as zero would be adopted too.
    private static final int UNCLAIMED = 0;

    private final int application;
    private final int version;
    private final boolean rebuilds;
    private final String connecting;
    private final List<String> steps;

    private Migrations(int application, int version, boolean rebuilds, String connecting, List<String> steps) {
        this.application = application;
        this.version = version;
        this.rebuilds = rebuilds;
        this.connecting = connecting;
        this.steps = List.copyOf(steps);
    }

    /// A file that can be computed again, at the version the program expects.
    ///
    /// The version is given rather than counted, because a derived file has no
    /// history to keep: the author raises it when the shape changes, and every
    /// older file is rebuilt. `version` is that number.
    public static Migrations derived(int application, int version) {
        return new Migrations(application, version, true, "", List.of());
    }

    /// A file that holds what nothing else holds. Its version is the number of
    /// steps, and its steps run in order on whatever is already there.
    ///
    /// **Write the first step so that it can run twice.** A database made
    /// before this class existed is stamped with nothing, so it reads as
    /// version zero and every step runs — over tables that are already there.
    /// `create table if not exists` adopts such a file and changes nothing;
    /// plain `create table` fails with *table already exists*, and the file it
    /// refuses is the one with all the data in it. Only the first step needs
    /// this care, because every later step runs on a file that is stamped and
    /// so runs exactly once.
    public static Migrations durable(int application) {
        return new Migrations(application, 0, false, "", List.of());
    }

    /// Pragmas to apply to every connection this opens.
    ///
    /// Some pragmas belong to the file and some to the connection, and the ones
    /// that belong to the connection have to be set again every time it is
    /// opened. `foreign_keys` is the one that bites: a schema that leans on
    /// cascades to forget a row silently stops cascading when nobody turns it
    /// on.
    public Migrations connecting(String pragmas) {
        return new Migrations(application, version, rebuilds, pragmas, steps);
    }

    /// One step. For a durable schema this also raises the version by one.
    public Migrations step(String sql) {
        var next = new ArrayList<>(steps);
        next.add(sql);
        return new Migrations(application, version, rebuilds, connecting, next);
    }

    /// The version this expects a file to be at.
    public int version() {
        return rebuilds ? version : steps.size();
    }

    /// Opens the file at the expected version, and answers with a connection to
    /// it.
    ///
    /// A caller gets back a database that is already the right shape. There is
    /// no second call to remember and no state where the file is open but not
    /// yet ready.
    public Database open(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        return rebuilds ? rebuilt(file) : migrated(file);
    }

    /// Derived: keep the file only if it is ours and current, and otherwise
    /// build a new one.
    ///
    /// Even the first statement has to read the file to find out, so a file
    /// that is not a database at all fails here and is answered the same way as
    /// one with the wrong version.
    private Database rebuilt(Path file) throws IOException {
        var kept = current(file);
        if (kept != null) return kept;

        discard(file);
        var fresh = connect(file);
        try {
            fresh.script("pragma journal_mode = wal");
            fresh.transaction(() -> {
                for (var step : steps) fresh.script(step);
                stamp(fresh, version);
            });
        } catch (RuntimeException broken) {
            fresh.close();
            discard(file);
            throw broken;
        }
        return fresh;
    }

    /// Ours, and this version of ours.
    ///
    /// Three answers, not two. A file that is ours and current is kept. A file
    /// that is ours and old, or that is not a database at all, is rebuilt. A
    /// file that says it belongs to another program is *refused*, and that
    /// third case is the reason this is not a boolean: rebuilding means
    /// deleting, and deleting a database because another program wrote it is
    /// the worst of the three things this could do.
    ///
    /// A file claiming nothing is treated as ours to take. An empty file reads
    /// that way, and so does a file that is not a database.
    private Database current(Path file) {
        Database database;
        try {
            database = connect(file);
        } catch (SqliteException notADatabase) {
            return null;
        }
        try {
            var owner = (int) number(database, "pragma application_id");
            if (owner != application && owner != UNCLAIMED) {
                throw new SchemaException(file + " belongs to another application"
                        + " (application_id " + hex(owner) + ", not " + hex(application) + ")"
                        + " — this would have deleted it to build a new one");
            }
            if (owner == application && number(database, "pragma user_version") == version) {
                return database;
            }
        } catch (SqliteException unreadable) {
            database.close();
            return null;
        } catch (SchemaException refused) {
            database.close();
            throw refused;
        }
        database.close();
        return null;
    }

    /// Durable: run whatever steps this file has not had, and keep everything
    /// it holds.
    private Database migrated(Path file) {
        var database = connect(file);
        try {
            var owner = (int) number(database, "pragma application_id");
            if (owner != application && owner != UNCLAIMED) {
                throw new SchemaException(file + " belongs to another application"
                        + " (application_id " + hex(owner) + ", not " + hex(application) + ")");
            }

            var at = (int) number(database, "pragma user_version");
            if (at > steps.size()) {
                throw new SchemaException(file + " is at version " + at + " and this program knows "
                        + steps.size() + " — it was written by a later version, and reading it here"
                        + " would answer questions about a shape this does not know");
            }

            database.script("pragma journal_mode = wal");
            if (at < steps.size() || owner == UNCLAIMED) {
                database.transaction(() -> {
                    for (var step : steps.subList(at, steps.size())) database.script(step);
                    stamp(database, steps.size());
                });
            }
            return database;
        } catch (RuntimeException refused) {
            database.close();
            throw refused;
        }
    }

    /// The settings that belong to the connection rather than to the file.
    private Database connect(Path file) {
        var database = Database.open(file);
        try {
            if (!connecting.isBlank()) database.script(connecting);
            return database;
        } catch (RuntimeException notADatabase) {
            database.close();
            throw notADatabase;
        }
    }

    /// Both numbers, written together.
    ///
    /// This runs inside the transaction that made the shape, so a file is never
    /// stamped with a version it did not reach.
    private void stamp(Database database, int reached) {
        database.script("pragma application_id = " + application + "; pragma user_version = " + reached + ";");
    }

    /// The write-ahead log and its shared memory belong to the file; leaving
    /// them behind would attach the old database to the new one.
    private static void discard(Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + "-wal"));
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + "-shm"));
    }

    private static long number(Database database, String pragma) {
        try (var rows = database.query(pragma)) {
            return rows.next() ? rows.integer(0) : 0;
        }
    }

    /// An application id is read in a hex dump, so an error about one says it
    /// the way somebody will see it.
    private static String hex(int application) {
        return "0x%08x".formatted(application);
    }
}
