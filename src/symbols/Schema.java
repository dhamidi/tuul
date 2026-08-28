package symbols;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import sqlite3.Database;
import sqlite3.SqliteException;

/// What a tuul index file *is*: an application file format, in the sense SQLite
/// means it — a schema a person can read and query, not a cache of blobs.
///
/// The file announces itself. `application_id` is `0x7475756c`, which is `tuul`
/// in a hex dump, and `user_version` is the version of the schema below. The
/// rule when either is wrong is the same as when the file is not a database at
/// all: throw it away and build it again. An index is derived from the sources
/// it describes, so replacing one costs nothing but time, while reading one
/// whose shape you have guessed at costs correctness.
final class Schema {

    /// `tuul` as four bytes, so `file` and a hex dump both give it away.
    static final int APPLICATION = 0x7475756c;

    /// Bumped when the shape below changes in a way that would make an older
    /// file answer differently — version 2 stopped indexing private members,
    /// version 4 added where a symbol is written and made packages and modules
    /// symbols in their own right, and version 5 carries a symbol's modifiers
    /// into search, so a result can say `static` without the page being opened.
    static final int VERSION = 5;

    /// The whole format. Types, the members they declare, the parameters those
    /// take, the tags on either, and the origin each type was learned from.
    private static final String TABLES = """
            create table origin (
                id       integer primary key,
                kind     text    not null check (kind in ('project', 'vendor', 'platform')),
                location text    not null,
                stamp    text    not null,
                complete integer not null default 0,
                unique (kind, location)
            );

            -- `kind` holds package and module as well as the five kinds of
            -- type: a package is a symbol with a name, a comment and a list of
            -- what it holds, which is the same shape with the members empty.
            -- `source` and `line` say where the declaration is written; both
            -- are empty for a symbol whose source was never found, which is
            -- every type in a jar that shipped without one.
            create table type (
                id         integer primary key,
                origin     integer not null references origin (id) on delete cascade,
                name       text    not null,
                kind       text    not null,
                modifiers  text    not null,
                superclass text    not null,
                doc        text    not null,
                source     text    not null default '',
                line       integer not null default 0,
                unique (origin, name)
            );

            create index type_by_name on type (name);

            create table type_parameter (
                type     integer not null references type (id) on delete cascade,
                position integer not null,
                name     text    not null,
                primary key (type, position)
            ) without rowid;

            -- What a type says about other types: the interfaces it
            -- implements, the subtypes it permits, the types it declares. One
            -- table because they are one kind of fact, and a reader asking
            -- "what else does this name" wants all three.
            create table related (
                type     integer not null references type (id) on delete cascade,
                relation text    not null check (relation in ('implements', 'permits', 'nested')),
                position integer not null,
                name     text    not null,
                primary key (type, relation, position)
            ) without rowid;

            create table member (
                id        integer primary key,
                type      integer not null references type (id) on delete cascade,
                position  integer not null,
                kind      text    not null check (kind in ('method', 'field')),
                name      text    not null,
                returns   text    not null,
                modifiers text    not null,
                doc       text    not null,
                line      integer not null default 0
            );

            create index member_of_type on member (type, position);

            create table parameter (
                member   integer not null references member (id) on delete cascade,
                position integer not null,
                type     text    not null,
                name     text    not null,
                primary key (member, position)
            ) without rowid;

            create table tag (
                id       integer primary key,
                type     integer references type (id) on delete cascade,
                member   integer references member (id) on delete cascade,
                position integer not null,
                tag      text    not null,
                name     text    not null,
                text     text    not null,
                check ((type is null) <> (member is null))
            );

            create index tag_of_type on tag (type, position);
            create index tag_of_member on tag (member, position);
            """;

