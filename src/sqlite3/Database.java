package sqlite3;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/// A SQLite database, bound straight to the amalgamation in `native/sqlite3`.
///
/// This is the comfortable way in. [Api] is the whole C API underneath it,
/// generated from `sqlite3.h`, and anything this class does not cover can be
/// done there with the same handle.
///
/// ```
/// try (var database = Database.memory()) {
///     database.execute("create table notes (id integer primary key, body text)");
///     database.execute("insert into notes (body) values (?)", "the first note");
///     try (var rows = database.query("select id, body from notes")) {
///         while (rows.next()) System.out.println(rows.integer(0) + " " + rows.text(1));
///     }
/// }
/// ```
///
/// Rows are read as they come. Nothing collects a result set into a list on the
/// way past, so a query over a million rows costs one row of memory.
public final class Database implements AutoCloseable {

    private final Arena arena = Arena.ofShared();
    private final MemorySegment handle;
    private final String name;
    private boolean open = true;

    private Database(String name, int flags) {
        this.name = name;
        var out = arena.allocate(ADDRESS);
        var status = Api.sqlite3_open_v2(arena.allocateFrom(name), out, flags, MemorySegment.NULL);
        handle = out.get(ADDRESS, 0);
        if (status == Api.SQLITE_OK) return;

        var reason = handle.equals(MemorySegment.NULL) ? "out of memory" : message();
        Api.sqlite3_close_v2(handle);
        arena.close();
        throw new SqliteException("cannot open " + name + ": " + reason, status);
    }

    public static Database open(Path file) {
        return new Database(file.toString(), Api.SQLITE_OPEN_READWRITE | Api.SQLITE_OPEN_CREATE);
    }

    /// Opens an existing database without permission to change it.
    ///
    /// A catalog uses this path. It cannot create a missing file, migrate a
    /// schema, or accidentally turn a read into index work.
    public static Database readOnly(Path file) {
        return new Database(file.toString(), Api.SQLITE_OPEN_READONLY);
    }

    /// A database that lives and dies with this object.
    public static Database memory() {
        return new Database(":memory:", Api.SQLITE_OPEN_READWRITE | Api.SQLITE_OPEN_CREATE);
    }

    /// The version of SQLite that is actually loaded, which is the only version
    /// worth reporting.
    public static String version() {
        return Values.string(Api.sqlite3_libversion());
    }

    /// Runs a statement and answers with the number of rows it changed. Rows it
    /// produces, if any, are stepped past.
    public long execute(String sql, Object... parameters) {
        try (var rows = query(sql, parameters)) {
            while (rows.next()) {
                // a statement is allowed to return rows even when nobody asked
            }
        }
        return changes();
    }

    /// Prepares a query and hands back a cursor over its rows. Close it — a
    /// try-with-resources is the whole lifecycle.
    public Rows query(String sql, Object... parameters) {
        return new Rows(this, sql, parameters);
    }

    /// Runs several statements at once — a schema, a set of pragmas — the way
    /// `sqlite3_exec` does, because splitting SQL on semicolons is a parser
    /// nobody should write twice. Nothing it produces is read.
    public void script(String sql) {
        try (var arena = Arena.ofConfined()) {
            var message = arena.allocate(ADDRESS);
            var status = Api.sqlite3_exec(
                    handle, arena.allocateFrom(sql), MemorySegment.NULL, MemorySegment.NULL, message);
            if (status == Api.SQLITE_OK) return;
            var reason = Values.string(message.get(ADDRESS, 0));
            Api.sqlite3_free(message.get(ADDRESS, 0));
            throw new SqliteException(reason, status);
        }
    }

    /// Runs the work in one transaction: all of it or none of it.
    ///
    /// Bulk writing belongs in here. SQLite makes its changes durable at the
    /// end of a transaction, so a thousand statements outside one are a
    /// thousand trips to the disk. Transactions do not nest — this is the
    /// outermost one or it is a mistake.
    public void transaction(Runnable work) {
        execute("begin immediate");
        try {
            work.run();
        } catch (RuntimeException | Error e) {
            execute("rollback");
            throw e;
        }
        execute("commit");
    }

    public long changes() {
        return Api.sqlite3_changes(handle);
    }

    public long lastId() {
        return Api.sqlite3_last_insert_rowid(handle);
    }

    public String name() {
        return name;
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        Api.sqlite3_close_v2(handle);
        arena.close();
    }

    /// The `sqlite3*` this object holds, for the calls [Api] has and this
    /// class does not.
    public MemorySegment handle() {
        return handle;
    }

    String message() {
        return Values.string(Api.sqlite3_errmsg(handle));
    }
}
