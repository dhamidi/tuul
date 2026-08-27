package sqlite;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import ffi.Library;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/// The C API as C spells it — one method per function, and nothing else.
/// Everything above this file speaks Java.
///
/// The signatures are the ones in `sqlite3.h`, written out by hand. There is no
/// generator to run and nothing to regenerate: a wrong descriptor is a compile
/// error here rather than a mystery at run time.
final class Sqlite {

    static final int OK = 0;
    static final int ROW = 100;
    static final int DONE = 101;
    static final int OPEN_READWRITE = 0x00000002;
    static final int OPEN_CREATE = 0x00000004;

    /// SQLITE_TRANSIENT — tells SQLite to copy the bytes, so a bound value does
    /// not have to outlive the call that bound it.
    static final MemorySegment TRANSIENT = MemorySegment.ofAddress(-1L);

    private static final Library SQLITE = Library.open("sqlite3");

    private static final MethodHandle OPEN = SQLITE.function(
            "sqlite3_open_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE = SQLITE.function(
            "sqlite3_close_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ERRMSG = SQLITE.function(
            "sqlite3_errmsg", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PREPARE = SQLITE.function(
            "sqlite3_prepare_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle STEP = SQLITE.function(
            "sqlite3_step", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle FINALIZE = SQLITE.function(
            "sqlite3_finalize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CHANGES = SQLITE.function(
            "sqlite3_changes", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle LAST_ID = SQLITE.function(
            "sqlite3_last_insert_rowid", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle VERSION = SQLITE.function(
            "sqlite3_libversion", FunctionDescriptor.of(ADDRESS));

    private static final MethodHandle BIND_NULL = SQLITE.function(
            "sqlite3_bind_null", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle BIND_INT64 = SQLITE.function(
            "sqlite3_bind_int64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
    private static final MethodHandle BIND_DOUBLE = SQLITE.function(
            "sqlite3_bind_double", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_DOUBLE));
    private static final MethodHandle BIND_TEXT = SQLITE.function(
            "sqlite3_bind_text", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle BIND_BLOB = SQLITE.function(
            "sqlite3_bind_blob", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    private static final MethodHandle COLUMN_COUNT = SQLITE.function(
            "sqlite3_column_count", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle COLUMN_NAME = SQLITE.function(
            "sqlite3_column_name", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle COLUMN_TYPE = SQLITE.function(
            "sqlite3_column_type", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle COLUMN_TEXT = SQLITE.function(
            "sqlite3_column_text", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle COLUMN_BLOB = SQLITE.function(
            "sqlite3_column_blob", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle COLUMN_BYTES = SQLITE.function(
            "sqlite3_column_bytes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle COLUMN_INT64 = SQLITE.function(
            "sqlite3_column_int64", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle COLUMN_DOUBLE = SQLITE.function(
            "sqlite3_column_double", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_INT));

    private Sqlite() {}

    static int open(MemorySegment file, MemorySegment out, int flags) {
        try {
            return (int) OPEN.invokeExact(file, out, flags, MemorySegment.NULL);
        } catch (Throwable e) {
            throw failed("sqlite3_open_v2", e);
        }
    }

    static int close(MemorySegment database) {
        try {
            return (int) CLOSE.invokeExact(database);
        } catch (Throwable e) {
            throw failed("sqlite3_close_v2", e);
        }
    }

    static String message(MemorySegment database) {
        try {
            return string((MemorySegment) ERRMSG.invokeExact(database));
        } catch (Throwable e) {
            throw failed("sqlite3_errmsg", e);
        }
    }

    static int prepare(MemorySegment database, MemorySegment sql, MemorySegment out) {
        try {
            return (int) PREPARE.invokeExact(database, sql, -1, out, MemorySegment.NULL);
        } catch (Throwable e) {
            throw failed("sqlite3_prepare_v2", e);
        }
    }

    static int step(MemorySegment statement) {
        try {
            return (int) STEP.invokeExact(statement);
        } catch (Throwable e) {
            throw failed("sqlite3_step", e);
        }
    }

    static int finish(MemorySegment statement) {
        try {
            return (int) FINALIZE.invokeExact(statement);
        } catch (Throwable e) {
            throw failed("sqlite3_finalize", e);
        }
    }

    static long changes(MemorySegment database) {
        try {
            return (int) CHANGES.invokeExact(database);
        } catch (Throwable e) {
            throw failed("sqlite3_changes", e);
        }
    }

    static long lastId(MemorySegment database) {
        try {
            return (long) LAST_ID.invokeExact(database);
        } catch (Throwable e) {
            throw failed("sqlite3_last_insert_rowid", e);
        }
    }

    static String version() {
        try {
            return string((MemorySegment) VERSION.invokeExact());
        } catch (Throwable e) {
            throw failed("sqlite3_libversion", e);
        }
    }

    static int bindNull(MemorySegment statement, int parameter) {
        try {
            return (int) BIND_NULL.invokeExact(statement, parameter);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_null", e);
        }
    }

    static int bindInteger(MemorySegment statement, int parameter, long value) {
        try {
            return (int) BIND_INT64.invokeExact(statement, parameter, value);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_int64", e);
        }
    }

    static int bindReal(MemorySegment statement, int parameter, double value) {
        try {
            return (int) BIND_DOUBLE.invokeExact(statement, parameter, value);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_double", e);
        }
    }

    static int bindText(MemorySegment statement, int parameter, MemorySegment text, int bytes) {
        try {
            return (int) BIND_TEXT.invokeExact(statement, parameter, text, bytes, TRANSIENT);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_text", e);
        }
    }

    static int bindBlob(MemorySegment statement, int parameter, MemorySegment bytes, int length) {
        try {
            return (int) BIND_BLOB.invokeExact(statement, parameter, bytes, length, TRANSIENT);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_blob", e);
        }
    }

    static int columns(MemorySegment statement) {
        try {
            return (int) COLUMN_COUNT.invokeExact(statement);
        } catch (Throwable e) {
            throw failed("sqlite3_column_count", e);
        }
    }

    static String columnName(MemorySegment statement, int column) {
        try {
            return string((MemorySegment) COLUMN_NAME.invokeExact(statement, column));
        } catch (Throwable e) {
            throw failed("sqlite3_column_name", e);
        }
    }

    static int columnType(MemorySegment statement, int column) {
        try {
            return (int) COLUMN_TYPE.invokeExact(statement, column);
        } catch (Throwable e) {
            throw failed("sqlite3_column_type", e);
        }
    }

    static int columnBytes(MemorySegment statement, int column) {
        try {
            return (int) COLUMN_BYTES.invokeExact(statement, column);
        } catch (Throwable e) {
            throw failed("sqlite3_column_bytes", e);
        }
    }

    /// The pointer stays valid only until the next step, which is why the value
    /// is copied into Java before returning.
    static String columnText(MemorySegment statement, int column) {
        try {
            var pointer = (MemorySegment) COLUMN_TEXT.invokeExact(statement, column);
            if (pointer.equals(MemorySegment.NULL)) return null;
            return pointer.reinterpret(columnBytes(statement, column) + 1L).getString(0);
        } catch (Throwable e) {
            throw failed("sqlite3_column_text", e);
        }
    }

    static byte[] columnBlob(MemorySegment statement, int column) {
        try {
            var pointer = (MemorySegment) COLUMN_BLOB.invokeExact(statement, column);
            var length = columnBytes(statement, column);
            if (pointer.equals(MemorySegment.NULL) || length == 0) return new byte[0];
            return pointer.reinterpret(length).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        } catch (Throwable e) {
            throw failed("sqlite3_column_blob", e);
        }
    }

    static long columnInteger(MemorySegment statement, int column) {
        try {
            return (long) COLUMN_INT64.invokeExact(statement, column);
        } catch (Throwable e) {
            throw failed("sqlite3_column_int64", e);
        }
    }

    static double columnReal(MemorySegment statement, int column) {
        try {
            return (double) COLUMN_DOUBLE.invokeExact(statement, column);
        } catch (Throwable e) {
            throw failed("sqlite3_column_double", e);
        }
    }

    /// A C string of unknown length, read up to its terminator.
    private static String string(MemorySegment pointer) {
        return pointer.equals(MemorySegment.NULL) ? null : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static SqliteException failed(String function, Throwable cause) {
        return new SqliteException("calling " + function + " failed", cause);
    }
}
