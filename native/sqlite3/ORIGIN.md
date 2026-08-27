# sqlite3

The SQLite amalgamation, vendored: `sqlite3.c` and `sqlite3.h` as shipped,
unmodified.

    version: 3.50.4
    source:  https://sqlite.org/2025/sqlite-amalgamation-3500400.zip

It is committed rather than fetched, for the same reason `vendor/` is: a fresh
clone builds without touching the network. `tuul build` compiles it into
`build/native/libsqlite3.dylib` with the arguments in `cflags`, and
`src/sqlite3/` binds it through `java.lang.foreign`.

`src/sqlite3/Api.java` is generated from `sqlite3.h` by `tuul bind sqlite3`,
which preprocesses the header with the same `cflags` the library was compiled
with — so the binding and the library can never disagree about what is in the
API.

To update: drop in the two files from a newer amalgamation, run

    tuul build --native && tuul bind sqlite3

and commit what changed. Nothing else records the version.
