package sqlite3;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// A statement prepared once and run many times.
///
/// Preparing is the expensive half of a small write: SQLite has to parse the
/// SQL and plan it, and doing that again for every row of a bulk load costs
/// more than the writing does. This prepares once and then binds new values
/// into the same statement, which is what `sqlite3_reset` and
/// `sqlite3_clear_bindings` are for.
///
/// [Database#query] is still the way to read. This is for the writes that
/// happen in a loop.
public final class Statement implements AutoCloseable {

    private final Database database;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment statement;
    private boolean open = true;

    private Statement(Database database, String sql) {
        this.database = database;
        var out = arena.allocate(ADDRESS);
        var status = Api.sqlite3_prepare_v2(database.handle(), arena.allocateFrom(sql), -1, out, MemorySegment.NULL);
        statement = out.get(ADDRESS, 0);
        if (status == Api.SQLITE_OK) return;

        var reason = database.message();
        arena.close();
        throw new SqliteException(reason + ": " + sql, status);
    }

    public static Statement of(Database database, String sql) {
        return new Statement(database, sql);
    }

    /// Binds the parameters, runs the statement to the end, and leaves it ready
    /// for the next run. Answers with the row id of the last insert, which is
    /// what a caller writing parents before children needs.
    public long run(Object... parameters) {
        for (var parameter = 0; parameter < parameters.length; parameter++) {
            var status = Values.bind(statement, arena, database, parameter + 1, parameters[parameter]);
            if (status != Api.SQLITE_OK) throw new SqliteException(database.message(), status);
        }
        step();
        Api.sqlite3_reset(statement);
        Api.sqlite3_clear_bindings(statement);
        return database.lastId();
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        Api.sqlite3_finalize(statement);
        arena.close();
    }

    private void step() {
        while (true) {
            var status = Api.sqlite3_step(statement);
            if (status == Api.SQLITE_DONE) return;
            if (status == Api.SQLITE_ROW) continue;
            var reason = database.message();
            Api.sqlite3_reset(statement);
            Api.sqlite3_clear_bindings(statement);
            throw new SqliteException(reason, status);
        }
    }
}
