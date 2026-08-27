package sqlite3;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;

/// A cursor over the rows of one query: step, read, step again.
///
/// A row is not an object that outlives the step that produced it. Values are
/// copied into Java as they are read, and the cursor moves on — which is what
/// makes a query over more rows than fit in memory an ordinary thing to do.
public final class Rows implements AutoCloseable {

    private final Database database;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment statement;
    private Map<String, Integer> names;
    private boolean open = true;

    Rows(Database database, String sql, Object... parameters) {
        this.database = database;
        var out = arena.allocate(ADDRESS);
        var status = Api.sqlite3_prepare_v2(database.handle(), arena.allocateFrom(sql), -1, out, MemorySegment.NULL);
        statement = out.get(ADDRESS, 0);
        if (status != Api.SQLITE_OK) {
            var reason = database.message();
            arena.close();
            throw new SqliteException(reason + ": " + sql, status);
        }
        for (var parameter = 0; parameter < parameters.length; parameter++) bind(parameter + 1, parameters[parameter]);
    }

    /// Moves to the next row, and answers whether there is one.
    public boolean next() {
        var status = Api.sqlite3_step(statement);
        if (status == Api.SQLITE_ROW) return true;
        if (status == Api.SQLITE_DONE) return false;
        throw new SqliteException(database.message(), status);
    }

    /// The `sqlite3_stmt*` this cursor holds, for the calls [Api] has and this
    /// class does not.
    public MemorySegment statement() {
        return statement;
    }

    public int columns() {
        return Api.sqlite3_column_count(statement);
    }

    public String name(int column) {
        return Values.string(Api.sqlite3_column_name(statement, column));
    }

    /// The index of a named column, so a query can be read by name without the
    /// caller counting.
    public int column(String name) {
        if (names == null) names = index();
        var column = names.get(name);
        if (column == null) throw new SqliteException("no column named " + name + " in " + names.keySet(), -1);
        return column;
    }

    public Type type(int column) {
        return Type.of(Api.sqlite3_column_type(statement, column));
    }

    public boolean isNull(int column) {
        return type(column) == Type.NULL;
    }

    public String text(int column) {
        return Values.string(Api.sqlite3_column_text(statement, column), Api.sqlite3_column_bytes(statement, column));
    }

    public long integer(int column) {
        return Api.sqlite3_column_int64(statement, column);
    }

    public double real(int column) {
        return Api.sqlite3_column_double(statement, column);
    }

    public byte[] blob(int column) {
        return Values.bytes(Api.sqlite3_column_blob(statement, column), Api.sqlite3_column_bytes(statement, column));
    }

    public String text(String column) {
        return text(column(column));
    }

    public long integer(String column) {
        return integer(column(column));
    }

    public double real(String column) {
        return real(column(column));
    }

    public byte[] blob(String column) {
        return blob(column(column));
    }

    /// Whatever SQLite says the value is: a String, a Long, a Double, a byte[],
    /// or null.
    public Object value(int column) {
        return switch (type(column)) {
            case INTEGER -> integer(column);
            case REAL -> real(column);
            case TEXT -> text(column);
            case BLOB -> blob(column);
            case NULL -> null;
        };
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        Api.sqlite3_finalize(statement);
        arena.close();
    }

    private Map<String, Integer> index() {
        var index = new LinkedHashMap<String, Integer>();
        for (var column = 0; column < columns(); column++) index.putIfAbsent(name(column), column);
        return index;
    }

    /// Binds one parameter, taking the SQLite type from the Java one.
    private void bind(int parameter, Object value) {
        var status = switch (value) {
            case null -> Api.sqlite3_bind_null(statement, parameter);
            case String text -> Api.sqlite3_bind_text(
                    statement, parameter, arena.allocateFrom(text), -1, Values.TRANSIENT);
            case byte[] bytes -> Api.sqlite3_bind_blob(
                    statement, parameter, arena.allocateFrom(JAVA_BYTE, bytes), bytes.length, Values.TRANSIENT);
            case Boolean flag -> Api.sqlite3_bind_int64(statement, parameter, flag ? 1 : 0);
            case Float number -> Api.sqlite3_bind_double(statement, parameter, number);
            case Double number -> Api.sqlite3_bind_double(statement, parameter, number);
            case Number number -> Api.sqlite3_bind_int64(statement, parameter, number.longValue());
            default -> throw new SqliteException("cannot bind a " + value.getClass().getName(), -1);
        };
        if (status != Api.SQLITE_OK) throw new SqliteException(database.message(), status);
    }
}
