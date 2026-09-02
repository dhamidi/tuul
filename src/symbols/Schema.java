package symbols;

import java.io.IOException;
import java.nio.file.Path;
import sqlite3.Database;
import sqlite3.Migrations;

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
    /// symbols in their own right. Version 5 carries a symbol's modifiers into
    /// search. Version 6 adds package documents and their search rows. Version
    /// 7 stores the root summary that lets a browser start without discovery.
    /// Version 8 files a package's `README.md` as a document and records
    /// why the last refresh of an origin failed.
    static final int VERSION = 8;

    /// The whole format. It stores types, members, parameters, tags, package
    /// documents, and the origin of each stored item.
    private static final String TABLES = """
            -- `stamp` is the fingerprint of the rows stored here. When a
            -- refresh for a newer fingerprint failed, `attempted` holds that
            -- fingerprint and `problem` says why. Both are cleared by the
            -- next publication. They let the index skip a fingerprint it
            -- already failed on, and tell a reader that the rows are the
            -- last good ones.
            create table origin (
                id        integer primary key,
                kind      text    not null check (kind in ('project', 'vendor', 'platform')),
                location  text    not null,
                stamp     text    not null,
                complete  integer not null default 0,
                attempted text    not null default '',
                problem   text    not null default '',
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

            create table document (
                id      integer primary key,
                origin  integer not null references origin (id) on delete cascade,
                package text    not null,
                kind    text    not null check (kind in ('readme', 'tutorial', 'howto', 'reference', 'guide')),
                slug    text    not null,
                title   text    not null,
                body    text    not null,
                source  text    not null,
                unique (origin, package, kind, slug)
            );

            create index document_of_package on document (origin, package, source);

            -- A browser must be able to draw its first page without walking
            -- sources, jars, or the runtime image. The coordinator writes the
            -- summary and the catalog only reads it.
            create table root (
                position integer not null,
                name     text    not null,
                label    text    not null,
                primary key (position),
                unique (name)
            ) without rowid;

            create table root_item (
                root     integer not null references root (position) on delete cascade,
                position integer not null,
                name     text    not null,
                primary key (root, position)
            ) without rowid;
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
                document  unindexed,
                tokenize = 'porter unicode61'
            );

            create trigger type_indexed after insert on type begin
                insert into search (symbol, doc, kind, modifiers, owner, member, document)
                values (new.name, new.doc, new.kind, new.modifiers, new.id, null, null);
            end;

            create trigger member_indexed after insert on member
            when new.modifiers like '%public%' or new.modifiers like '%protected%' begin
                insert into search (symbol, doc, kind, modifiers, owner, member, document)
                select type.name || '#' || new.name, new.doc, new.kind, new.modifiers, new.type, new.id, null
                from type where type.id = new.type;
            end;

            create trigger document_indexed after insert on document begin
                insert into search (symbol, doc, kind, modifiers, owner, member, document)
                values (
                    new.package || '/' || new.kind ||
                        case when new.slug = '' then '' else '/' || new.slug end,
                    new.title || ' ' || new.body,
                    new.kind,
                    '',
                    null,
                    null,
                    new.id
                );
            end;

            create trigger tag_indexed after insert on tag begin
                update search set doc = doc || ' ' || new.name || ' ' || new.text
                where (new.member is not null and member = new.member)
                   or (new.type is not null and owner = new.type and member is null);
            end;

            create trigger type_forgotten after delete on type begin
                delete from search where owner = old.id and document is null;
            end;

            create trigger document_forgotten after delete on document begin
                delete from search where document = old.id;
            end;
            """;

    private Schema() {}

    /// Opens the index, building it if there is nothing usable there.
    ///
    /// An index is *derived*, so a file at any other version is thrown away
    /// rather than migrated: the sources it describes are the truth, and
    /// rebuilding costs time while reading a shape you guessed at costs
    /// correctness. [Migrations#derived(int, int)] is that rule, and this says
    /// which rule it wants rather than working it out again.
    ///
    /// Foreign keys are on, because the schema leans on cascades to forget a
    /// type completely. `synchronous` is relaxed because the worst a lost write
    /// costs is the work of doing it again. The busy timeout is the one that
    /// matters in practice: two tuuls started at once both want to write the
    /// index, and the second should wait for the first rather than give up on a
    /// question it can still answer.
    static Database open(Path file) throws IOException {
        return Migrations.derived(APPLICATION, VERSION)
                .connecting("pragma foreign_keys = on; pragma synchronous = normal; pragma busy_timeout = 5000;")
                .step(TABLES)
                .step(SEARCH)
                .open(file);
    }

    /// Opens only a complete schema that already exists. Unlike [#open(Path)],
    /// this method never creates, migrates, or replaces a file.
    static Database read(Path file) throws IOException {
        if (!java.nio.file.Files.isRegularFile(file)) throw new IOException("no index at " + file);
        var database = Database.readOnly(file);
        try {
            if (number(database, "application_id") != APPLICATION || number(database, "user_version") != VERSION) {
                throw new IOException("the index is not a current tuul index: " + file);
            }
            database.script("pragma foreign_keys = on; pragma busy_timeout = 5000;");
            return database;
        } catch (RuntimeException | IOException unreadable) {
            database.close();
            throw unreadable;
        }
    }

    private static long number(Database database, String pragma) {
        try (var rows = database.query("pragma " + pragma)) {
            return rows.next() ? rows.integer(0) : 0;
        }
    }
}