    /// Full text search over every symbol and every doc comment. Porter
    /// stemming is the point of using FTS5 rather than `like`: a search for
    /// *returning* finds *returns*, which is how people actually remember what
    /// a method said.
    ///
    /// The triggers are the reason this cannot drift. Nothing in Java has to
    /// remember to keep the search table in step — the schema does it, even
    /// when the rows go away underneath a cascade.
    ///
    /// A member's row is owned by its *type*, not by itself, so forgetting a
    /// type forgets everything written about it in one statement, and the two
    /// tables' ids can never be mistaken for one another.
    ///
    /// Only members somebody could read are searchable. Everything a type
    /// declares is *stored*, because `tuul docs --all` asks for it, but a
    /// private field offered by search is a result that leads nowhere: the page
    /// does not show it and cannot, so search and `docs` would disagree about
    /// what exists. One consequence is worth stating: `modifiers` on a search
    /// row can say `static`, `final`, `abstract` or `sealed`, and can never say
    /// `private`, because the row would not be here.
    ///
    /// `modifiers` is carried but not indexed, for the reason `kind` is: a
    /// result has to be able to say what it is without a second query, and
    /// nobody searching for a symbol means to find every static method there is.
    private static final String SEARCH = """
            create virtual table search using fts5 (
                symbol,
                doc,
                kind      unindexed,
                modifiers unindexed,
                owner     unindexed,
                member    unindexed,
                tokenize = 'porter unicode61'
            );

            create trigger type_indexed after insert on type begin
                insert into search (symbol, doc, kind, modifiers, owner, member)
                values (new.name, new.doc, new.kind, new.modifiers, new.id, null);
            end;

            create trigger member_indexed after insert on member
            when new.modifiers like '%public%' or new.modifiers like '%protected%' begin
                insert into search (symbol, doc, kind, modifiers, owner, member)
                select type.name || '#' || new.name, new.doc, new.kind, new.modifiers, new.type, new.id
                from type where type.id = new.type;
            end;

            create trigger tag_indexed after insert on tag begin
                update search set doc = doc || ' ' || new.name || ' ' || new.text
                where (new.member is not null and member = new.member)
                   or (new.type is not null and owner = new.type and member is null);
            end;

            create trigger type_forgotten after delete on type begin
                delete from search where owner = old.id;
            end;
            """;

    private Schema() {}

    /// Opens the index, building it if there is nothing usable there.
    ///
    /// Foreign keys are on, because the schema leans on cascades to forget a
    /// type completely. The journal is write-ahead so that one tuul reading the
    /// index does not block another writing it, and `synchronous` is relaxed
    /// because the worst a lost write costs is the work of doing it again.
    static Database open(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        var kept = recognised(file);
        if (kept.isPresent()) return kept.get();

        discard(file);
        var fresh = connect(file);
        try {
            create(fresh);
        } catch (RuntimeException broken) {
            fresh.close();
            discard(file);
            throw broken;
        }
        return fresh;
    }

    /// Ours, and this version of ours. Anything else — somebody else's file, an
    /// older idea of what an index looks like, or a note that happens to have
    /// the right name — is not an index, and saying so is the whole job of this
    /// method. Even the first statement has to read the file to find out.
    private static Optional<Database> recognised(Path file) {
        Database database = null;
        try {
            database = connect(file);
            if (number(database, "pragma application_id") == APPLICATION
                    && number(database, "pragma user_version") == VERSION) {
                return Optional.of(database);
            }
        } catch (SqliteException notAnIndex) {
            return Optional.empty();
        }
        database.close();
        return Optional.empty();
    }

    /// The settings that belong to the connection rather than the file. The
    /// busy timeout is the one that matters in practice: two tuuls started at
    /// once both want to write the index, and the second should wait for the
    /// first rather than give up on a question it can still answer.
    private static Database connect(Path file) {
        var database = Database.open(file);
        try {
            database.script(
                    "pragma foreign_keys = on; pragma synchronous = normal; pragma busy_timeout = 5000;");
            return database;
        } catch (RuntimeException notADatabase) {
            database.close();
            throw notADatabase;
        }
    }

    /// The journal mode is written into the file, so it is set once, here.
    private static void create(Database database) {
        database.script("pragma journal_mode = wal");
        database.transaction(() -> database.script(TABLES + SEARCH));
        database.script("pragma application_id = " + APPLICATION + "; pragma user_version = " + VERSION + ";");
    }

    /// The write-ahead log and its shared memory belong to the file; leaving
    /// them behind would attach the old index to the new one.
    private static void discard(Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + "-wal"));
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + "-shm"));
    }

    private static long number(Database database, String pragma) {
        try (var rows = database.query(pragma)) {
            return rows.next() ? rows.integer(0) : 0;
        }
    }
}
