package sqlite3;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import ffi.Library;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicReferenceArray;

/// The sqlite3 C API — all 296 functions of it — as C spells them.
///
/// Generated from ./native/sqlite3/sqlite3.h by `tuul bind sqlite3`. Do not edit: change the
/// header or the generator, and generate it again.
///
/// A function is linked the first time it is called and not
/// before: an API this size costs a third of a second to link in
/// full, and no program uses all of it. One the library does not
/// export fails only if something calls it.
///
/// A variadic function answers with a call site rather than a
/// result, since only the caller knows the shape of the arguments
/// it is about to pass.
public final class Api {

    private static final Library LIBRARY = Library.open("sqlite3");

    private static final AtomicReferenceArray<MethodHandle> FUNCTIONS = new AtomicReferenceArray<>(296);

    private Api() {}

    private static MethodHandle link(int index, String name, FunctionDescriptor descriptor) {
        var function = LIBRARY.optional(name, descriptor);
        if (function == null) throw new UnsatisfiedLinkError("this build of sqlite3 does not export " + name);
        FUNCTIONS.compareAndSet(index, null, function);
        return FUNCTIONS.get(index);
    }

    private static RuntimeException failed(String name, Throwable cause) {
        if (cause instanceof RuntimeException already) throw already;
        return new IllegalStateException("calling " + name + " failed", cause);
    }

