package sqlite3;

import harness.Check;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public final class ApiTest {

    private ApiTest() {}

    public static void run() throws IOException {
        surface();
        constants();
        backup();
        variadic();
        metadata();
    }

    /// The point of generating the binding is that nobody had to choose which
    /// functions matter.
    private static void surface() {
        var functions = Arrays.stream(Api.class.getMethods())
                .filter(method -> method.getName().startsWith("sqlite3_"))
                .map(Method::getName)
                .distinct()
                .count();
        Check.that("the whole C API is bound, not a chosen few: " + functions, functions >= 290);

        for (var function : List.of(
                "sqlite3_backup_init", "sqlite3_blob_open", "sqlite3_serialize", "sqlite3_deserialize",
                "sqlite3_create_function_v2", "sqlite3_create_collation_v2", "sqlite3_column_database_name",
                "sqlite3_preupdate_count", "sqlite3_snapshot_get", "sqlite3_unlock_notify",
                "sqlite3_normalized_sql", "sqlite3_vfs_find", "sqlite3_mprintf", "sqlite3_wal_checkpoint_v2")) {
            Check.that(function + " is bound", bound(function));
        }
    }

    private static void constants() {
        Check.equal("result codes come across", 0, Api.SQLITE_OK);
        Check.equal("and the extended ones are still expressions",
                Api.SQLITE_CONSTRAINT | (8 << 8), Api.SQLITE_CONSTRAINT_UNIQUE);
        Check.equal("hex flags survive being read", 0x00000002, Api.SQLITE_OPEN_READWRITE);
        Check.equal("so do the strings", Database.version(), Api.SQLITE_VERSION);
        Check.that("and the limits are there too", Api.SQLITE_LIMIT_LENGTH == 0);
    }

    /// Nothing in the typed layer knows about backups. Everything needed to do
    /// one is in the generated API, against the handles the typed layer holds.
    private static void backup() throws IOException {
        var file = Files.createTempDirectory("tuul-api").resolve("copy.db");
        file.getParent().toFile().deleteOnExit();

        try (var source = Database.memory(); var arena = Arena.ofConfined()) {
            source.execute("create table t (n integer)");
            source.execute("insert into t values (1), (2), (3)");

            try (var target = Database.open(file)) {
                var main = arena.allocateFrom("main");
                var backup = Api.sqlite3_backup_init(target.handle(), main, source.handle(), main);
                Check.that("a backup starts", !backup.equals(MemorySegment.NULL));
                Check.equal("copies every page", Api.SQLITE_DONE, Api.sqlite3_backup_step(backup, -1));
                Check.equal("and finishes", Api.SQLITE_OK, Api.sqlite3_backup_finish(backup));
            }
        }

        try (var copy = Database.open(file); var rows = copy.query("select count(*) from t")) {
            rows.next();
            Check.equal("the rows came with it", 3L, rows.integer(0));
        }
    }

    /// A variadic function has no one signature, so the binding hands back a
    /// call site for the arguments the caller actually has.
    private static void variadic() {
        try (var arena = Arena.ofConfined()) {
            var printf = Api.sqlite3_mprintf(ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
            var text = call(printf, arena.allocateFrom("%s has %d"), arena.allocateFrom("tuul"), 3);
            Check.equal("a variadic call site works", "tuul has 3", Values.string(text));
            Api.sqlite3_free(text);
        }
    }

    /// sqlite3_column_table_name exists only when the amalgamation was compiled
    /// with SQLITE_ENABLE_COLUMN_METADATA — so this asks whether cflags reached
    /// both the library and the binding.
    private static void metadata() {
        try (var database = Database.memory()) {
            database.execute("create table notes (body text)");
            try (var rows = database.query("select body from notes")) {
                Check.equal("the flags reached the build",
                        "notes",
                        Values.string(Api.sqlite3_column_table_name(rows.statement(), 0)));
            }
        }
    }

    private static boolean bound(String function) {
        return Arrays.stream(Api.class.getMethods()).anyMatch(method -> method.getName().equals(function));
    }

    private static MemorySegment call(MethodHandle printf, MemorySegment format, MemorySegment text, int number) {
        try {
            return (MemorySegment) printf.invokeExact(format, text, number);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
