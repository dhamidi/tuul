package sqlite3;

/// Something SQLite refused to do, in its own words.
public final class SqliteException extends RuntimeException {

    private final int code;

    public SqliteException(String message, int code) {
        super(message);
        this.code = code;
    }

    public SqliteException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    /// The SQLite result code, or -1 when the failure happened on the Java side
    /// of the call.
    public int code() {
        return code;
    }
}