    public static final String SQLITE_VERSION = "3.50.4";
    public static final int SQLITE_VERSION_NUMBER = 3050004;
    public static final String SQLITE_SOURCE_ID = "2025-07-30 19:33:53 4d8adfb30e03f9cf27f800a2c1ba3c48fb4ca1b08b0f5ed59a4d5ecbf45e20a3";
    public static final int SQLITE_OK = 0;
    public static final int SQLITE_ERROR = 1;
    public static final int SQLITE_INTERNAL = 2;
    public static final int SQLITE_PERM = 3;
    public static final int SQLITE_ABORT = 4;
    public static final int SQLITE_BUSY = 5;
    public static final int SQLITE_LOCKED = 6;
    public static final int SQLITE_NOMEM = 7;
    public static final int SQLITE_READONLY = 8;
    public static final int SQLITE_INTERRUPT = 9;
    public static final int SQLITE_IOERR = 10;
    public static final int SQLITE_CORRUPT = 11;
    public static final int SQLITE_NOTFOUND = 12;
    public static final int SQLITE_FULL = 13;
    public static final int SQLITE_CANTOPEN = 14;
    public static final int SQLITE_PROTOCOL = 15;
    public static final int SQLITE_EMPTY = 16;
    public static final int SQLITE_SCHEMA = 17;
    public static final int SQLITE_TOOBIG = 18;
    public static final int SQLITE_CONSTRAINT = 19;
    public static final int SQLITE_MISMATCH = 20;
    public static final int SQLITE_MISUSE = 21;
    public static final int SQLITE_NOLFS = 22;
    public static final int SQLITE_AUTH = 23;
    public static final int SQLITE_FORMAT = 24;
    public static final int SQLITE_RANGE = 25;
    public static final int SQLITE_NOTADB = 26;
    public static final int SQLITE_NOTICE = 27;
    public static final int SQLITE_WARNING = 28;
    public static final int SQLITE_ROW = 100;
    public static final int SQLITE_DONE = 101;
    public static final int SQLITE_ERROR_MISSING_COLLSEQ = (Api.SQLITE_ERROR | (1<<8));
    public static final int SQLITE_ERROR_RETRY = (Api.SQLITE_ERROR | (2<<8));
    public static final int SQLITE_ERROR_SNAPSHOT = (Api.SQLITE_ERROR | (3<<8));
    public static final int SQLITE_IOERR_READ = (Api.SQLITE_IOERR | (1<<8));
    public static final int SQLITE_IOERR_SHORT_READ = (Api.SQLITE_IOERR | (2<<8));
    public static final int SQLITE_IOERR_WRITE = (Api.SQLITE_IOERR | (3<<8));
    public static final int SQLITE_IOERR_FSYNC = (Api.SQLITE_IOERR | (4<<8));
    public static final int SQLITE_IOERR_DIR_FSYNC = (Api.SQLITE_IOERR | (5<<8));
    public static final int SQLITE_IOERR_TRUNCATE = (Api.SQLITE_IOERR | (6<<8));
    public static final int SQLITE_IOERR_FSTAT = (Api.SQLITE_IOERR | (7<<8));
    public static final int SQLITE_IOERR_UNLOCK = (Api.SQLITE_IOERR | (8<<8));
    public static final int SQLITE_IOERR_RDLOCK = (Api.SQLITE_IOERR | (9<<8));
    public static final int SQLITE_IOERR_DELETE = (Api.SQLITE_IOERR | (10<<8));
    public static final int SQLITE_IOERR_BLOCKED = (Api.SQLITE_IOERR | (11<<8));
    public static final int SQLITE_IOERR_NOMEM = (Api.SQLITE_IOERR | (12<<8));
    public static final int SQLITE_IOERR_ACCESS = (Api.SQLITE_IOERR | (13<<8));
    public static final int SQLITE_IOERR_CHECKRESERVEDLOCK = (Api.SQLITE_IOERR | (14<<8));
    public static final int SQLITE_IOERR_LOCK = (Api.SQLITE_IOERR | (15<<8));
    public static final int SQLITE_IOERR_CLOSE = (Api.SQLITE_IOERR | (16<<8));
    public static final int SQLITE_IOERR_DIR_CLOSE = (Api.SQLITE_IOERR | (17<<8));
    public static final int SQLITE_IOERR_SHMOPEN = (Api.SQLITE_IOERR | (18<<8));
    public static final int SQLITE_IOERR_SHMSIZE = (Api.SQLITE_IOERR | (19<<8));
    public static final int SQLITE_IOERR_SHMLOCK = (Api.SQLITE_IOERR | (20<<8));
    public static final int SQLITE_IOERR_SHMMAP = (Api.SQLITE_IOERR | (21<<8));
    public static final int SQLITE_IOERR_SEEK = (Api.SQLITE_IOERR | (22<<8));
    public static final int SQLITE_IOERR_DELETE_NOENT = (Api.SQLITE_IOERR | (23<<8));
    public static final int SQLITE_IOERR_MMAP = (Api.SQLITE_IOERR | (24<<8));
    public static final int SQLITE_IOERR_GETTEMPPATH = (Api.SQLITE_IOERR | (25<<8));
    public static final int SQLITE_IOERR_CONVPATH = (Api.SQLITE_IOERR | (26<<8));
    public static final int SQLITE_IOERR_VNODE = (Api.SQLITE_IOERR | (27<<8));
    public static final int SQLITE_IOERR_AUTH = (Api.SQLITE_IOERR | (28<<8));
    public static final int SQLITE_IOERR_BEGIN_ATOMIC = (Api.SQLITE_IOERR | (29<<8));
    public static final int SQLITE_IOERR_COMMIT_ATOMIC = (Api.SQLITE_IOERR | (30<<8));
    public static final int SQLITE_IOERR_ROLLBACK_ATOMIC = (Api.SQLITE_IOERR | (31<<8));
    public static final int SQLITE_IOERR_DATA = (Api.SQLITE_IOERR | (32<<8));
    public static final int SQLITE_IOERR_CORRUPTFS = (Api.SQLITE_IOERR | (33<<8));
    public static final int SQLITE_IOERR_IN_PAGE = (Api.SQLITE_IOERR | (34<<8));
    public static final int SQLITE_LOCKED_SHAREDCACHE = (Api.SQLITE_LOCKED | (1<<8));
    public static final int SQLITE_LOCKED_VTAB = (Api.SQLITE_LOCKED | (2<<8));
    public static final int SQLITE_BUSY_RECOVERY = (Api.SQLITE_BUSY | (1<<8));
    public static final int SQLITE_BUSY_SNAPSHOT = (Api.SQLITE_BUSY | (2<<8));
    public static final int SQLITE_BUSY_TIMEOUT = (Api.SQLITE_BUSY | (3<<8));
    public static final int SQLITE_CANTOPEN_NOTEMPDIR = (Api.SQLITE_CANTOPEN | (1<<8));
    public static final int SQLITE_CANTOPEN_ISDIR = (Api.SQLITE_CANTOPEN | (2<<8));
    public static final int SQLITE_CANTOPEN_FULLPATH = (Api.SQLITE_CANTOPEN | (3<<8));
    public static final int SQLITE_CANTOPEN_CONVPATH = (Api.SQLITE_CANTOPEN | (4<<8));
    public static final int SQLITE_CANTOPEN_DIRTYWAL = (Api.SQLITE_CANTOPEN | (5<<8));
    public static final int SQLITE_CANTOPEN_SYMLINK = (Api.SQLITE_CANTOPEN | (6<<8));
    public static final int SQLITE_CORRUPT_VTAB = (Api.SQLITE_CORRUPT | (1<<8));
    public static final int SQLITE_CORRUPT_SEQUENCE = (Api.SQLITE_CORRUPT | (2<<8));
    public static final int SQLITE_CORRUPT_INDEX = (Api.SQLITE_CORRUPT | (3<<8));
    public static final int SQLITE_READONLY_RECOVERY = (Api.SQLITE_READONLY | (1<<8));
    public static final int SQLITE_READONLY_CANTLOCK = (Api.SQLITE_READONLY | (2<<8));
    public static final int SQLITE_READONLY_ROLLBACK = (Api.SQLITE_READONLY | (3<<8));
    public static final int SQLITE_READONLY_DBMOVED = (Api.SQLITE_READONLY | (4<<8));
    public static final int SQLITE_READONLY_CANTINIT = (Api.SQLITE_READONLY | (5<<8));
    public static final int SQLITE_READONLY_DIRECTORY = (Api.SQLITE_READONLY | (6<<8));
    public static final int SQLITE_ABORT_ROLLBACK = (Api.SQLITE_ABORT | (2<<8));
    public static final int SQLITE_CONSTRAINT_CHECK = (Api.SQLITE_CONSTRAINT | (1<<8));
    public static final int SQLITE_CONSTRAINT_COMMITHOOK = (Api.SQLITE_CONSTRAINT | (2<<8));
    public static final int SQLITE_CONSTRAINT_FOREIGNKEY = (Api.SQLITE_CONSTRAINT | (3<<8));
    public static final int SQLITE_CONSTRAINT_FUNCTION = (Api.SQLITE_CONSTRAINT | (4<<8));
    public static final int SQLITE_CONSTRAINT_NOTNULL = (Api.SQLITE_CONSTRAINT | (5<<8));
    public static final int SQLITE_CONSTRAINT_PRIMARYKEY = (Api.SQLITE_CONSTRAINT | (6<<8));
    public static final int SQLITE_CONSTRAINT_TRIGGER = (Api.SQLITE_CONSTRAINT | (7<<8));
    public static final int SQLITE_CONSTRAINT_UNIQUE = (Api.SQLITE_CONSTRAINT | (8<<8));
    public static final int SQLITE_CONSTRAINT_VTAB = (Api.SQLITE_CONSTRAINT | (9<<8));
    public static final int SQLITE_CONSTRAINT_ROWID = (Api.SQLITE_CONSTRAINT |(10<<8));
    public static final int SQLITE_CONSTRAINT_PINNED = (Api.SQLITE_CONSTRAINT |(11<<8));
    public static final int SQLITE_CONSTRAINT_DATATYPE = (Api.SQLITE_CONSTRAINT |(12<<8));
    public static final int SQLITE_NOTICE_RECOVER_WAL = (Api.SQLITE_NOTICE | (1<<8));
    public static final int SQLITE_NOTICE_RECOVER_ROLLBACK = (Api.SQLITE_NOTICE | (2<<8));
    public static final int SQLITE_NOTICE_RBU = (Api.SQLITE_NOTICE | (3<<8));
    public static final int SQLITE_WARNING_AUTOINDEX = (Api.SQLITE_WARNING | (1<<8));
    public static final int SQLITE_AUTH_USER = (Api.SQLITE_AUTH | (1<<8));
    public static final int SQLITE_OK_LOAD_PERMANENTLY = (Api.SQLITE_OK | (1<<8));
    public static final int SQLITE_OK_SYMLINK = (Api.SQLITE_OK | (2<<8));
    public static final int SQLITE_OPEN_READONLY = 0x00000001;
    public static final int SQLITE_OPEN_READWRITE = 0x00000002;
    public static final int SQLITE_OPEN_CREATE = 0x00000004;
    public static final int SQLITE_OPEN_DELETEONCLOSE = 0x00000008;
    public static final int SQLITE_OPEN_EXCLUSIVE = 0x00000010;
    public static final int SQLITE_OPEN_AUTOPROXY = 0x00000020;
    public static final int SQLITE_OPEN_URI = 0x00000040;
    public static final int SQLITE_OPEN_MEMORY = 0x00000080;
    public static final int SQLITE_OPEN_MAIN_DB = 0x00000100;
    public static final int SQLITE_OPEN_TEMP_DB = 0x00000200;
    public static final int SQLITE_OPEN_TRANSIENT_DB = 0x00000400;
    public static final int SQLITE_OPEN_MAIN_JOURNAL = 0x00000800;
    public static final int SQLITE_OPEN_TEMP_JOURNAL = 0x00001000;
    public static final int SQLITE_OPEN_SUBJOURNAL = 0x00002000;
    public static final int SQLITE_OPEN_SUPER_JOURNAL = 0x00004000;
    public static final int SQLITE_OPEN_NOMUTEX = 0x00008000;
    public static final int SQLITE_OPEN_FULLMUTEX = 0x00010000;
    public static final int SQLITE_OPEN_SHAREDCACHE = 0x00020000;
    public static final int SQLITE_OPEN_PRIVATECACHE = 0x00040000;
    public static final int SQLITE_OPEN_WAL = 0x00080000;
    public static final int SQLITE_OPEN_NOFOLLOW = 0x01000000;
    public static final int SQLITE_OPEN_EXRESCODE = 0x02000000;
    public static final int SQLITE_OPEN_MASTER_JOURNAL = 0x00004000;
    public static final int SQLITE_IOCAP_ATOMIC = 0x00000001;
    public static final int SQLITE_IOCAP_ATOMIC512 = 0x00000002;
    public static final int SQLITE_IOCAP_ATOMIC1K = 0x00000004;
    public static final int SQLITE_IOCAP_ATOMIC2K = 0x00000008;
    public static final int SQLITE_IOCAP_ATOMIC4K = 0x00000010;
    public static final int SQLITE_IOCAP_ATOMIC8K = 0x00000020;
    public static final int SQLITE_IOCAP_ATOMIC16K = 0x00000040;
    public static final int SQLITE_IOCAP_ATOMIC32K = 0x00000080;
    public static final int SQLITE_IOCAP_ATOMIC64K = 0x00000100;
    public static final int SQLITE_IOCAP_SAFE_APPEND = 0x00000200;
    public static final int SQLITE_IOCAP_SEQUENTIAL = 0x00000400;
    public static final int SQLITE_IOCAP_UNDELETABLE_WHEN_OPEN = 0x00000800;
    public static final int SQLITE_IOCAP_POWERSAFE_OVERWRITE = 0x00001000;
    public static final int SQLITE_IOCAP_IMMUTABLE = 0x00002000;
    public static final int SQLITE_IOCAP_BATCH_ATOMIC = 0x00004000;
    public static final int SQLITE_IOCAP_SUBPAGE_READ = 0x00008000;
    public static final int SQLITE_LOCK_NONE = 0;
    public static final int SQLITE_LOCK_SHARED = 1;
    public static final int SQLITE_LOCK_RESERVED = 2;
    public static final int SQLITE_LOCK_PENDING = 3;
    public static final int SQLITE_LOCK_EXCLUSIVE = 4;
    public static final int SQLITE_SYNC_NORMAL = 0x00002;
    public static final int SQLITE_SYNC_FULL = 0x00003;
    public static final int SQLITE_SYNC_DATAONLY = 0x00010;
    public static final int SQLITE_FCNTL_LOCKSTATE = 1;
    public static final int SQLITE_FCNTL_GET_LOCKPROXYFILE = 2;
    public static final int SQLITE_FCNTL_SET_LOCKPROXYFILE = 3;
    public static final int SQLITE_FCNTL_LAST_ERRNO = 4;
    public static final int SQLITE_FCNTL_SIZE_HINT = 5;
    public static final int SQLITE_FCNTL_CHUNK_SIZE = 6;
    public static final int SQLITE_FCNTL_FILE_POINTER = 7;
    public static final int SQLITE_FCNTL_SYNC_OMITTED = 8;
    public static final int SQLITE_FCNTL_WIN32_AV_RETRY = 9;
    public static final int SQLITE_FCNTL_PERSIST_WAL = 10;
    public static final int SQLITE_FCNTL_OVERWRITE = 11;
    public static final int SQLITE_FCNTL_VFSNAME = 12;
    public static final int SQLITE_FCNTL_POWERSAFE_OVERWRITE = 13;
    public static final int SQLITE_FCNTL_PRAGMA = 14;
    public static final int SQLITE_FCNTL_BUSYHANDLER = 15;
    public static final int SQLITE_FCNTL_TEMPFILENAME = 16;
    public static final int SQLITE_FCNTL_MMAP_SIZE = 18;
    public static final int SQLITE_FCNTL_TRACE = 19;
    public static final int SQLITE_FCNTL_HAS_MOVED = 20;
    public static final int SQLITE_FCNTL_SYNC = 21;
    public static final int SQLITE_FCNTL_COMMIT_PHASETWO = 22;
    public static final int SQLITE_FCNTL_WIN32_SET_HANDLE = 23;
    public static final int SQLITE_FCNTL_WAL_BLOCK = 24;
    public static final int SQLITE_FCNTL_ZIPVFS = 25;
    public static final int SQLITE_FCNTL_RBU = 26;
    public static final int SQLITE_FCNTL_VFS_POINTER = 27;
    public static final int SQLITE_FCNTL_JOURNAL_POINTER = 28;
    public static final int SQLITE_FCNTL_WIN32_GET_HANDLE = 29;
    public static final int SQLITE_FCNTL_PDB = 30;
    public static final int SQLITE_FCNTL_BEGIN_ATOMIC_WRITE = 31;
    public static final int SQLITE_FCNTL_COMMIT_ATOMIC_WRITE = 32;
    public static final int SQLITE_FCNTL_ROLLBACK_ATOMIC_WRITE = 33;
    public static final int SQLITE_FCNTL_LOCK_TIMEOUT = 34;
    public static final int SQLITE_FCNTL_DATA_VERSION = 35;
    public static final int SQLITE_FCNTL_SIZE_LIMIT = 36;
    public static final int SQLITE_FCNTL_CKPT_DONE = 37;
    public static final int SQLITE_FCNTL_RESERVE_BYTES = 38;
    public static final int SQLITE_FCNTL_CKPT_START = 39;
    public static final int SQLITE_FCNTL_EXTERNAL_READER = 40;
    public static final int SQLITE_FCNTL_CKSM_FILE = 41;
    public static final int SQLITE_FCNTL_RESET_CACHE = 42;
    public static final int SQLITE_FCNTL_NULL_IO = 43;
    public static final int SQLITE_FCNTL_BLOCK_ON_CONNECT = 44;
    public static final int SQLITE_GET_LOCKPROXYFILE = Api.SQLITE_FCNTL_GET_LOCKPROXYFILE;
    public static final int SQLITE_SET_LOCKPROXYFILE = Api.SQLITE_FCNTL_SET_LOCKPROXYFILE;
    public static final int SQLITE_LAST_ERRNO = Api.SQLITE_FCNTL_LAST_ERRNO;
    public static final int SQLITE_ACCESS_EXISTS = 0;
    public static final int SQLITE_ACCESS_READWRITE = 1;
    public static final int SQLITE_ACCESS_READ = 2;
    public static final int SQLITE_SHM_UNLOCK = 1;
    public static final int SQLITE_SHM_LOCK = 2;
    public static final int SQLITE_SHM_SHARED = 4;
    public static final int SQLITE_SHM_EXCLUSIVE = 8;
    public static final int SQLITE_SHM_NLOCK = 8;
    public static final int SQLITE_CONFIG_SINGLETHREAD = 1;
    public static final int SQLITE_CONFIG_MULTITHREAD = 2;
    public static final int SQLITE_CONFIG_SERIALIZED = 3;
    public static final int SQLITE_CONFIG_MALLOC = 4;
    public static final int SQLITE_CONFIG_GETMALLOC = 5;
    public static final int SQLITE_CONFIG_SCRATCH = 6;
    public static final int SQLITE_CONFIG_PAGECACHE = 7;
    public static final int SQLITE_CONFIG_HEAP = 8;
    public static final int SQLITE_CONFIG_MEMSTATUS = 9;
    public static final int SQLITE_CONFIG_MUTEX = 10;
    public static final int SQLITE_CONFIG_GETMUTEX = 11;
    public static final int SQLITE_CONFIG_LOOKASIDE = 13;
    public static final int SQLITE_CONFIG_PCACHE = 14;
    public static final int SQLITE_CONFIG_GETPCACHE = 15;
    public static final int SQLITE_CONFIG_LOG = 16;
    public static final int SQLITE_CONFIG_URI = 17;
    public static final int SQLITE_CONFIG_PCACHE2 = 18;
    public static final int SQLITE_CONFIG_GETPCACHE2 = 19;
    public static final int SQLITE_CONFIG_COVERING_INDEX_SCAN = 20;
    public static final int SQLITE_CONFIG_SQLLOG = 21;
    public static final int SQLITE_CONFIG_MMAP_SIZE = 22;
    public static final int SQLITE_CONFIG_WIN32_HEAPSIZE = 23;
    public static final int SQLITE_CONFIG_PCACHE_HDRSZ = 24;
    public static final int SQLITE_CONFIG_PMASZ = 25;
    public static final int SQLITE_CONFIG_STMTJRNL_SPILL = 26;
    public static final int SQLITE_CONFIG_SMALL_MALLOC = 27;
    public static final int SQLITE_CONFIG_SORTERREF_SIZE = 28;
    public static final int SQLITE_CONFIG_MEMDB_MAXSIZE = 29;
    public static final int SQLITE_CONFIG_ROWID_IN_VIEW = 30;
    public static final int SQLITE_DBCONFIG_MAINDBNAME = 1000;
    public static final int SQLITE_DBCONFIG_LOOKASIDE = 1001;
    public static final int SQLITE_DBCONFIG_ENABLE_FKEY = 1002;
    public static final int SQLITE_DBCONFIG_ENABLE_TRIGGER = 1003;
    public static final int SQLITE_DBCONFIG_ENABLE_FTS3_TOKENIZER = 1004;
    public static final int SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION = 1005;
    public static final int SQLITE_DBCONFIG_NO_CKPT_ON_CLOSE = 1006;
    public static final int SQLITE_DBCONFIG_ENABLE_QPSG = 1007;
    public static final int SQLITE_DBCONFIG_TRIGGER_EQP = 1008;
    public static final int SQLITE_DBCONFIG_RESET_DATABASE = 1009;
    public static final int SQLITE_DBCONFIG_DEFENSIVE = 1010;
    public static final int SQLITE_DBCONFIG_WRITABLE_SCHEMA = 1011;
    public static final int SQLITE_DBCONFIG_LEGACY_ALTER_TABLE = 1012;
    public static final int SQLITE_DBCONFIG_DQS_DML = 1013;
    public static final int SQLITE_DBCONFIG_DQS_DDL = 1014;
    public static final int SQLITE_DBCONFIG_ENABLE_VIEW = 1015;
    public static final int SQLITE_DBCONFIG_LEGACY_FILE_FORMAT = 1016;
    public static final int SQLITE_DBCONFIG_TRUSTED_SCHEMA = 1017;
    public static final int SQLITE_DBCONFIG_STMT_SCANSTATUS = 1018;
    public static final int SQLITE_DBCONFIG_REVERSE_SCANORDER = 1019;
    public static final int SQLITE_DBCONFIG_ENABLE_ATTACH_CREATE = 1020;
    public static final int SQLITE_DBCONFIG_ENABLE_ATTACH_WRITE = 1021;
    public static final int SQLITE_DBCONFIG_ENABLE_COMMENTS = 1022;
    public static final int SQLITE_DBCONFIG_MAX = 1022;
    public static final int SQLITE_SETLK_BLOCK_ON_CONNECT = 0x01;
    public static final int SQLITE_DENY = 1;
    public static final int SQLITE_IGNORE = 2;
    public static final int SQLITE_CREATE_INDEX = 1;
    public static final int SQLITE_CREATE_TABLE = 2;
    public static final int SQLITE_CREATE_TEMP_INDEX = 3;
    public static final int SQLITE_CREATE_TEMP_TABLE = 4;
    public static final int SQLITE_CREATE_TEMP_TRIGGER = 5;
    public static final int SQLITE_CREATE_TEMP_VIEW = 6;
    public static final int SQLITE_CREATE_TRIGGER = 7;
    public static final int SQLITE_CREATE_VIEW = 8;
    public static final int SQLITE_DELETE = 9;
    public static final int SQLITE_DROP_INDEX = 10;
    public static final int SQLITE_DROP_TABLE = 11;
    public static final int SQLITE_DROP_TEMP_INDEX = 12;
    public static final int SQLITE_DROP_TEMP_TABLE = 13;
    public static final int SQLITE_DROP_TEMP_TRIGGER = 14;
    public static final int SQLITE_DROP_TEMP_VIEW = 15;
    public static final int SQLITE_DROP_TRIGGER = 16;
    public static final int SQLITE_DROP_VIEW = 17;
    public static final int SQLITE_INSERT = 18;
    public static final int SQLITE_PRAGMA = 19;
    public static final int SQLITE_READ = 20;
    public static final int SQLITE_SELECT = 21;
    public static final int SQLITE_TRANSACTION = 22;
    public static final int SQLITE_UPDATE = 23;
    public static final int SQLITE_ATTACH = 24;
    public static final int SQLITE_DETACH = 25;
    public static final int SQLITE_ALTER_TABLE = 26;
    public static final int SQLITE_REINDEX = 27;
    public static final int SQLITE_ANALYZE = 28;
    public static final int SQLITE_CREATE_VTABLE = 29;
    public static final int SQLITE_DROP_VTABLE = 30;
    public static final int SQLITE_FUNCTION = 31;
    public static final int SQLITE_SAVEPOINT = 32;
    public static final int SQLITE_COPY = 0;
    public static final int SQLITE_RECURSIVE = 33;
    public static final int SQLITE_TRACE_STMT = 0x01;
    public static final int SQLITE_TRACE_PROFILE = 0x02;
    public static final int SQLITE_TRACE_ROW = 0x04;
    public static final int SQLITE_TRACE_CLOSE = 0x08;
    public static final int SQLITE_LIMIT_LENGTH = 0;
    public static final int SQLITE_LIMIT_SQL_LENGTH = 1;
    public static final int SQLITE_LIMIT_COLUMN = 2;
    public static final int SQLITE_LIMIT_EXPR_DEPTH = 3;
    public static final int SQLITE_LIMIT_COMPOUND_SELECT = 4;
    public static final int SQLITE_LIMIT_VDBE_OP = 5;
    public static final int SQLITE_LIMIT_FUNCTION_ARG = 6;
    public static final int SQLITE_LIMIT_ATTACHED = 7;
    public static final int SQLITE_LIMIT_LIKE_PATTERN_LENGTH = 8;
    public static final int SQLITE_LIMIT_VARIABLE_NUMBER = 9;
    public static final int SQLITE_LIMIT_TRIGGER_DEPTH = 10;
    public static final int SQLITE_LIMIT_WORKER_THREADS = 11;
    public static final int SQLITE_PREPARE_PERSISTENT = 0x01;
    public static final int SQLITE_PREPARE_NORMALIZE = 0x02;
    public static final int SQLITE_PREPARE_NO_VTAB = 0x04;
    public static final int SQLITE_PREPARE_DONT_LOG = 0x10;
    public static final int SQLITE_INTEGER = 1;
    public static final int SQLITE_FLOAT = 2;
    public static final int SQLITE_BLOB = 4;
    public static final int SQLITE_NULL = 5;
    public static final int SQLITE_TEXT = 3;
    public static final int SQLITE_UTF8 = 1;
    public static final int SQLITE_UTF16LE = 2;
    public static final int SQLITE_UTF16BE = 3;
    public static final int SQLITE_UTF16 = 4;
    public static final int SQLITE_ANY = 5;
    public static final int SQLITE_UTF16_ALIGNED = 8;
    public static final int SQLITE_DETERMINISTIC = 0x000000800;
    public static final int SQLITE_DIRECTONLY = 0x000080000;
    public static final int SQLITE_SUBTYPE = 0x000100000;
    public static final int SQLITE_INNOCUOUS = 0x000200000;
    public static final int SQLITE_RESULT_SUBTYPE = 0x001000000;
    public static final int SQLITE_SELFORDER1 = 0x002000000;
    public static final int SQLITE_WIN32_DATA_DIRECTORY_TYPE = 1;
    public static final int SQLITE_WIN32_TEMP_DIRECTORY_TYPE = 2;
    public static final int SQLITE_TXN_NONE = 0;
    public static final int SQLITE_TXN_READ = 1;
    public static final int SQLITE_TXN_WRITE = 2;
    public static final int SQLITE_INDEX_SCAN_UNIQUE = 0x00000001;
    public static final int SQLITE_INDEX_SCAN_HEX = 0x00000002;
    public static final int SQLITE_INDEX_CONSTRAINT_EQ = 2;
    public static final int SQLITE_INDEX_CONSTRAINT_GT = 4;
    public static final int SQLITE_INDEX_CONSTRAINT_LE = 8;
    public static final int SQLITE_INDEX_CONSTRAINT_LT = 16;
    public static final int SQLITE_INDEX_CONSTRAINT_GE = 32;
    public static final int SQLITE_INDEX_CONSTRAINT_MATCH = 64;
    public static final int SQLITE_INDEX_CONSTRAINT_LIKE = 65;
    public static final int SQLITE_INDEX_CONSTRAINT_GLOB = 66;
    public static final int SQLITE_INDEX_CONSTRAINT_REGEXP = 67;
    public static final int SQLITE_INDEX_CONSTRAINT_NE = 68;
    public static final int SQLITE_INDEX_CONSTRAINT_ISNOT = 69;
    public static final int SQLITE_INDEX_CONSTRAINT_ISNOTNULL = 70;
    public static final int SQLITE_INDEX_CONSTRAINT_ISNULL = 71;
    public static final int SQLITE_INDEX_CONSTRAINT_IS = 72;
    public static final int SQLITE_INDEX_CONSTRAINT_LIMIT = 73;
    public static final int SQLITE_INDEX_CONSTRAINT_OFFSET = 74;
    public static final int SQLITE_INDEX_CONSTRAINT_FUNCTION = 150;
    public static final int SQLITE_MUTEX_FAST = 0;
    public static final int SQLITE_MUTEX_RECURSIVE = 1;
    public static final int SQLITE_MUTEX_STATIC_MAIN = 2;
    public static final int SQLITE_MUTEX_STATIC_MEM = 3;
    public static final int SQLITE_MUTEX_STATIC_MEM2 = 4;
    public static final int SQLITE_MUTEX_STATIC_OPEN = 4;
    public static final int SQLITE_MUTEX_STATIC_PRNG = 5;
    public static final int SQLITE_MUTEX_STATIC_LRU = 6;
    public static final int SQLITE_MUTEX_STATIC_LRU2 = 7;
    public static final int SQLITE_MUTEX_STATIC_PMEM = 7;
    public static final int SQLITE_MUTEX_STATIC_APP1 = 8;
    public static final int SQLITE_MUTEX_STATIC_APP2 = 9;
    public static final int SQLITE_MUTEX_STATIC_APP3 = 10;
    public static final int SQLITE_MUTEX_STATIC_VFS1 = 11;
    public static final int SQLITE_MUTEX_STATIC_VFS2 = 12;
    public static final int SQLITE_MUTEX_STATIC_VFS3 = 13;
    public static final int SQLITE_MUTEX_STATIC_MASTER = 2;
    public static final int SQLITE_TESTCTRL_FIRST = 5;
    public static final int SQLITE_TESTCTRL_PRNG_SAVE = 5;
    public static final int SQLITE_TESTCTRL_PRNG_RESTORE = 6;
    public static final int SQLITE_TESTCTRL_PRNG_RESET = 7;
    public static final int SQLITE_TESTCTRL_FK_NO_ACTION = 7;
    public static final int SQLITE_TESTCTRL_BITVEC_TEST = 8;
    public static final int SQLITE_TESTCTRL_FAULT_INSTALL = 9;
    public static final int SQLITE_TESTCTRL_BENIGN_MALLOC_HOOKS = 10;
    public static final int SQLITE_TESTCTRL_PENDING_BYTE = 11;
    public static final int SQLITE_TESTCTRL_ASSERT = 12;
    public static final int SQLITE_TESTCTRL_ALWAYS = 13;
    public static final int SQLITE_TESTCTRL_RESERVE = 14;
    public static final int SQLITE_TESTCTRL_JSON_SELFCHECK = 14;
    public static final int SQLITE_TESTCTRL_OPTIMIZATIONS = 15;
    public static final int SQLITE_TESTCTRL_ISKEYWORD = 16;
    public static final int SQLITE_TESTCTRL_GETOPT = 16;
    public static final int SQLITE_TESTCTRL_SCRATCHMALLOC = 17;
    public static final int SQLITE_TESTCTRL_INTERNAL_FUNCTIONS = 17;
    public static final int SQLITE_TESTCTRL_LOCALTIME_FAULT = 18;
    public static final int SQLITE_TESTCTRL_EXPLAIN_STMT = 19;
    public static final int SQLITE_TESTCTRL_ONCE_RESET_THRESHOLD = 19;
    public static final int SQLITE_TESTCTRL_NEVER_CORRUPT = 20;
    public static final int SQLITE_TESTCTRL_VDBE_COVERAGE = 21;
    public static final int SQLITE_TESTCTRL_BYTEORDER = 22;
    public static final int SQLITE_TESTCTRL_ISINIT = 23;
    public static final int SQLITE_TESTCTRL_SORTER_MMAP = 24;
    public static final int SQLITE_TESTCTRL_IMPOSTER = 25;
    public static final int SQLITE_TESTCTRL_PARSER_COVERAGE = 26;
    public static final int SQLITE_TESTCTRL_RESULT_INTREAL = 27;
    public static final int SQLITE_TESTCTRL_PRNG_SEED = 28;
    public static final int SQLITE_TESTCTRL_EXTRA_SCHEMA_CHECKS = 29;
    public static final int SQLITE_TESTCTRL_SEEK_COUNT = 30;
    public static final int SQLITE_TESTCTRL_TRACEFLAGS = 31;
    public static final int SQLITE_TESTCTRL_TUNE = 32;
    public static final int SQLITE_TESTCTRL_LOGEST = 33;
    public static final int SQLITE_TESTCTRL_USELONGDOUBLE = 34;
    public static final int SQLITE_TESTCTRL_LAST = 34;
    public static final int SQLITE_STATUS_MEMORY_USED = 0;
    public static final int SQLITE_STATUS_PAGECACHE_USED = 1;
    public static final int SQLITE_STATUS_PAGECACHE_OVERFLOW = 2;
    public static final int SQLITE_STATUS_SCRATCH_USED = 3;
    public static final int SQLITE_STATUS_SCRATCH_OVERFLOW = 4;
    public static final int SQLITE_STATUS_MALLOC_SIZE = 5;
    public static final int SQLITE_STATUS_PARSER_STACK = 6;
    public static final int SQLITE_STATUS_PAGECACHE_SIZE = 7;
    public static final int SQLITE_STATUS_SCRATCH_SIZE = 8;
    public static final int SQLITE_STATUS_MALLOC_COUNT = 9;
    public static final int SQLITE_DBSTATUS_LOOKASIDE_USED = 0;
    public static final int SQLITE_DBSTATUS_CACHE_USED = 1;
    public static final int SQLITE_DBSTATUS_SCHEMA_USED = 2;
    public static final int SQLITE_DBSTATUS_STMT_USED = 3;
    public static final int SQLITE_DBSTATUS_LOOKASIDE_HIT = 4;
    public static final int SQLITE_DBSTATUS_LOOKASIDE_MISS_SIZE = 5;
    public static final int SQLITE_DBSTATUS_LOOKASIDE_MISS_FULL = 6;
    public static final int SQLITE_DBSTATUS_CACHE_HIT = 7;
    public static final int SQLITE_DBSTATUS_CACHE_MISS = 8;
    public static final int SQLITE_DBSTATUS_CACHE_WRITE = 9;
    public static final int SQLITE_DBSTATUS_DEFERRED_FKS = 10;
    public static final int SQLITE_DBSTATUS_CACHE_USED_SHARED = 11;
    public static final int SQLITE_DBSTATUS_CACHE_SPILL = 12;
    public static final int SQLITE_DBSTATUS_MAX = 12;
    public static final int SQLITE_STMTSTATUS_FULLSCAN_STEP = 1;
    public static final int SQLITE_STMTSTATUS_SORT = 2;
    public static final int SQLITE_STMTSTATUS_AUTOINDEX = 3;
    public static final int SQLITE_STMTSTATUS_VM_STEP = 4;
    public static final int SQLITE_STMTSTATUS_REPREPARE = 5;
    public static final int SQLITE_STMTSTATUS_RUN = 6;
    public static final int SQLITE_STMTSTATUS_FILTER_MISS = 7;
    public static final int SQLITE_STMTSTATUS_FILTER_HIT = 8;
    public static final int SQLITE_STMTSTATUS_MEMUSED = 99;
    public static final int SQLITE_CHECKPOINT_PASSIVE = 0;
    public static final int SQLITE_CHECKPOINT_FULL = 1;
    public static final int SQLITE_CHECKPOINT_RESTART = 2;
    public static final int SQLITE_CHECKPOINT_TRUNCATE = 3;
    public static final int SQLITE_VTAB_CONSTRAINT_SUPPORT = 1;
    public static final int SQLITE_VTAB_INNOCUOUS = 2;
    public static final int SQLITE_VTAB_DIRECTONLY = 3;
    public static final int SQLITE_VTAB_USES_ALL_SCHEMAS = 4;
    public static final int SQLITE_ROLLBACK = 1;
    public static final int SQLITE_FAIL = 3;
    public static final int SQLITE_REPLACE = 5;
    public static final int SQLITE_SCANSTAT_NLOOP = 0;
    public static final int SQLITE_SCANSTAT_NVISIT = 1;
    public static final int SQLITE_SCANSTAT_EST = 2;
    public static final int SQLITE_SCANSTAT_NAME = 3;
    public static final int SQLITE_SCANSTAT_EXPLAIN = 4;
    public static final int SQLITE_SCANSTAT_SELECTID = 5;
    public static final int SQLITE_SCANSTAT_PARENTID = 6;
    public static final int SQLITE_SCANSTAT_NCYCLE = 7;
    public static final int SQLITE_SCANSTAT_COMPLEX = 0x0001;
    public static final int SQLITE_SERIALIZE_NOCOPY = 0x001;
    public static final int SQLITE_DESERIALIZE_FREEONCLOSE = 1;
    public static final int SQLITE_DESERIALIZE_RESIZEABLE = 2;
    public static final int SQLITE_DESERIALIZE_READONLY = 4;

