package sqlite;

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
        var status = Sqlite.prepare(database.handle(), arena.allocateFrom(sql), out);
        statement = out.get(ADDRESS, 0);
        if (status != Sqlite.OK) {
            var reason = database.message();
            arena.close();
            throw new SqliteException(reason + ": " + sql, status);
        }
        for (var parameter = 0; parameter < parameters.length; parameter++) bind(parameter + 1, parameters[parameter]);
    }

    /// Moves to the next row, and answers whether there is one.
    public boolean next() {
        var status = Sqlite.step(statement);
        if (status == Sqlite.ROW) return true;
        if (status == Sqlite.DONE) return false;
        throw new SqliteException(database.message(), status);
    }

    public int columns() {
        return Sqlite.columns(statement);
    }

    public String name(int column) {
        return Sqlite.columnName(statement, column);
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
        return Type.of(Sqlite.columnType(statement, column));
    }

    public boolean isNull(int column) {
        return type(column) == Type.NULL;
    }

    public String text(int column) {
        return Sqlite.columnText(statement, column);
    }

    public long integer(int column) {
        return Sqlite.columnInteger(statement, column);
    }

    public double real(int column) {
        return Sqlite.columnReal(statement, column);
    }

    public byte[] blob(int column) {
        return Sqlite.columnBlob(statement, column);
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
        Sqlite.finish(statement);
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
            case null -> Sqlite.bindNull(statement, parameter);
            case String text -> Sqlite.bindText(statement, parameter, arena.allocateFrom(text), -1);
            case byte[] bytes -> Sqlite.bindBlob(statement, parameter, arena.allocateFrom(JAVA_BYTE, bytes), bytes.length);
            case Boolean flag -> Sqlite.bindInteger(statement, parameter, flag ? 1 : 0);
            case Float number -> Sqlite.bindReal(statement, parameter, number);
            case Double number -> Sqlite.bindReal(statement, parameter, number);
            case Number number -> Sqlite.bindInteger(statement, parameter, number.longValue());
            default -> throw new SqliteException("cannot bind a " + value.getClass().getName(), -1);
        };
        if (status != Sqlite.OK) throw new SqliteException(database.message(), status);
    }
}
