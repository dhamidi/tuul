# sqlite3

The SQLite amalgamation, vendored: `sqlite3.c` and `sqlite3.h` as shipped,
unmodified.

    version: 3.50.4
    source:  https://sqlite.org/2025/sqlite-amalgamation-3500400.zip

It is committed rather than fetched, for the same reason `vendor/` is: a fresh
clone builds without touching the network. `tuul build` compiles it into
`build/native/libsqlite3.dylib` with the arguments in `cflags`, and
`src/sqlite/` binds it through `java.lang.foreign`.

To update, drop in the two files from a newer amalgamation and rebuild —
nothing else records the version.