    public static MemorySegment sqlite3_libversion() {
        var function = FUNCTIONS.get(0);
        if (function == null) function = link(0, "sqlite3_libversion",
                FunctionDescriptor.of(ADDRESS));
        try {
            return (MemorySegment) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_libversion", e);
        }
    }

    public static MemorySegment sqlite3_sourceid() {
        var function = FUNCTIONS.get(1);
        if (function == null) function = link(1, "sqlite3_sourceid",
                FunctionDescriptor.of(ADDRESS));
        try {
            return (MemorySegment) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_sourceid", e);
        }
    }

    public static int sqlite3_libversion_number() {
        var function = FUNCTIONS.get(2);
        if (function == null) function = link(2, "sqlite3_libversion_number",
                FunctionDescriptor.of(JAVA_INT));
        try {
            return (int) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_libversion_number", e);
        }
    }

    public static int sqlite3_compileoption_used(MemorySegment zOptName) {
        var function = FUNCTIONS.get(3);
        if (function == null) function = link(3, "sqlite3_compileoption_used",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(zOptName);
        } catch (Throwable e) {
            throw failed("sqlite3_compileoption_used", e);
        }
    }

    public static MemorySegment sqlite3_compileoption_get(int N) {
        var function = FUNCTIONS.get(4);
        if (function == null) function = link(4, "sqlite3_compileoption_get",
                FunctionDescriptor.of(ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(N);
        } catch (Throwable e) {
            throw failed("sqlite3_compileoption_get", e);
        }
    }

    public static int sqlite3_threadsafe() {
        var function = FUNCTIONS.get(5);
        if (function == null) function = link(5, "sqlite3_threadsafe",
                FunctionDescriptor.of(JAVA_INT));
        try {
            return (int) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_threadsafe", e);
        }
    }

    public static int sqlite3_close(MemorySegment arg0) {
        var function = FUNCTIONS.get(6);
        if (function == null) function = link(6, "sqlite3_close",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_close", e);
        }
    }

    public static int sqlite3_close_v2(MemorySegment arg0) {
        var function = FUNCTIONS.get(7);
        if (function == null) function = link(7, "sqlite3_close_v2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_close_v2", e);
        }
    }

