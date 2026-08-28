package sqlite3;

/// A database this program must not use.
///
/// It is not a SQLite failure — SQLite opened the file and answered every
/// question. It is this program refusing a file whose shape it does not know:
/// another application's database, or one written by a later version of this
/// one. Both refusals exist because the alternative is a wrong answer from a
/// file that reads correctly.
public final class SchemaException extends RuntimeException {

    public SchemaException(String message) {
        super(message);
    }
}
