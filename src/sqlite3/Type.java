package sqlite3;

/// What SQLite says a value in a column actually is. A column has no type of
/// its own worth trusting — a value does.
public enum Type {

    INTEGER, REAL, TEXT, BLOB, NULL;

    static Type of(int code) {
        return switch (code) {
            case 1 -> INTEGER;
            case 2 -> REAL;
            case 3 -> TEXT;
            case 4 -> BLOB;
            default -> NULL;
        };
    }
}