    public static int sqlite3_exec(MemorySegment arg0, MemorySegment sql, MemorySegment callback, MemorySegment arg3, MemorySegment errmsg) {
        var function = FUNCTIONS.get(8);
        if (function == null) function = link(8, "sqlite3_exec",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, sql, callback, arg3, errmsg);
        } catch (Throwable e) {
            throw failed("sqlite3_exec", e);
        }
    }

    public static int sqlite3_initialize() {
        var function = FUNCTIONS.get(9);
        if (function == null) function = link(9, "sqlite3_initialize",
                FunctionDescriptor.of(JAVA_INT));
        try {
            return (int) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_initialize", e);
        }
    }

    public static int sqlite3_shutdown() {
        var function = FUNCTIONS.get(10);
        if (function == null) function = link(10, "sqlite3_shutdown",
                FunctionDescriptor.of(JAVA_INT));
        try {
            return (int) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_shutdown", e);
        }
    }

    public static int sqlite3_os_init() {
        var function = FUNCTIONS.get(11);
        if (function == null) function = link(11, "sqlite3_os_init",
                FunctionDescriptor.of(JAVA_INT));
        try {
            return (int) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_os_init", e);
        }
    }

    public static int sqlite3_os_end() {
        var function = FUNCTIONS.get(12);
        if (function == null) function = link(12, "sqlite3_os_end",
                FunctionDescriptor.of(JAVA_INT));
        try {
            return (int) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_os_end", e);
        }
    }

    /// Variadic. Give it the layouts of the extra arguments and it answers
    /// with a handle for that call: sqlite3_config(...).
    public static MethodHandle sqlite3_config(MemoryLayout... variadic) {
        return LIBRARY.variadic("sqlite3_config",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT), variadic);
    }

    /// Variadic. Give it the layouts of the extra arguments and it answers
    /// with a handle for that call: sqlite3_db_config(...).
    public static MethodHandle sqlite3_db_config(MemoryLayout... variadic) {
        return LIBRARY.variadic("sqlite3_db_config",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), variadic);
    }

    public static int sqlite3_extended_result_codes(MemorySegment arg0, int onoff) {
        var function = FUNCTIONS.get(15);
        if (function == null) function = link(15, "sqlite3_extended_result_codes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, onoff);
        } catch (Throwable e) {
            throw failed("sqlite3_extended_result_codes", e);
        }
    }

    public static long sqlite3_last_insert_rowid(MemorySegment arg0) {
        var function = FUNCTIONS.get(16);
        if (function == null) function = link(16, "sqlite3_last_insert_rowid",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        try {
            return (long) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_last_insert_rowid", e);
        }
    }

    public static void sqlite3_set_last_insert_rowid(MemorySegment arg0, long arg1) {
        var function = FUNCTIONS.get(17);
        if (function == null) function = link(17, "sqlite3_set_last_insert_rowid",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        try {
            function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_set_last_insert_rowid", e);
        }
    }

    public static int sqlite3_changes(MemorySegment arg0) {
        var function = FUNCTIONS.get(18);
        if (function == null) function = link(18, "sqlite3_changes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_changes", e);
        }
    }

    public static long sqlite3_changes64(MemorySegment arg0) {
        var function = FUNCTIONS.get(19);
        if (function == null) function = link(19, "sqlite3_changes64",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        try {
            return (long) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_changes64", e);
        }
    }

    public static int sqlite3_total_changes(MemorySegment arg0) {
        var function = FUNCTIONS.get(20);
        if (function == null) function = link(20, "sqlite3_total_changes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_total_changes", e);
        }
    }

    public static long sqlite3_total_changes64(MemorySegment arg0) {
        var function = FUNCTIONS.get(21);
        if (function == null) function = link(21, "sqlite3_total_changes64",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        try {
            return (long) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_total_changes64", e);
        }
    }

    public static void sqlite3_interrupt(MemorySegment arg0) {
        var function = FUNCTIONS.get(22);
        if (function == null) function = link(22, "sqlite3_interrupt",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_interrupt", e);
        }
    }

    public static int sqlite3_is_interrupted(MemorySegment arg0) {
        var function = FUNCTIONS.get(23);
        if (function == null) function = link(23, "sqlite3_is_interrupted",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_is_interrupted", e);
        }
    }

    public static int sqlite3_complete(MemorySegment sql) {
        var function = FUNCTIONS.get(24);
        if (function == null) function = link(24, "sqlite3_complete",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(sql);
        } catch (Throwable e) {
            throw failed("sqlite3_complete", e);
        }
    }

    public static int sqlite3_complete16(MemorySegment sql) {
        var function = FUNCTIONS.get(25);
        if (function == null) function = link(25, "sqlite3_complete16",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(sql);
        } catch (Throwable e) {
            throw failed("sqlite3_complete16", e);
        }
    }

    public static int sqlite3_busy_handler(MemorySegment arg0, MemorySegment arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(26);
        if (function == null) function = link(26, "sqlite3_busy_handler",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_busy_handler", e);
        }
    }

    public static int sqlite3_busy_timeout(MemorySegment arg0, int ms) {
        var function = FUNCTIONS.get(27);
        if (function == null) function = link(27, "sqlite3_busy_timeout",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, ms);
        } catch (Throwable e) {
            throw failed("sqlite3_busy_timeout", e);
        }
    }

    public static int sqlite3_setlk_timeout(MemorySegment arg0, int ms, int flags) {
        var function = FUNCTIONS.get(28);
        if (function == null) function = link(28, "sqlite3_setlk_timeout",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, ms, flags);
        } catch (Throwable e) {
            throw failed("sqlite3_setlk_timeout", e);
        }
    }

    public static int sqlite3_get_table(MemorySegment db, MemorySegment zSql, MemorySegment pazResult, MemorySegment pnRow, MemorySegment pnColumn, MemorySegment pzErrmsg) {
        var function = FUNCTIONS.get(29);
        if (function == null) function = link(29, "sqlite3_get_table",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zSql, pazResult, pnRow, pnColumn, pzErrmsg);
        } catch (Throwable e) {
            throw failed("sqlite3_get_table", e);
        }
    }

    public static void sqlite3_free_table(MemorySegment result) {
        var function = FUNCTIONS.get(30);
        if (function == null) function = link(30, "sqlite3_free_table",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(result);
        } catch (Throwable e) {
            throw failed("sqlite3_free_table", e);
        }
    }

    /// Variadic. Give it the layouts of the extra arguments and it answers
    /// with a handle for that call: sqlite3_mprintf(...).
    public static MethodHandle sqlite3_mprintf(MemoryLayout... variadic) {
        return LIBRARY.variadic("sqlite3_mprintf",
                FunctionDescriptor.of(ADDRESS, ADDRESS), variadic);
    }

    /// Variadic. Give it the layouts of the extra arguments and it answers
    /// with a handle for that call: sqlite3_snprintf(...).
    public static MethodHandle sqlite3_snprintf(MemoryLayout... variadic) {
        return LIBRARY.variadic("sqlite3_snprintf",
                FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS), variadic);
    }

    public static MemorySegment sqlite3_malloc(int arg0) {
        var function = FUNCTIONS.get(33);
        if (function == null) function = link(33, "sqlite3_malloc",
                FunctionDescriptor.of(ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_malloc", e);
        }
    }

    public static MemorySegment sqlite3_malloc64(long arg0) {
        var function = FUNCTIONS.get(34);
        if (function == null) function = link(34, "sqlite3_malloc64",
                FunctionDescriptor.of(ADDRESS, JAVA_LONG));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_malloc64", e);
        }
    }

    public static MemorySegment sqlite3_realloc(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(35);
        if (function == null) function = link(35, "sqlite3_realloc",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_realloc", e);
        }
    }

    public static MemorySegment sqlite3_realloc64(MemorySegment arg0, long arg1) {
        var function = FUNCTIONS.get(36);
        if (function == null) function = link(36, "sqlite3_realloc64",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_realloc64", e);
        }
    }

    public static void sqlite3_free(MemorySegment arg0) {
        var function = FUNCTIONS.get(37);
        if (function == null) function = link(37, "sqlite3_free",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_free", e);
        }
    }

    public static long sqlite3_msize(MemorySegment arg0) {
        var function = FUNCTIONS.get(38);
        if (function == null) function = link(38, "sqlite3_msize",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        try {
            return (long) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_msize", e);
        }
    }

    public static long sqlite3_memory_used() {
        var function = FUNCTIONS.get(39);
        if (function == null) function = link(39, "sqlite3_memory_used",
                FunctionDescriptor.of(JAVA_LONG));
        try {
            return (long) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_memory_used", e);
        }
    }

    public static long sqlite3_memory_highwater(int resetFlag) {
        var function = FUNCTIONS.get(40);
        if (function == null) function = link(40, "sqlite3_memory_highwater",
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
        try {
            return (long) function.invokeExact(resetFlag);
        } catch (Throwable e) {
            throw failed("sqlite3_memory_highwater", e);
        }
    }

    public static void sqlite3_randomness(int N, MemorySegment P) {
        var function = FUNCTIONS.get(41);
        if (function == null) function = link(41, "sqlite3_randomness",
                FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        try {
            function.invokeExact(N, P);
        } catch (Throwable e) {
            throw failed("sqlite3_randomness", e);
        }
    }

    public static int sqlite3_set_authorizer(MemorySegment arg0, MemorySegment xAuth, MemorySegment pUserData) {
        var function = FUNCTIONS.get(42);
        if (function == null) function = link(42, "sqlite3_set_authorizer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, xAuth, pUserData);
        } catch (Throwable e) {
            throw failed("sqlite3_set_authorizer", e);
        }
    }

    public static MemorySegment sqlite3_trace(MemorySegment arg0, MemorySegment xTrace, MemorySegment arg2) {
        var function = FUNCTIONS.get(43);
        if (function == null) function = link(43, "sqlite3_trace",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0, xTrace, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_trace", e);
        }
    }

    public static MemorySegment sqlite3_profile(MemorySegment arg0, MemorySegment xProfile, MemorySegment arg2) {
        var function = FUNCTIONS.get(44);
        if (function == null) function = link(44, "sqlite3_profile",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0, xProfile, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_profile", e);
        }
    }

    public static int sqlite3_trace_v2(MemorySegment arg0, int uMask, MemorySegment xCallback, MemorySegment pCtx) {
        var function = FUNCTIONS.get(45);
        if (function == null) function = link(45, "sqlite3_trace_v2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, uMask, xCallback, pCtx);
        } catch (Throwable e) {
            throw failed("sqlite3_trace_v2", e);
        }
    }

    public static void sqlite3_progress_handler(MemorySegment arg0, int arg1, MemorySegment arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(46);
        if (function == null) function = link(46, "sqlite3_progress_handler",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        try {
            function.invokeExact(arg0, arg1, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_progress_handler", e);
        }
    }

    public static int sqlite3_open(MemorySegment filename, MemorySegment ppDb) {
        var function = FUNCTIONS.get(47);
        if (function == null) function = link(47, "sqlite3_open",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(filename, ppDb);
        } catch (Throwable e) {
            throw failed("sqlite3_open", e);
        }
    }

    public static int sqlite3_open16(MemorySegment filename, MemorySegment ppDb) {
        var function = FUNCTIONS.get(48);
        if (function == null) function = link(48, "sqlite3_open16",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(filename, ppDb);
        } catch (Throwable e) {
            throw failed("sqlite3_open16", e);
        }
    }

    public static int sqlite3_open_v2(MemorySegment filename, MemorySegment ppDb, int flags, MemorySegment zVfs) {
        var function = FUNCTIONS.get(49);
        if (function == null) function = link(49, "sqlite3_open_v2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(filename, ppDb, flags, zVfs);
        } catch (Throwable e) {
            throw failed("sqlite3_open_v2", e);
        }
    }

    public static MemorySegment sqlite3_uri_parameter(MemorySegment z, MemorySegment zParam) {
        var function = FUNCTIONS.get(50);
        if (function == null) function = link(50, "sqlite3_uri_parameter",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(z, zParam);
        } catch (Throwable e) {
            throw failed("sqlite3_uri_parameter", e);
        }
    }

    public static int sqlite3_uri_boolean(MemorySegment z, MemorySegment zParam, int bDefault) {
        var function = FUNCTIONS.get(51);
        if (function == null) function = link(51, "sqlite3_uri_boolean",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(z, zParam, bDefault);
        } catch (Throwable e) {
            throw failed("sqlite3_uri_boolean", e);
        }
    }

    public static long sqlite3_uri_int64(MemorySegment arg0, MemorySegment arg1, long arg2) {
        var function = FUNCTIONS.get(52);
        if (function == null) function = link(52, "sqlite3_uri_int64",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
        try {
            return (long) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_uri_int64", e);
        }
    }

    public static MemorySegment sqlite3_uri_key(MemorySegment z, int N) {
        var function = FUNCTIONS.get(53);
        if (function == null) function = link(53, "sqlite3_uri_key",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(z, N);
        } catch (Throwable e) {
            throw failed("sqlite3_uri_key", e);
        }
    }

    public static MemorySegment sqlite3_filename_database(MemorySegment arg0) {
        var function = FUNCTIONS.get(54);
        if (function == null) function = link(54, "sqlite3_filename_database",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_filename_database", e);
        }
    }

    public static MemorySegment sqlite3_filename_journal(MemorySegment arg0) {
        var function = FUNCTIONS.get(55);
        if (function == null) function = link(55, "sqlite3_filename_journal",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_filename_journal", e);
        }
    }

    public static MemorySegment sqlite3_filename_wal(MemorySegment arg0) {
        var function = FUNCTIONS.get(56);
        if (function == null) function = link(56, "sqlite3_filename_wal",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_filename_wal", e);
        }
    }

    public static MemorySegment sqlite3_database_file_object(MemorySegment arg0) {
        var function = FUNCTIONS.get(57);
        if (function == null) function = link(57, "sqlite3_database_file_object",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_database_file_object", e);
        }
    }

    public static MemorySegment sqlite3_create_filename(MemorySegment zDatabase, MemorySegment zJournal, MemorySegment zWal, int nParam, MemorySegment azParam) {
        var function = FUNCTIONS.get(58);
        if (function == null) function = link(58, "sqlite3_create_filename",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(zDatabase, zJournal, zWal, nParam, azParam);
        } catch (Throwable e) {
            throw failed("sqlite3_create_filename", e);
        }
    }

    public static void sqlite3_free_filename(MemorySegment arg0) {
        var function = FUNCTIONS.get(59);
        if (function == null) function = link(59, "sqlite3_free_filename",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_free_filename", e);
        }
    }

    public static int sqlite3_errcode(MemorySegment db) {
        var function = FUNCTIONS.get(60);
        if (function == null) function = link(60, "sqlite3_errcode",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(db);
        } catch (Throwable e) {
            throw failed("sqlite3_errcode", e);
        }
    }

    public static int sqlite3_extended_errcode(MemorySegment db) {
        var function = FUNCTIONS.get(61);
        if (function == null) function = link(61, "sqlite3_extended_errcode",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(db);
        } catch (Throwable e) {
            throw failed("sqlite3_extended_errcode", e);
        }
    }

    public static MemorySegment sqlite3_errmsg(MemorySegment arg0) {
        var function = FUNCTIONS.get(62);
        if (function == null) function = link(62, "sqlite3_errmsg",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_errmsg", e);
        }
    }

    public static MemorySegment sqlite3_errmsg16(MemorySegment arg0) {
        var function = FUNCTIONS.get(63);
        if (function == null) function = link(63, "sqlite3_errmsg16",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_errmsg16", e);
        }
    }

    public static MemorySegment sqlite3_errstr(int arg0) {
        var function = FUNCTIONS.get(64);
        if (function == null) function = link(64, "sqlite3_errstr",
                FunctionDescriptor.of(ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_errstr", e);
        }
    }

    public static int sqlite3_error_offset(MemorySegment db) {
        var function = FUNCTIONS.get(65);
        if (function == null) function = link(65, "sqlite3_error_offset",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(db);
        } catch (Throwable e) {
            throw failed("sqlite3_error_offset", e);
        }
    }

    public static int sqlite3_limit(MemorySegment arg0, int id, int newVal) {
        var function = FUNCTIONS.get(66);
        if (function == null) function = link(66, "sqlite3_limit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, id, newVal);
        } catch (Throwable e) {
            throw failed("sqlite3_limit", e);
        }
    }

    public static int sqlite3_prepare(MemorySegment db, MemorySegment zSql, int nByte, MemorySegment ppStmt, MemorySegment pzTail) {
        var function = FUNCTIONS.get(67);
        if (function == null) function = link(67, "sqlite3_prepare",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zSql, nByte, ppStmt, pzTail);
        } catch (Throwable e) {
            throw failed("sqlite3_prepare", e);
        }
    }

    public static int sqlite3_prepare_v2(MemorySegment db, MemorySegment zSql, int nByte, MemorySegment ppStmt, MemorySegment pzTail) {
        var function = FUNCTIONS.get(68);
        if (function == null) function = link(68, "sqlite3_prepare_v2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zSql, nByte, ppStmt, pzTail);
        } catch (Throwable e) {
            throw failed("sqlite3_prepare_v2", e);
        }
    }

    public static int sqlite3_prepare_v3(MemorySegment db, MemorySegment zSql, int nByte, int prepFlags, MemorySegment ppStmt, MemorySegment pzTail) {
        var function = FUNCTIONS.get(69);
        if (function == null) function = link(69, "sqlite3_prepare_v3",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zSql, nByte, prepFlags, ppStmt, pzTail);
        } catch (Throwable e) {
            throw failed("sqlite3_prepare_v3", e);
        }
    }

    public static int sqlite3_prepare16(MemorySegment db, MemorySegment zSql, int nByte, MemorySegment ppStmt, MemorySegment pzTail) {
        var function = FUNCTIONS.get(70);
        if (function == null) function = link(70, "sqlite3_prepare16",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zSql, nByte, ppStmt, pzTail);
        } catch (Throwable e) {
            throw failed("sqlite3_prepare16", e);
        }
    }

    public static int sqlite3_prepare16_v2(MemorySegment db, MemorySegment zSql, int nByte, MemorySegment ppStmt, MemorySegment pzTail) {
        var function = FUNCTIONS.get(71);
        if (function == null) function = link(71, "sqlite3_prepare16_v2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zSql, nByte, ppStmt, pzTail);
        } catch (Throwable e) {
            throw failed("sqlite3_prepare16_v2", e);
        }
    }

    public static int sqlite3_prepare16_v3(MemorySegment db, MemorySegment zSql, int nByte, int prepFlags, MemorySegment ppStmt, MemorySegment pzTail) {
        var function = FUNCTIONS.get(72);
        if (function == null) function = link(72, "sqlite3_prepare16_v3",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zSql, nByte, prepFlags, ppStmt, pzTail);
        } catch (Throwable e) {
            throw failed("sqlite3_prepare16_v3", e);
        }
    }

    public static MemorySegment sqlite3_sql(MemorySegment pStmt) {
        var function = FUNCTIONS.get(73);
        if (function == null) function = link(73, "sqlite3_sql",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(pStmt);
        } catch (Throwable e) {
            throw failed("sqlite3_sql", e);
        }
    }

    public static MemorySegment sqlite3_expanded_sql(MemorySegment pStmt) {
        var function = FUNCTIONS.get(74);
        if (function == null) function = link(74, "sqlite3_expanded_sql",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(pStmt);
        } catch (Throwable e) {
            throw failed("sqlite3_expanded_sql", e);
        }
    }

    public static MemorySegment sqlite3_normalized_sql(MemorySegment pStmt) {
        var function = FUNCTIONS.get(75);
        if (function == null) function = link(75, "sqlite3_normalized_sql",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(pStmt);
        } catch (Throwable e) {
            throw failed("sqlite3_normalized_sql", e);
        }
    }

    public static int sqlite3_stmt_readonly(MemorySegment pStmt) {
        var function = FUNCTIONS.get(76);
        if (function == null) function = link(76, "sqlite3_stmt_readonly",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(pStmt);
        } catch (Throwable e) {
            throw failed("sqlite3_stmt_readonly", e);
        }
    }

    public static int sqlite3_stmt_isexplain(MemorySegment pStmt) {
        var function = FUNCTIONS.get(77);
        if (function == null) function = link(77, "sqlite3_stmt_isexplain",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(pStmt);
        } catch (Throwable e) {
            throw failed("sqlite3_stmt_isexplain", e);
        }
    }

    public static int sqlite3_stmt_explain(MemorySegment pStmt, int eMode) {
        var function = FUNCTIONS.get(78);
        if (function == null) function = link(78, "sqlite3_stmt_explain",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(pStmt, eMode);
        } catch (Throwable e) {
            throw failed("sqlite3_stmt_explain", e);
        }
    }

    public static int sqlite3_stmt_busy(MemorySegment arg0) {
        var function = FUNCTIONS.get(79);
        if (function == null) function = link(79, "sqlite3_stmt_busy",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_stmt_busy", e);
        }
    }

    public static int sqlite3_bind_blob(MemorySegment arg0, int arg1, MemorySegment arg2, int n, MemorySegment arg4) {
        var function = FUNCTIONS.get(80);
        if (function == null) function = link(80, "sqlite3_bind_blob",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2, n, arg4);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_blob", e);
        }
    }

    public static int sqlite3_bind_blob64(MemorySegment arg0, int arg1, MemorySegment arg2, long arg3, MemorySegment arg4) {
        var function = FUNCTIONS.get(81);
        if (function == null) function = link(81, "sqlite3_bind_blob64",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2, arg3, arg4);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_blob64", e);
        }
    }

    public static int sqlite3_bind_double(MemorySegment arg0, int arg1, double arg2) {
        var function = FUNCTIONS.get(82);
        if (function == null) function = link(82, "sqlite3_bind_double",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_DOUBLE));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_double", e);
        }
    }

    public static int sqlite3_bind_int(MemorySegment arg0, int arg1, int arg2) {
        var function = FUNCTIONS.get(83);
        if (function == null) function = link(83, "sqlite3_bind_int",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_int", e);
        }
    }

    public static int sqlite3_bind_int64(MemorySegment arg0, int arg1, long arg2) {
        var function = FUNCTIONS.get(84);
        if (function == null) function = link(84, "sqlite3_bind_int64",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_int64", e);
        }
    }

    public static int sqlite3_bind_null(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(85);
        if (function == null) function = link(85, "sqlite3_bind_null",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_null", e);
        }
    }

    public static int sqlite3_bind_text(MemorySegment arg0, int arg1, MemorySegment arg2, int arg3, MemorySegment arg4) {
        var function = FUNCTIONS.get(86);
        if (function == null) function = link(86, "sqlite3_bind_text",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2, arg3, arg4);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_text", e);
        }
    }

    public static int sqlite3_bind_text16(MemorySegment arg0, int arg1, MemorySegment arg2, int arg3, MemorySegment arg4) {
        var function = FUNCTIONS.get(87);
        if (function == null) function = link(87, "sqlite3_bind_text16",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2, arg3, arg4);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_text16", e);
        }
    }

    public static int sqlite3_bind_text64(MemorySegment arg0, int arg1, MemorySegment arg2, long arg3, MemorySegment arg4, byte encoding) {
        var function = FUNCTIONS.get(88);
        if (function == null) function = link(88, "sqlite3_bind_text64",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_BYTE));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2, arg3, arg4, encoding);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_text64", e);
        }
    }

    public static int sqlite3_bind_value(MemorySegment arg0, int arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(89);
        if (function == null) function = link(89, "sqlite3_bind_value",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_value", e);
        }
    }

    public static int sqlite3_bind_pointer(MemorySegment arg0, int arg1, MemorySegment arg2, MemorySegment arg3, MemorySegment arg4) {
        var function = FUNCTIONS.get(90);
        if (function == null) function = link(90, "sqlite3_bind_pointer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2, arg3, arg4);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_pointer", e);
        }
    }

    public static int sqlite3_bind_zeroblob(MemorySegment arg0, int arg1, int n) {
        var function = FUNCTIONS.get(91);
        if (function == null) function = link(91, "sqlite3_bind_zeroblob",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, arg1, n);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_zeroblob", e);
        }
    }

    public static int sqlite3_bind_zeroblob64(MemorySegment arg0, int arg1, long arg2) {
        var function = FUNCTIONS.get(92);
        if (function == null) function = link(92, "sqlite3_bind_zeroblob64",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_zeroblob64", e);
        }
    }

    public static int sqlite3_bind_parameter_count(MemorySegment arg0) {
        var function = FUNCTIONS.get(93);
        if (function == null) function = link(93, "sqlite3_bind_parameter_count",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_parameter_count", e);
        }
    }

    public static MemorySegment sqlite3_bind_parameter_name(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(94);
        if (function == null) function = link(94, "sqlite3_bind_parameter_name",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_parameter_name", e);
        }
    }

    public static int sqlite3_bind_parameter_index(MemorySegment arg0, MemorySegment zName) {
        var function = FUNCTIONS.get(95);
        if (function == null) function = link(95, "sqlite3_bind_parameter_index",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, zName);
        } catch (Throwable e) {
            throw failed("sqlite3_bind_parameter_index", e);
        }
    }

    public static int sqlite3_clear_bindings(MemorySegment arg0) {
        var function = FUNCTIONS.get(96);
        if (function == null) function = link(96, "sqlite3_clear_bindings",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_clear_bindings", e);
        }
    }

    public static int sqlite3_column_count(MemorySegment pStmt) {
        var function = FUNCTIONS.get(97);
        if (function == null) function = link(97, "sqlite3_column_count",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(pStmt);
        } catch (Throwable e) {
            throw failed("sqlite3_column_count", e);
        }
    }

    public static MemorySegment sqlite3_column_name(MemorySegment arg0, int N) {
        var function = FUNCTIONS.get(98);
        if (function == null) function = link(98, "sqlite3_column_name",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, N);
        } catch (Throwable e) {
            throw failed("sqlite3_column_name", e);
        }
    }

    public static MemorySegment sqlite3_column_name16(MemorySegment arg0, int N) {
        var function = FUNCTIONS.get(99);
        if (function == null) function = link(99, "sqlite3_column_name16",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, N);
        } catch (Throwable e) {
            throw failed("sqlite3_column_name16", e);
        }
    }

    public static MemorySegment sqlite3_column_database_name(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(100);
        if (function == null) function = link(100, "sqlite3_column_database_name",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_column_database_name", e);
        }
    }

    public static MemorySegment sqlite3_column_database_name16(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(101);
        if (function == null) function = link(101, "sqlite3_column_database_name16",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_column_database_name16", e);
        }
    }

    public static MemorySegment sqlite3_column_table_name(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(102);
        if (function == null) function = link(102, "sqlite3_column_table_name",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_column_table_name", e);
        }
    }

    public static MemorySegment sqlite3_column_table_name16(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(103);
        if (function == null) function = link(103, "sqlite3_column_table_name16",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_column_table_name16", e);
        }
    }

    public static MemorySegment sqlite3_column_origin_name(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(104);
        if (function == null) function = link(104, "sqlite3_column_origin_name",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_column_origin_name", e);
        }
    }

    public static MemorySegment sqlite3_column_origin_name16(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(105);
        if (function == null) function = link(105, "sqlite3_column_origin_name16",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_column_origin_name16", e);
        }
    }

    public static MemorySegment sqlite3_column_decltype(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(106);
        if (function == null) function = link(106, "sqlite3_column_decltype",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_column_decltype", e);
        }
    }

    public static MemorySegment sqlite3_column_decltype16(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(107);
        if (function == null) function = link(107, "sqlite3_column_decltype16",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_column_decltype16", e);
        }
    }

    public static int sqlite3_step(MemorySegment arg0) {
        var function = FUNCTIONS.get(108);
        if (function == null) function = link(108, "sqlite3_step",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_step", e);
        }
    }

    public static int sqlite3_data_count(MemorySegment pStmt) {
        var function = FUNCTIONS.get(109);
        if (function == null) function = link(109, "sqlite3_data_count",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(pStmt);
        } catch (Throwable e) {
            throw failed("sqlite3_data_count", e);
        }
    }

    public static MemorySegment sqlite3_column_blob(MemorySegment arg0, int iCol) {
        var function = FUNCTIONS.get(110);
        if (function == null) function = link(110, "sqlite3_column_blob",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, iCol);
        } catch (Throwable e) {
            throw failed("sqlite3_column_blob", e);
        }
    }

    public static double sqlite3_column_double(MemorySegment arg0, int iCol) {
        var function = FUNCTIONS.get(111);
        if (function == null) function = link(111, "sqlite3_column_double",
                FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_INT));
        try {
            return (double) function.invokeExact(arg0, iCol);
        } catch (Throwable e) {
            throw failed("sqlite3_column_double", e);
        }
    }

    public static int sqlite3_column_int(MemorySegment arg0, int iCol) {
        var function = FUNCTIONS.get(112);
        if (function == null) function = link(112, "sqlite3_column_int",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, iCol);
        } catch (Throwable e) {
            throw failed("sqlite3_column_int", e);
        }
    }

    public static long sqlite3_column_int64(MemorySegment arg0, int iCol) {
        var function = FUNCTIONS.get(113);
        if (function == null) function = link(113, "sqlite3_column_int64",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
        try {
            return (long) function.invokeExact(arg0, iCol);
        } catch (Throwable e) {
            throw failed("sqlite3_column_int64", e);
        }
    }

    public static MemorySegment sqlite3_column_text(MemorySegment arg0, int iCol) {
        var function = FUNCTIONS.get(114);
        if (function == null) function = link(114, "sqlite3_column_text",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, iCol);
        } catch (Throwable e) {
            throw failed("sqlite3_column_text", e);
        }
    }

    public static MemorySegment sqlite3_column_text16(MemorySegment arg0, int iCol) {
        var function = FUNCTIONS.get(115);
        if (function == null) function = link(115, "sqlite3_column_text16",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, iCol);
        } catch (Throwable e) {
            throw failed("sqlite3_column_text16", e);
        }
    }

    public static MemorySegment sqlite3_column_value(MemorySegment arg0, int iCol) {
        var function = FUNCTIONS.get(116);
        if (function == null) function = link(116, "sqlite3_column_value",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, iCol);
        } catch (Throwable e) {
            throw failed("sqlite3_column_value", e);
        }
    }

    public static int sqlite3_column_bytes(MemorySegment arg0, int iCol) {
        var function = FUNCTIONS.get(117);
        if (function == null) function = link(117, "sqlite3_column_bytes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, iCol);
        } catch (Throwable e) {
            throw failed("sqlite3_column_bytes", e);
        }
    }

    public static int sqlite3_column_bytes16(MemorySegment arg0, int iCol) {
        var function = FUNCTIONS.get(118);
        if (function == null) function = link(118, "sqlite3_column_bytes16",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, iCol);
        } catch (Throwable e) {
            throw failed("sqlite3_column_bytes16", e);
        }
    }

    public static int sqlite3_column_type(MemorySegment arg0, int iCol) {
        var function = FUNCTIONS.get(119);
        if (function == null) function = link(119, "sqlite3_column_type",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, iCol);
        } catch (Throwable e) {
            throw failed("sqlite3_column_type", e);
        }
    }

    public static int sqlite3_finalize(MemorySegment pStmt) {
        var function = FUNCTIONS.get(120);
        if (function == null) function = link(120, "sqlite3_finalize",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(pStmt);
        } catch (Throwable e) {
            throw failed("sqlite3_finalize", e);
        }
    }

    public static int sqlite3_reset(MemorySegment pStmt) {
        var function = FUNCTIONS.get(121);
        if (function == null) function = link(121, "sqlite3_reset",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(pStmt);
        } catch (Throwable e) {
            throw failed("sqlite3_reset", e);
        }
    }

    public static int sqlite3_create_function(MemorySegment db, MemorySegment zFunctionName, int nArg, int eTextRep, MemorySegment pApp, MemorySegment xFunc, MemorySegment xStep, MemorySegment xFinal) {
        var function = FUNCTIONS.get(122);
        if (function == null) function = link(122, "sqlite3_create_function",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zFunctionName, nArg, eTextRep, pApp, xFunc, xStep, xFinal);
        } catch (Throwable e) {
            throw failed("sqlite3_create_function", e);
        }
    }

    public static int sqlite3_create_function16(MemorySegment db, MemorySegment zFunctionName, int nArg, int eTextRep, MemorySegment pApp, MemorySegment xFunc, MemorySegment xStep, MemorySegment xFinal) {
        var function = FUNCTIONS.get(123);
        if (function == null) function = link(123, "sqlite3_create_function16",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zFunctionName, nArg, eTextRep, pApp, xFunc, xStep, xFinal);
        } catch (Throwable e) {
            throw failed("sqlite3_create_function16", e);
        }
    }

    public static int sqlite3_create_function_v2(MemorySegment db, MemorySegment zFunctionName, int nArg, int eTextRep, MemorySegment pApp, MemorySegment xFunc, MemorySegment xStep, MemorySegment xFinal, MemorySegment xDestroy) {
        var function = FUNCTIONS.get(124);
        if (function == null) function = link(124, "sqlite3_create_function_v2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zFunctionName, nArg, eTextRep, pApp, xFunc, xStep, xFinal, xDestroy);
        } catch (Throwable e) {
            throw failed("sqlite3_create_function_v2", e);
        }
    }

    public static int sqlite3_create_window_function(MemorySegment db, MemorySegment zFunctionName, int nArg, int eTextRep, MemorySegment pApp, MemorySegment xStep, MemorySegment xFinal, MemorySegment xValue, MemorySegment xInverse, MemorySegment xDestroy) {
        var function = FUNCTIONS.get(125);
        if (function == null) function = link(125, "sqlite3_create_window_function",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zFunctionName, nArg, eTextRep, pApp, xStep, xFinal, xValue, xInverse, xDestroy);
        } catch (Throwable e) {
            throw failed("sqlite3_create_window_function", e);
        }
    }

    public static int sqlite3_aggregate_count(MemorySegment arg0) {
        var function = FUNCTIONS.get(126);
        if (function == null) function = link(126, "sqlite3_aggregate_count",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_aggregate_count", e);
        }
    }

    public static int sqlite3_expired(MemorySegment arg0) {
        var function = FUNCTIONS.get(127);
        if (function == null) function = link(127, "sqlite3_expired",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_expired", e);
        }
    }

    public static int sqlite3_transfer_bindings(MemorySegment arg0, MemorySegment arg1) {
        var function = FUNCTIONS.get(128);
        if (function == null) function = link(128, "sqlite3_transfer_bindings",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_transfer_bindings", e);
        }
    }

    public static int sqlite3_global_recover() {
        var function = FUNCTIONS.get(129);
        if (function == null) function = link(129, "sqlite3_global_recover",
                FunctionDescriptor.of(JAVA_INT));
        try {
            return (int) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_global_recover", e);
        }
    }

    public static void sqlite3_thread_cleanup() {
        var function = FUNCTIONS.get(130);
        if (function == null) function = link(130, "sqlite3_thread_cleanup",
                FunctionDescriptor.ofVoid());
        try {
            function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_thread_cleanup", e);
        }
    }

    public static int sqlite3_memory_alarm(MemorySegment arg0, MemorySegment arg1, long arg2) {
        var function = FUNCTIONS.get(131);
        if (function == null) function = link(131, "sqlite3_memory_alarm",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_memory_alarm", e);
        }
    }

    public static MemorySegment sqlite3_value_blob(MemorySegment arg0) {
        var function = FUNCTIONS.get(132);
        if (function == null) function = link(132, "sqlite3_value_blob",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_blob", e);
        }
    }

    public static double sqlite3_value_double(MemorySegment arg0) {
        var function = FUNCTIONS.get(133);
        if (function == null) function = link(133, "sqlite3_value_double",
                FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));
        try {
            return (double) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_double", e);
        }
    }

    public static int sqlite3_value_int(MemorySegment arg0) {
        var function = FUNCTIONS.get(134);
        if (function == null) function = link(134, "sqlite3_value_int",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_int", e);
        }
    }

    public static long sqlite3_value_int64(MemorySegment arg0) {
        var function = FUNCTIONS.get(135);
        if (function == null) function = link(135, "sqlite3_value_int64",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        try {
            return (long) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_int64", e);
        }
    }

    public static MemorySegment sqlite3_value_pointer(MemorySegment arg0, MemorySegment arg1) {
        var function = FUNCTIONS.get(136);
        if (function == null) function = link(136, "sqlite3_value_pointer",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_value_pointer", e);
        }
    }

    public static MemorySegment sqlite3_value_text(MemorySegment arg0) {
        var function = FUNCTIONS.get(137);
        if (function == null) function = link(137, "sqlite3_value_text",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_text", e);
        }
    }

    public static MemorySegment sqlite3_value_text16(MemorySegment arg0) {
        var function = FUNCTIONS.get(138);
        if (function == null) function = link(138, "sqlite3_value_text16",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_text16", e);
        }
    }

    public static MemorySegment sqlite3_value_text16le(MemorySegment arg0) {
        var function = FUNCTIONS.get(139);
        if (function == null) function = link(139, "sqlite3_value_text16le",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_text16le", e);
        }
    }

    public static MemorySegment sqlite3_value_text16be(MemorySegment arg0) {
        var function = FUNCTIONS.get(140);
        if (function == null) function = link(140, "sqlite3_value_text16be",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_text16be", e);
        }
    }

    public static int sqlite3_value_bytes(MemorySegment arg0) {
        var function = FUNCTIONS.get(141);
        if (function == null) function = link(141, "sqlite3_value_bytes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_bytes", e);
        }
    }

    public static int sqlite3_value_bytes16(MemorySegment arg0) {
        var function = FUNCTIONS.get(142);
        if (function == null) function = link(142, "sqlite3_value_bytes16",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_bytes16", e);
        }
    }

    public static int sqlite3_value_type(MemorySegment arg0) {
        var function = FUNCTIONS.get(143);
        if (function == null) function = link(143, "sqlite3_value_type",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_type", e);
        }
    }

    public static int sqlite3_value_numeric_type(MemorySegment arg0) {
        var function = FUNCTIONS.get(144);
        if (function == null) function = link(144, "sqlite3_value_numeric_type",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_numeric_type", e);
        }
    }

    public static int sqlite3_value_nochange(MemorySegment arg0) {
        var function = FUNCTIONS.get(145);
        if (function == null) function = link(145, "sqlite3_value_nochange",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_nochange", e);
        }
    }

    public static int sqlite3_value_frombind(MemorySegment arg0) {
        var function = FUNCTIONS.get(146);
        if (function == null) function = link(146, "sqlite3_value_frombind",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_frombind", e);
        }
    }

    public static int sqlite3_value_encoding(MemorySegment arg0) {
        var function = FUNCTIONS.get(147);
        if (function == null) function = link(147, "sqlite3_value_encoding",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_encoding", e);
        }
    }

    public static int sqlite3_value_subtype(MemorySegment arg0) {
        var function = FUNCTIONS.get(148);
        if (function == null) function = link(148, "sqlite3_value_subtype",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_subtype", e);
        }
    }

    public static MemorySegment sqlite3_value_dup(MemorySegment arg0) {
        var function = FUNCTIONS.get(149);
        if (function == null) function = link(149, "sqlite3_value_dup",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_dup", e);
        }
    }

    public static void sqlite3_value_free(MemorySegment arg0) {
        var function = FUNCTIONS.get(150);
        if (function == null) function = link(150, "sqlite3_value_free",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_value_free", e);
        }
    }

    public static MemorySegment sqlite3_aggregate_context(MemorySegment arg0, int nBytes) {
        var function = FUNCTIONS.get(151);
        if (function == null) function = link(151, "sqlite3_aggregate_context",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, nBytes);
        } catch (Throwable e) {
            throw failed("sqlite3_aggregate_context", e);
        }
    }

    public static MemorySegment sqlite3_user_data(MemorySegment arg0) {
        var function = FUNCTIONS.get(152);
        if (function == null) function = link(152, "sqlite3_user_data",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_user_data", e);
        }
    }

    public static MemorySegment sqlite3_context_db_handle(MemorySegment arg0) {
        var function = FUNCTIONS.get(153);
        if (function == null) function = link(153, "sqlite3_context_db_handle",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_context_db_handle", e);
        }
    }

    public static MemorySegment sqlite3_get_auxdata(MemorySegment arg0, int N) {
        var function = FUNCTIONS.get(154);
        if (function == null) function = link(154, "sqlite3_get_auxdata",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, N);
        } catch (Throwable e) {
            throw failed("sqlite3_get_auxdata", e);
        }
    }

    public static void sqlite3_set_auxdata(MemorySegment arg0, int N, MemorySegment arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(155);
        if (function == null) function = link(155, "sqlite3_set_auxdata",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        try {
            function.invokeExact(arg0, N, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_set_auxdata", e);
        }
    }

    public static MemorySegment sqlite3_get_clientdata(MemorySegment arg0, MemorySegment arg1) {
        var function = FUNCTIONS.get(156);
        if (function == null) function = link(156, "sqlite3_get_clientdata",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_get_clientdata", e);
        }
    }

    public static int sqlite3_set_clientdata(MemorySegment arg0, MemorySegment arg1, MemorySegment arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(157);
        if (function == null) function = link(157, "sqlite3_set_clientdata",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_set_clientdata", e);
        }
    }

    public static void sqlite3_result_blob(MemorySegment arg0, MemorySegment arg1, int arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(158);
        if (function == null) function = link(158, "sqlite3_result_blob",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        try {
            function.invokeExact(arg0, arg1, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_result_blob", e);
        }
    }

    public static void sqlite3_result_blob64(MemorySegment arg0, MemorySegment arg1, long arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(159);
        if (function == null) function = link(159, "sqlite3_result_blob64",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        try {
            function.invokeExact(arg0, arg1, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_result_blob64", e);
        }
    }

    public static void sqlite3_result_double(MemorySegment arg0, double arg1) {
        var function = FUNCTIONS.get(160);
        if (function == null) function = link(160, "sqlite3_result_double",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE));
        try {
            function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_result_double", e);
        }
    }

    public static void sqlite3_result_error(MemorySegment arg0, MemorySegment arg1, int arg2) {
        var function = FUNCTIONS.get(161);
        if (function == null) function = link(161, "sqlite3_result_error",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        try {
            function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_result_error", e);
        }
    }

    public static void sqlite3_result_error16(MemorySegment arg0, MemorySegment arg1, int arg2) {
        var function = FUNCTIONS.get(162);
        if (function == null) function = link(162, "sqlite3_result_error16",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        try {
            function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_result_error16", e);
        }
    }

    public static void sqlite3_result_error_toobig(MemorySegment arg0) {
        var function = FUNCTIONS.get(163);
        if (function == null) function = link(163, "sqlite3_result_error_toobig",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_result_error_toobig", e);
        }
    }

    public static void sqlite3_result_error_nomem(MemorySegment arg0) {
        var function = FUNCTIONS.get(164);
        if (function == null) function = link(164, "sqlite3_result_error_nomem",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_result_error_nomem", e);
        }
    }

    public static void sqlite3_result_error_code(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(165);
        if (function == null) function = link(165, "sqlite3_result_error_code",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        try {
            function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_result_error_code", e);
        }
    }

    public static void sqlite3_result_int(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(166);
        if (function == null) function = link(166, "sqlite3_result_int",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        try {
            function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_result_int", e);
        }
    }

    public static void sqlite3_result_int64(MemorySegment arg0, long arg1) {
        var function = FUNCTIONS.get(167);
        if (function == null) function = link(167, "sqlite3_result_int64",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        try {
            function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_result_int64", e);
        }
    }

    public static void sqlite3_result_null(MemorySegment arg0) {
        var function = FUNCTIONS.get(168);
        if (function == null) function = link(168, "sqlite3_result_null",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_result_null", e);
        }
    }

    public static void sqlite3_result_text(MemorySegment arg0, MemorySegment arg1, int arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(169);
        if (function == null) function = link(169, "sqlite3_result_text",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        try {
            function.invokeExact(arg0, arg1, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_result_text", e);
        }
    }

    public static void sqlite3_result_text64(MemorySegment arg0, MemorySegment arg1, long arg2, MemorySegment arg3, byte encoding) {
        var function = FUNCTIONS.get(170);
        if (function == null) function = link(170, "sqlite3_result_text64",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_BYTE));
        try {
            function.invokeExact(arg0, arg1, arg2, arg3, encoding);
        } catch (Throwable e) {
            throw failed("sqlite3_result_text64", e);
        }
    }

    public static void sqlite3_result_text16(MemorySegment arg0, MemorySegment arg1, int arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(171);
        if (function == null) function = link(171, "sqlite3_result_text16",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        try {
            function.invokeExact(arg0, arg1, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_result_text16", e);
        }
    }

    public static void sqlite3_result_text16le(MemorySegment arg0, MemorySegment arg1, int arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(172);
        if (function == null) function = link(172, "sqlite3_result_text16le",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        try {
            function.invokeExact(arg0, arg1, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_result_text16le", e);
        }
    }

    public static void sqlite3_result_text16be(MemorySegment arg0, MemorySegment arg1, int arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(173);
        if (function == null) function = link(173, "sqlite3_result_text16be",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        try {
            function.invokeExact(arg0, arg1, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_result_text16be", e);
        }
    }

    public static void sqlite3_result_value(MemorySegment arg0, MemorySegment arg1) {
        var function = FUNCTIONS.get(174);
        if (function == null) function = link(174, "sqlite3_result_value",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        try {
            function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_result_value", e);
        }
    }

    public static void sqlite3_result_pointer(MemorySegment arg0, MemorySegment arg1, MemorySegment arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(175);
        if (function == null) function = link(175, "sqlite3_result_pointer",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            function.invokeExact(arg0, arg1, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_result_pointer", e);
        }
    }

    public static void sqlite3_result_zeroblob(MemorySegment arg0, int n) {
        var function = FUNCTIONS.get(176);
        if (function == null) function = link(176, "sqlite3_result_zeroblob",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        try {
            function.invokeExact(arg0, n);
        } catch (Throwable e) {
            throw failed("sqlite3_result_zeroblob", e);
        }
    }

    public static int sqlite3_result_zeroblob64(MemorySegment arg0, long n) {
        var function = FUNCTIONS.get(177);
        if (function == null) function = link(177, "sqlite3_result_zeroblob64",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        try {
            return (int) function.invokeExact(arg0, n);
        } catch (Throwable e) {
            throw failed("sqlite3_result_zeroblob64", e);
        }
    }

    public static void sqlite3_result_subtype(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(178);
        if (function == null) function = link(178, "sqlite3_result_subtype",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        try {
            function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_result_subtype", e);
        }
    }

    public static int sqlite3_create_collation(MemorySegment arg0, MemorySegment zName, int eTextRep, MemorySegment pArg, MemorySegment xCompare) {
        var function = FUNCTIONS.get(179);
        if (function == null) function = link(179, "sqlite3_create_collation",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, zName, eTextRep, pArg, xCompare);
        } catch (Throwable e) {
            throw failed("sqlite3_create_collation", e);
        }
    }

    public static int sqlite3_create_collation_v2(MemorySegment arg0, MemorySegment zName, int eTextRep, MemorySegment pArg, MemorySegment xCompare, MemorySegment xDestroy) {
        var function = FUNCTIONS.get(180);
        if (function == null) function = link(180, "sqlite3_create_collation_v2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, zName, eTextRep, pArg, xCompare, xDestroy);
        } catch (Throwable e) {
            throw failed("sqlite3_create_collation_v2", e);
        }
    }

    public static int sqlite3_create_collation16(MemorySegment arg0, MemorySegment zName, int eTextRep, MemorySegment pArg, MemorySegment xCompare) {
        var function = FUNCTIONS.get(181);
        if (function == null) function = link(181, "sqlite3_create_collation16",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, zName, eTextRep, pArg, xCompare);
        } catch (Throwable e) {
            throw failed("sqlite3_create_collation16", e);
        }
    }

    public static int sqlite3_collation_needed(MemorySegment arg0, MemorySegment arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(182);
        if (function == null) function = link(182, "sqlite3_collation_needed",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_collation_needed", e);
        }
    }

    public static int sqlite3_collation_needed16(MemorySegment arg0, MemorySegment arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(183);
        if (function == null) function = link(183, "sqlite3_collation_needed16",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_collation_needed16", e);
        }
    }

    public static int sqlite3_sleep(int arg0) {
        var function = FUNCTIONS.get(184);
        if (function == null) function = link(184, "sqlite3_sleep",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_sleep", e);
        }
    }

    public static int sqlite3_win32_set_directory(long type, MemorySegment zValue) {
        var function = FUNCTIONS.get(185);
        if (function == null) function = link(185, "sqlite3_win32_set_directory",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
        try {
            return (int) function.invokeExact(type, zValue);
        } catch (Throwable e) {
            throw failed("sqlite3_win32_set_directory", e);
        }
    }

    public static int sqlite3_win32_set_directory8(long type, MemorySegment zValue) {
        var function = FUNCTIONS.get(186);
        if (function == null) function = link(186, "sqlite3_win32_set_directory8",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
        try {
            return (int) function.invokeExact(type, zValue);
        } catch (Throwable e) {
            throw failed("sqlite3_win32_set_directory8", e);
        }
    }

    public static int sqlite3_win32_set_directory16(long type, MemorySegment zValue) {
        var function = FUNCTIONS.get(187);
        if (function == null) function = link(187, "sqlite3_win32_set_directory16",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
        try {
            return (int) function.invokeExact(type, zValue);
        } catch (Throwable e) {
            throw failed("sqlite3_win32_set_directory16", e);
        }
    }

    public static int sqlite3_get_autocommit(MemorySegment arg0) {
        var function = FUNCTIONS.get(188);
        if (function == null) function = link(188, "sqlite3_get_autocommit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_get_autocommit", e);
        }
    }

    public static MemorySegment sqlite3_db_handle(MemorySegment arg0) {
        var function = FUNCTIONS.get(189);
        if (function == null) function = link(189, "sqlite3_db_handle",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_db_handle", e);
        }
    }

    public static MemorySegment sqlite3_db_name(MemorySegment db, int N) {
        var function = FUNCTIONS.get(190);
        if (function == null) function = link(190, "sqlite3_db_name",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(db, N);
        } catch (Throwable e) {
            throw failed("sqlite3_db_name", e);
        }
    }

    public static MemorySegment sqlite3_db_filename(MemorySegment db, MemorySegment zDbName) {
        var function = FUNCTIONS.get(191);
        if (function == null) function = link(191, "sqlite3_db_filename",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(db, zDbName);
        } catch (Throwable e) {
            throw failed("sqlite3_db_filename", e);
        }
    }

    public static int sqlite3_db_readonly(MemorySegment db, MemorySegment zDbName) {
        var function = FUNCTIONS.get(192);
        if (function == null) function = link(192, "sqlite3_db_readonly",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zDbName);
        } catch (Throwable e) {
            throw failed("sqlite3_db_readonly", e);
        }
    }

    public static int sqlite3_txn_state(MemorySegment arg0, MemorySegment zSchema) {
        var function = FUNCTIONS.get(193);
        if (function == null) function = link(193, "sqlite3_txn_state",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, zSchema);
        } catch (Throwable e) {
            throw failed("sqlite3_txn_state", e);
        }
    }

    public static MemorySegment sqlite3_next_stmt(MemorySegment pDb, MemorySegment pStmt) {
        var function = FUNCTIONS.get(194);
        if (function == null) function = link(194, "sqlite3_next_stmt",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(pDb, pStmt);
        } catch (Throwable e) {
            throw failed("sqlite3_next_stmt", e);
        }
    }

    public static MemorySegment sqlite3_commit_hook(MemorySegment arg0, MemorySegment arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(195);
        if (function == null) function = link(195, "sqlite3_commit_hook",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_commit_hook", e);
        }
    }

    public static MemorySegment sqlite3_rollback_hook(MemorySegment arg0, MemorySegment arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(196);
        if (function == null) function = link(196, "sqlite3_rollback_hook",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_rollback_hook", e);
        }
    }

    public static int sqlite3_autovacuum_pages(MemorySegment db, MemorySegment arg1, MemorySegment arg2, MemorySegment arg3) {
        var function = FUNCTIONS.get(197);
        if (function == null) function = link(197, "sqlite3_autovacuum_pages",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, arg1, arg2, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_autovacuum_pages", e);
        }
    }

    public static MemorySegment sqlite3_update_hook(MemorySegment arg0, MemorySegment arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(198);
        if (function == null) function = link(198, "sqlite3_update_hook",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_update_hook", e);
        }
    }

    public static int sqlite3_enable_shared_cache(int arg0) {
        var function = FUNCTIONS.get(199);
        if (function == null) function = link(199, "sqlite3_enable_shared_cache",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_enable_shared_cache", e);
        }
    }

    public static int sqlite3_release_memory(int arg0) {
        var function = FUNCTIONS.get(200);
        if (function == null) function = link(200, "sqlite3_release_memory",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_release_memory", e);
        }
    }

    public static int sqlite3_db_release_memory(MemorySegment arg0) {
        var function = FUNCTIONS.get(201);
        if (function == null) function = link(201, "sqlite3_db_release_memory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_db_release_memory", e);
        }
    }

    public static long sqlite3_soft_heap_limit64(long N) {
        var function = FUNCTIONS.get(202);
        if (function == null) function = link(202, "sqlite3_soft_heap_limit64",
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
        try {
            return (long) function.invokeExact(N);
        } catch (Throwable e) {
            throw failed("sqlite3_soft_heap_limit64", e);
        }
    }

    public static long sqlite3_hard_heap_limit64(long N) {
        var function = FUNCTIONS.get(203);
        if (function == null) function = link(203, "sqlite3_hard_heap_limit64",
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
        try {
            return (long) function.invokeExact(N);
        } catch (Throwable e) {
            throw failed("sqlite3_hard_heap_limit64", e);
        }
    }

    public static void sqlite3_soft_heap_limit(int N) {
        var function = FUNCTIONS.get(204);
        if (function == null) function = link(204, "sqlite3_soft_heap_limit",
                FunctionDescriptor.ofVoid(JAVA_INT));
        try {
            function.invokeExact(N);
        } catch (Throwable e) {
            throw failed("sqlite3_soft_heap_limit", e);
        }
    }

    public static int sqlite3_table_column_metadata(MemorySegment db, MemorySegment zDbName, MemorySegment zTableName, MemorySegment zColumnName, MemorySegment pzDataType, MemorySegment pzCollSeq, MemorySegment pNotNull, MemorySegment pPrimaryKey, MemorySegment pAutoinc) {
        var function = FUNCTIONS.get(205);
        if (function == null) function = link(205, "sqlite3_table_column_metadata",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zDbName, zTableName, zColumnName, pzDataType, pzCollSeq, pNotNull, pPrimaryKey, pAutoinc);
        } catch (Throwable e) {
            throw failed("sqlite3_table_column_metadata", e);
        }
    }

    public static int sqlite3_load_extension(MemorySegment db, MemorySegment zFile, MemorySegment zProc, MemorySegment pzErrMsg) {
        var function = FUNCTIONS.get(206);
        if (function == null) function = link(206, "sqlite3_load_extension",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zFile, zProc, pzErrMsg);
        } catch (Throwable e) {
            throw failed("sqlite3_load_extension", e);
        }
    }

    public static int sqlite3_enable_load_extension(MemorySegment db, int onoff) {
        var function = FUNCTIONS.get(207);
        if (function == null) function = link(207, "sqlite3_enable_load_extension",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(db, onoff);
        } catch (Throwable e) {
            throw failed("sqlite3_enable_load_extension", e);
        }
    }

    public static int sqlite3_auto_extension(MemorySegment xEntryPoint) {
        var function = FUNCTIONS.get(208);
        if (function == null) function = link(208, "sqlite3_auto_extension",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(xEntryPoint);
        } catch (Throwable e) {
            throw failed("sqlite3_auto_extension", e);
        }
    }

    public static int sqlite3_cancel_auto_extension(MemorySegment xEntryPoint) {
        var function = FUNCTIONS.get(209);
        if (function == null) function = link(209, "sqlite3_cancel_auto_extension",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(xEntryPoint);
        } catch (Throwable e) {
            throw failed("sqlite3_cancel_auto_extension", e);
        }
    }

    public static void sqlite3_reset_auto_extension() {
        var function = FUNCTIONS.get(210);
        if (function == null) function = link(210, "sqlite3_reset_auto_extension",
                FunctionDescriptor.ofVoid());
        try {
            function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_reset_auto_extension", e);
        }
    }

    public static int sqlite3_create_module(MemorySegment db, MemorySegment zName, MemorySegment p, MemorySegment pClientData) {
        var function = FUNCTIONS.get(211);
        if (function == null) function = link(211, "sqlite3_create_module",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zName, p, pClientData);
        } catch (Throwable e) {
            throw failed("sqlite3_create_module", e);
        }
    }

    public static int sqlite3_create_module_v2(MemorySegment db, MemorySegment zName, MemorySegment p, MemorySegment pClientData, MemorySegment xDestroy) {
        var function = FUNCTIONS.get(212);
        if (function == null) function = link(212, "sqlite3_create_module_v2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zName, p, pClientData, xDestroy);
        } catch (Throwable e) {
            throw failed("sqlite3_create_module_v2", e);
        }
    }

    public static int sqlite3_drop_modules(MemorySegment db, MemorySegment azKeep) {
        var function = FUNCTIONS.get(213);
        if (function == null) function = link(213, "sqlite3_drop_modules",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, azKeep);
        } catch (Throwable e) {
            throw failed("sqlite3_drop_modules", e);
        }
    }

    public static int sqlite3_declare_vtab(MemorySegment arg0, MemorySegment zSQL) {
        var function = FUNCTIONS.get(214);
        if (function == null) function = link(214, "sqlite3_declare_vtab",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, zSQL);
        } catch (Throwable e) {
            throw failed("sqlite3_declare_vtab", e);
        }
    }

    public static int sqlite3_overload_function(MemorySegment arg0, MemorySegment zFuncName, int nArg) {
        var function = FUNCTIONS.get(215);
        if (function == null) function = link(215, "sqlite3_overload_function",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, zFuncName, nArg);
        } catch (Throwable e) {
            throw failed("sqlite3_overload_function", e);
        }
    }

    public static int sqlite3_blob_open(MemorySegment arg0, MemorySegment zDb, MemorySegment zTable, MemorySegment zColumn, long iRow, int flags, MemorySegment ppBlob) {
        var function = FUNCTIONS.get(216);
        if (function == null) function = link(216, "sqlite3_blob_open",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, zDb, zTable, zColumn, iRow, flags, ppBlob);
        } catch (Throwable e) {
            throw failed("sqlite3_blob_open", e);
        }
    }

    public static int sqlite3_blob_reopen(MemorySegment arg0, long arg1) {
        var function = FUNCTIONS.get(217);
        if (function == null) function = link(217, "sqlite3_blob_reopen",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        try {
            return (int) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_blob_reopen", e);
        }
    }

    public static int sqlite3_blob_close(MemorySegment arg0) {
        var function = FUNCTIONS.get(218);
        if (function == null) function = link(218, "sqlite3_blob_close",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_blob_close", e);
        }
    }

    public static int sqlite3_blob_bytes(MemorySegment arg0) {
        var function = FUNCTIONS.get(219);
        if (function == null) function = link(219, "sqlite3_blob_bytes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_blob_bytes", e);
        }
    }

    public static int sqlite3_blob_read(MemorySegment arg0, MemorySegment Z, int N, int iOffset) {
        var function = FUNCTIONS.get(220);
        if (function == null) function = link(220, "sqlite3_blob_read",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, Z, N, iOffset);
        } catch (Throwable e) {
            throw failed("sqlite3_blob_read", e);
        }
    }

    public static int sqlite3_blob_write(MemorySegment arg0, MemorySegment z, int n, int iOffset) {
        var function = FUNCTIONS.get(221);
        if (function == null) function = link(221, "sqlite3_blob_write",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, z, n, iOffset);
        } catch (Throwable e) {
            throw failed("sqlite3_blob_write", e);
        }
    }

    public static MemorySegment sqlite3_vfs_find(MemorySegment zVfsName) {
        var function = FUNCTIONS.get(222);
        if (function == null) function = link(222, "sqlite3_vfs_find",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(zVfsName);
        } catch (Throwable e) {
            throw failed("sqlite3_vfs_find", e);
        }
    }

    public static int sqlite3_vfs_register(MemorySegment arg0, int makeDflt) {
        var function = FUNCTIONS.get(223);
        if (function == null) function = link(223, "sqlite3_vfs_register",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, makeDflt);
        } catch (Throwable e) {
            throw failed("sqlite3_vfs_register", e);
        }
    }

    public static int sqlite3_vfs_unregister(MemorySegment arg0) {
        var function = FUNCTIONS.get(224);
        if (function == null) function = link(224, "sqlite3_vfs_unregister",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_vfs_unregister", e);
        }
    }

    public static MemorySegment sqlite3_mutex_alloc(int arg0) {
        var function = FUNCTIONS.get(225);
        if (function == null) function = link(225, "sqlite3_mutex_alloc",
                FunctionDescriptor.of(ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_mutex_alloc", e);
        }
    }

    public static void sqlite3_mutex_free(MemorySegment arg0) {
        var function = FUNCTIONS.get(226);
        if (function == null) function = link(226, "sqlite3_mutex_free",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_mutex_free", e);
        }
    }

    public static void sqlite3_mutex_enter(MemorySegment arg0) {
        var function = FUNCTIONS.get(227);
        if (function == null) function = link(227, "sqlite3_mutex_enter",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_mutex_enter", e);
        }
    }

    public static int sqlite3_mutex_try(MemorySegment arg0) {
        var function = FUNCTIONS.get(228);
        if (function == null) function = link(228, "sqlite3_mutex_try",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_mutex_try", e);
        }
    }

    public static void sqlite3_mutex_leave(MemorySegment arg0) {
        var function = FUNCTIONS.get(229);
        if (function == null) function = link(229, "sqlite3_mutex_leave",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_mutex_leave", e);
        }
    }

    public static int sqlite3_mutex_held(MemorySegment arg0) {
        var function = FUNCTIONS.get(230);
        if (function == null) function = link(230, "sqlite3_mutex_held",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_mutex_held", e);
        }
    }

    public static int sqlite3_mutex_notheld(MemorySegment arg0) {
        var function = FUNCTIONS.get(231);
        if (function == null) function = link(231, "sqlite3_mutex_notheld",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_mutex_notheld", e);
        }
    }

    public static MemorySegment sqlite3_db_mutex(MemorySegment arg0) {
        var function = FUNCTIONS.get(232);
        if (function == null) function = link(232, "sqlite3_db_mutex",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_db_mutex", e);
        }
    }

    public static int sqlite3_file_control(MemorySegment arg0, MemorySegment zDbName, int op, MemorySegment arg3) {
        var function = FUNCTIONS.get(233);
        if (function == null) function = link(233, "sqlite3_file_control",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, zDbName, op, arg3);
        } catch (Throwable e) {
            throw failed("sqlite3_file_control", e);
        }
    }

    /// Variadic. Give it the layouts of the extra arguments and it answers
    /// with a handle for that call: sqlite3_test_control(...).
    public static MethodHandle sqlite3_test_control(MemoryLayout... variadic) {
        return LIBRARY.variadic("sqlite3_test_control",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT), variadic);
    }

    public static int sqlite3_keyword_count() {
        var function = FUNCTIONS.get(235);
        if (function == null) function = link(235, "sqlite3_keyword_count",
                FunctionDescriptor.of(JAVA_INT));
        try {
            return (int) function.invokeExact();
        } catch (Throwable e) {
            throw failed("sqlite3_keyword_count", e);
        }
    }

    public static int sqlite3_keyword_name(int arg0, MemorySegment arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(236);
        if (function == null) function = link(236, "sqlite3_keyword_name",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_keyword_name", e);
        }
    }

    public static int sqlite3_keyword_check(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(237);
        if (function == null) function = link(237, "sqlite3_keyword_check",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_keyword_check", e);
        }
    }

    public static MemorySegment sqlite3_str_new(MemorySegment arg0) {
        var function = FUNCTIONS.get(238);
        if (function == null) function = link(238, "sqlite3_str_new",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_str_new", e);
        }
    }

    public static MemorySegment sqlite3_str_finish(MemorySegment arg0) {
        var function = FUNCTIONS.get(239);
        if (function == null) function = link(239, "sqlite3_str_finish",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_str_finish", e);
        }
    }

    /// Variadic. Give it the layouts of the extra arguments and it answers
    /// with a handle for that call: sqlite3_str_appendf(...).
    public static MethodHandle sqlite3_str_appendf(MemoryLayout... variadic) {
        return LIBRARY.variadic("sqlite3_str_appendf",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), variadic);
    }

    public static void sqlite3_str_append(MemorySegment arg0, MemorySegment zIn, int N) {
        var function = FUNCTIONS.get(241);
        if (function == null) function = link(241, "sqlite3_str_append",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        try {
            function.invokeExact(arg0, zIn, N);
        } catch (Throwable e) {
            throw failed("sqlite3_str_append", e);
        }
    }

    public static void sqlite3_str_appendall(MemorySegment arg0, MemorySegment zIn) {
        var function = FUNCTIONS.get(242);
        if (function == null) function = link(242, "sqlite3_str_appendall",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        try {
            function.invokeExact(arg0, zIn);
        } catch (Throwable e) {
            throw failed("sqlite3_str_appendall", e);
        }
    }

    public static void sqlite3_str_appendchar(MemorySegment arg0, int N, byte C) {
        var function = FUNCTIONS.get(243);
        if (function == null) function = link(243, "sqlite3_str_appendchar",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_BYTE));
        try {
            function.invokeExact(arg0, N, C);
        } catch (Throwable e) {
            throw failed("sqlite3_str_appendchar", e);
        }
    }

    public static void sqlite3_str_reset(MemorySegment arg0) {
        var function = FUNCTIONS.get(244);
        if (function == null) function = link(244, "sqlite3_str_reset",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_str_reset", e);
        }
    }

    public static int sqlite3_str_errcode(MemorySegment arg0) {
        var function = FUNCTIONS.get(245);
        if (function == null) function = link(245, "sqlite3_str_errcode",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_str_errcode", e);
        }
    }

    public static int sqlite3_str_length(MemorySegment arg0) {
        var function = FUNCTIONS.get(246);
        if (function == null) function = link(246, "sqlite3_str_length",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_str_length", e);
        }
    }

    public static MemorySegment sqlite3_str_value(MemorySegment arg0) {
        var function = FUNCTIONS.get(247);
        if (function == null) function = link(247, "sqlite3_str_value",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_str_value", e);
        }
    }

    public static int sqlite3_status(int op, MemorySegment pCurrent, MemorySegment pHighwater, int resetFlag) {
        var function = FUNCTIONS.get(248);
        if (function == null) function = link(248, "sqlite3_status",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(op, pCurrent, pHighwater, resetFlag);
        } catch (Throwable e) {
            throw failed("sqlite3_status", e);
        }
    }

    public static int sqlite3_status64(int op, MemorySegment pCurrent, MemorySegment pHighwater, int resetFlag) {
        var function = FUNCTIONS.get(249);
        if (function == null) function = link(249, "sqlite3_status64",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(op, pCurrent, pHighwater, resetFlag);
        } catch (Throwable e) {
            throw failed("sqlite3_status64", e);
        }
    }

    public static int sqlite3_db_status(MemorySegment arg0, int op, MemorySegment pCur, MemorySegment pHiwtr, int resetFlg) {
        var function = FUNCTIONS.get(250);
        if (function == null) function = link(250, "sqlite3_db_status",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, op, pCur, pHiwtr, resetFlg);
        } catch (Throwable e) {
            throw failed("sqlite3_db_status", e);
        }
    }

    public static int sqlite3_stmt_status(MemorySegment arg0, int op, int resetFlg) {
        var function = FUNCTIONS.get(251);
        if (function == null) function = link(251, "sqlite3_stmt_status",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, op, resetFlg);
        } catch (Throwable e) {
            throw failed("sqlite3_stmt_status", e);
        }
    }

    public static MemorySegment sqlite3_backup_init(MemorySegment pDest, MemorySegment zDestName, MemorySegment pSource, MemorySegment zSourceName) {
        var function = FUNCTIONS.get(252);
        if (function == null) function = link(252, "sqlite3_backup_init",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(pDest, zDestName, pSource, zSourceName);
        } catch (Throwable e) {
            throw failed("sqlite3_backup_init", e);
        }
    }

    public static int sqlite3_backup_step(MemorySegment p, int nPage) {
        var function = FUNCTIONS.get(253);
        if (function == null) function = link(253, "sqlite3_backup_step",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(p, nPage);
        } catch (Throwable e) {
            throw failed("sqlite3_backup_step", e);
        }
    }

    public static int sqlite3_backup_finish(MemorySegment p) {
        var function = FUNCTIONS.get(254);
        if (function == null) function = link(254, "sqlite3_backup_finish",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(p);
        } catch (Throwable e) {
            throw failed("sqlite3_backup_finish", e);
        }
    }

    public static int sqlite3_backup_remaining(MemorySegment p) {
        var function = FUNCTIONS.get(255);
        if (function == null) function = link(255, "sqlite3_backup_remaining",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(p);
        } catch (Throwable e) {
            throw failed("sqlite3_backup_remaining", e);
        }
    }

    public static int sqlite3_backup_pagecount(MemorySegment p) {
        var function = FUNCTIONS.get(256);
        if (function == null) function = link(256, "sqlite3_backup_pagecount",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(p);
        } catch (Throwable e) {
            throw failed("sqlite3_backup_pagecount", e);
        }
    }

    public static int sqlite3_unlock_notify(MemorySegment pBlocked, MemorySegment xNotify, MemorySegment pNotifyArg) {
        var function = FUNCTIONS.get(257);
        if (function == null) function = link(257, "sqlite3_unlock_notify",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(pBlocked, xNotify, pNotifyArg);
        } catch (Throwable e) {
            throw failed("sqlite3_unlock_notify", e);
        }
    }

    public static int sqlite3_stricmp(MemorySegment arg0, MemorySegment arg1) {
        var function = FUNCTIONS.get(258);
        if (function == null) function = link(258, "sqlite3_stricmp",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_stricmp", e);
        }
    }

    public static int sqlite3_strnicmp(MemorySegment arg0, MemorySegment arg1, int arg2) {
        var function = FUNCTIONS.get(259);
        if (function == null) function = link(259, "sqlite3_strnicmp",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_strnicmp", e);
        }
    }

    public static int sqlite3_strglob(MemorySegment zGlob, MemorySegment zStr) {
        var function = FUNCTIONS.get(260);
        if (function == null) function = link(260, "sqlite3_strglob",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(zGlob, zStr);
        } catch (Throwable e) {
            throw failed("sqlite3_strglob", e);
        }
    }

    public static int sqlite3_strlike(MemorySegment zGlob, MemorySegment zStr, int cEsc) {
        var function = FUNCTIONS.get(261);
        if (function == null) function = link(261, "sqlite3_strlike",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(zGlob, zStr, cEsc);
        } catch (Throwable e) {
            throw failed("sqlite3_strlike", e);
        }
    }

    /// Variadic. Give it the layouts of the extra arguments and it answers
    /// with a handle for that call: sqlite3_log(...).
    public static MethodHandle sqlite3_log(MemoryLayout... variadic) {
        return LIBRARY.variadic("sqlite3_log",
                FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS), variadic);
    }

    public static MemorySegment sqlite3_wal_hook(MemorySegment arg0, MemorySegment arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(263);
        if (function == null) function = link(263, "sqlite3_wal_hook",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_wal_hook", e);
        }
    }

    public static int sqlite3_wal_autocheckpoint(MemorySegment db, int N) {
        var function = FUNCTIONS.get(264);
        if (function == null) function = link(264, "sqlite3_wal_autocheckpoint",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try {
            return (int) function.invokeExact(db, N);
        } catch (Throwable e) {
            throw failed("sqlite3_wal_autocheckpoint", e);
        }
    }

    public static int sqlite3_wal_checkpoint(MemorySegment db, MemorySegment zDb) {
        var function = FUNCTIONS.get(265);
        if (function == null) function = link(265, "sqlite3_wal_checkpoint",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zDb);
        } catch (Throwable e) {
            throw failed("sqlite3_wal_checkpoint", e);
        }
    }

    public static int sqlite3_wal_checkpoint_v2(MemorySegment db, MemorySegment zDb, int eMode, MemorySegment pnLog, MemorySegment pnCkpt) {
        var function = FUNCTIONS.get(266);
        if (function == null) function = link(266, "sqlite3_wal_checkpoint_v2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zDb, eMode, pnLog, pnCkpt);
        } catch (Throwable e) {
            throw failed("sqlite3_wal_checkpoint_v2", e);
        }
    }

    /// Variadic. Give it the layouts of the extra arguments and it answers
    /// with a handle for that call: sqlite3_vtab_config(...).
    public static MethodHandle sqlite3_vtab_config(MemoryLayout... variadic) {
        return LIBRARY.variadic("sqlite3_vtab_config",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), variadic);
    }

    public static int sqlite3_vtab_on_conflict(MemorySegment arg0) {
        var function = FUNCTIONS.get(268);
        if (function == null) function = link(268, "sqlite3_vtab_on_conflict",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_vtab_on_conflict", e);
        }
    }

    public static int sqlite3_vtab_nochange(MemorySegment arg0) {
        var function = FUNCTIONS.get(269);
        if (function == null) function = link(269, "sqlite3_vtab_nochange",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_vtab_nochange", e);
        }
    }

    public static MemorySegment sqlite3_vtab_collation(MemorySegment arg0, int arg1) {
        var function = FUNCTIONS.get(270);
        if (function == null) function = link(270, "sqlite3_vtab_collation",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(arg0, arg1);
        } catch (Throwable e) {
            throw failed("sqlite3_vtab_collation", e);
        }
    }

    public static int sqlite3_vtab_distinct(MemorySegment arg0) {
        var function = FUNCTIONS.get(271);
        if (function == null) function = link(271, "sqlite3_vtab_distinct",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_vtab_distinct", e);
        }
    }

    public static int sqlite3_vtab_in(MemorySegment arg0, int iCons, int bHandle) {
        var function = FUNCTIONS.get(272);
        if (function == null) function = link(272, "sqlite3_vtab_in",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        try {
            return (int) function.invokeExact(arg0, iCons, bHandle);
        } catch (Throwable e) {
            throw failed("sqlite3_vtab_in", e);
        }
    }

    public static int sqlite3_vtab_in_first(MemorySegment pVal, MemorySegment ppOut) {
        var function = FUNCTIONS.get(273);
        if (function == null) function = link(273, "sqlite3_vtab_in_first",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(pVal, ppOut);
        } catch (Throwable e) {
            throw failed("sqlite3_vtab_in_first", e);
        }
    }

    public static int sqlite3_vtab_in_next(MemorySegment pVal, MemorySegment ppOut) {
        var function = FUNCTIONS.get(274);
        if (function == null) function = link(274, "sqlite3_vtab_in_next",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(pVal, ppOut);
        } catch (Throwable e) {
            throw failed("sqlite3_vtab_in_next", e);
        }
    }

    public static int sqlite3_vtab_rhs_value(MemorySegment arg0, int arg1, MemorySegment ppVal) {
        var function = FUNCTIONS.get(275);
        if (function == null) function = link(275, "sqlite3_vtab_rhs_value",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, ppVal);
        } catch (Throwable e) {
            throw failed("sqlite3_vtab_rhs_value", e);
        }
    }

    public static int sqlite3_stmt_scanstatus(MemorySegment pStmt, int idx, int iScanStatusOp, MemorySegment pOut) {
        var function = FUNCTIONS.get(276);
        if (function == null) function = link(276, "sqlite3_stmt_scanstatus",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(pStmt, idx, iScanStatusOp, pOut);
        } catch (Throwable e) {
            throw failed("sqlite3_stmt_scanstatus", e);
        }
    }

    public static int sqlite3_stmt_scanstatus_v2(MemorySegment pStmt, int idx, int iScanStatusOp, int flags, MemorySegment pOut) {
        var function = FUNCTIONS.get(277);
        if (function == null) function = link(277, "sqlite3_stmt_scanstatus_v2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(pStmt, idx, iScanStatusOp, flags, pOut);
        } catch (Throwable e) {
            throw failed("sqlite3_stmt_scanstatus_v2", e);
        }
    }

    public static void sqlite3_stmt_scanstatus_reset(MemorySegment arg0) {
        var function = FUNCTIONS.get(278);
        if (function == null) function = link(278, "sqlite3_stmt_scanstatus_reset",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_stmt_scanstatus_reset", e);
        }
    }

    public static int sqlite3_db_cacheflush(MemorySegment arg0) {
        var function = FUNCTIONS.get(279);
        if (function == null) function = link(279, "sqlite3_db_cacheflush",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_db_cacheflush", e);
        }
    }

    public static MemorySegment sqlite3_preupdate_hook(MemorySegment db, MemorySegment xPreUpdate, MemorySegment arg2) {
        var function = FUNCTIONS.get(280);
        if (function == null) function = link(280, "sqlite3_preupdate_hook",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (MemorySegment) function.invokeExact(db, xPreUpdate, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_preupdate_hook", e);
        }
    }

    public static int sqlite3_preupdate_old(MemorySegment arg0, int arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(281);
        if (function == null) function = link(281, "sqlite3_preupdate_old",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_preupdate_old", e);
        }
    }

    public static int sqlite3_preupdate_count(MemorySegment arg0) {
        var function = FUNCTIONS.get(282);
        if (function == null) function = link(282, "sqlite3_preupdate_count",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_preupdate_count", e);
        }
    }

    public static int sqlite3_preupdate_depth(MemorySegment arg0) {
        var function = FUNCTIONS.get(283);
        if (function == null) function = link(283, "sqlite3_preupdate_depth",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_preupdate_depth", e);
        }
    }

    public static int sqlite3_preupdate_new(MemorySegment arg0, int arg1, MemorySegment arg2) {
        var function = FUNCTIONS.get(284);
        if (function == null) function = link(284, "sqlite3_preupdate_new",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            throw failed("sqlite3_preupdate_new", e);
        }
    }

    public static int sqlite3_preupdate_blobwrite(MemorySegment arg0) {
        var function = FUNCTIONS.get(285);
        if (function == null) function = link(285, "sqlite3_preupdate_blobwrite",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_preupdate_blobwrite", e);
        }
    }

    public static int sqlite3_system_errno(MemorySegment arg0) {
        var function = FUNCTIONS.get(286);
        if (function == null) function = link(286, "sqlite3_system_errno",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            return (int) function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_system_errno", e);
        }
    }

    public static int sqlite3_snapshot_get(MemorySegment db, MemorySegment zSchema, MemorySegment ppSnapshot) {
        var function = FUNCTIONS.get(287);
        if (function == null) function = link(287, "sqlite3_snapshot_get",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zSchema, ppSnapshot);
        } catch (Throwable e) {
            throw failed("sqlite3_snapshot_get", e);
        }
    }

    public static int sqlite3_snapshot_open(MemorySegment db, MemorySegment zSchema, MemorySegment pSnapshot) {
        var function = FUNCTIONS.get(288);
        if (function == null) function = link(288, "sqlite3_snapshot_open",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zSchema, pSnapshot);
        } catch (Throwable e) {
            throw failed("sqlite3_snapshot_open", e);
        }
    }

    public static void sqlite3_snapshot_free(MemorySegment arg0) {
        var function = FUNCTIONS.get(289);
        if (function == null) function = link(289, "sqlite3_snapshot_free",
                FunctionDescriptor.ofVoid(ADDRESS));
        try {
            function.invokeExact(arg0);
        } catch (Throwable e) {
            throw failed("sqlite3_snapshot_free", e);
        }
    }

    public static int sqlite3_snapshot_cmp(MemorySegment p1, MemorySegment p2) {
        var function = FUNCTIONS.get(290);
        if (function == null) function = link(290, "sqlite3_snapshot_cmp",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(p1, p2);
        } catch (Throwable e) {
            throw failed("sqlite3_snapshot_cmp", e);
        }
    }

    public static int sqlite3_snapshot_recover(MemorySegment db, MemorySegment zDb) {
        var function = FUNCTIONS.get(291);
        if (function == null) function = link(291, "sqlite3_snapshot_recover",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zDb);
        } catch (Throwable e) {
            throw failed("sqlite3_snapshot_recover", e);
        }
    }

    public static MemorySegment sqlite3_serialize(MemorySegment db, MemorySegment zSchema, MemorySegment piSize, int mFlags) {
        var function = FUNCTIONS.get(292);
        if (function == null) function = link(292, "sqlite3_serialize",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        try {
            return (MemorySegment) function.invokeExact(db, zSchema, piSize, mFlags);
        } catch (Throwable e) {
            throw failed("sqlite3_serialize", e);
        }
    }

    public static int sqlite3_deserialize(MemorySegment db, MemorySegment zSchema, MemorySegment pData, long szDb, long szBuf, int mFlags) {
        var function = FUNCTIONS.get(293);
        if (function == null) function = link(293, "sqlite3_deserialize",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT));
        try {
            return (int) function.invokeExact(db, zSchema, pData, szDb, szBuf, mFlags);
        } catch (Throwable e) {
            throw failed("sqlite3_deserialize", e);
        }
    }

    public static int sqlite3_rtree_geometry_callback(MemorySegment db, MemorySegment zGeom, MemorySegment xGeom, MemorySegment pContext) {
        var function = FUNCTIONS.get(294);
        if (function == null) function = link(294, "sqlite3_rtree_geometry_callback",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zGeom, xGeom, pContext);
        } catch (Throwable e) {
            throw failed("sqlite3_rtree_geometry_callback", e);
        }
    }

    public static int sqlite3_rtree_query_callback(MemorySegment db, MemorySegment zQueryFunc, MemorySegment xQueryFunc, MemorySegment pContext, MemorySegment xDestructor) {
        var function = FUNCTIONS.get(295);
        if (function == null) function = link(295, "sqlite3_rtree_query_callback",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        try {
            return (int) function.invokeExact(db, zQueryFunc, xQueryFunc, pContext, xDestructor);
        } catch (Throwable e) {
            throw failed("sqlite3_rtree_query_callback", e);
        }
    }

}
