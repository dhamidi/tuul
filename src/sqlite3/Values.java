package sqlite3;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.MemorySegment;

/// Reading C values into Java ones. Nothing here holds on to a pointer: a value
/// is copied out while it is known to be valid, and the pointer is forgotten.
final class Values {

    /// SQLITE_TRANSIENT — the destructor argument that tells SQLite to copy the
    /// bytes, so a bound value does not have to outlive the call that bound it.
    /// It is a pointer with the value -1, which is why it is not one of the
    /// generated constants.
    static final MemorySegment TRANSIENT = MemorySegment.ofAddress(-1L);

    private Values() {}

    /// A C string of unknown length, read up to its terminator.
    static String string(MemorySegment pointer) {
        return pointer.equals(MemorySegment.NULL) ? null : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /// A C string whose length SQLite already told us.
    static String string(MemorySegment pointer, int bytes) {
        return pointer.equals(MemorySegment.NULL) ? null : pointer.reinterpret(bytes + 1L).getString(0);
    }

    static byte[] bytes(MemorySegment pointer, int length) {
        if (pointer.equals(MemorySegment.NULL) || length <= 0) return new byte[0];
        return pointer.reinterpret(length).toArray(JAVA_BYTE);
    }
}
