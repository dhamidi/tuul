package actors;

import application.Message;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import json.Json;
import sqlite3.Database;
import sqlite3.Statement;

/// One actor's log, kept in one SQLite file.
///
/// ## The schema
///
/// ```
/// create table commands (seq integer primary key, at integer, type text, body text);
/// create table meta     (name text primary key, value text);
/// ```
///
/// The `type` column repeats what is already inside `body`, and it earns its
/// place. `select type, count(*) from commands group by 1` is the first thing
/// anybody types when an actor misbehaves, and it should not need a JSON parser
/// to answer. The `meta` table holds the address, because a file name is
/// percent-encoded and an inspector needs the id a person wrote.
///
/// ## Why one file per actor
///
/// A single writer thread owns each file, so SQLite never has to arbitrate
/// between writers and a busy-handler is never needed. Ten thousand actors mean
/// ten thousand small files, which a file system handles better than ten
/// thousand writers on one file. The cost is that a query across actors has to
/// open many databases, and that is why [Logs#catalogue()] answers from the
/// directory rather than from SQL.
///
/// ## Durability settings
///
/// `journal_mode=wal` lets a reader run while the owner appends, which is what
/// makes an inspector possible without stopping the actor. `synchronous=normal`
/// trades a fsync per commit for a fsync per checkpoint. With WAL that risks
/// losing the last few commits to a power cut, not corruption. An actor that
/// must not lose a command sets `synchronous=full` and pays for it.
public final class Journal implements Log {

    private final Database database;
    private final Statement insert;
    private long length;

    Journal(java.nio.file.Path file, Address address) {
        database = Database.open(file);
        database.script("""
                pragma journal_mode = wal;
                pragma synchronous = normal;
                create table if not exists commands (
                    seq  integer primary key,
                    at   integer not null,
                    type text    not null,
                    body text    not null);
                create table if not exists meta (name text primary key, value text not null);
                """);
        database.execute("insert or replace into meta (name, value) values ('address', ?)", address.toString());
        insert = Statement.of(database, "insert into commands (seq, at, type, body) values (?, ?, ?, ?)");
        length = count();
    }

    @Override
    public long append(Message command, long at) {
        var seq = length + 1;
        insert.run(seq, at, command.type(), command.body().text());
        length = seq;
        return seq;
    }

    /// Reads entries as SQLite produces them. Nothing collects the history into
    /// a list, so replaying a log with a million commands costs one command of
    /// memory.
    @Override
    public Stream<Log.Entry> replay(long from, long limit) {
        var rows = database.query(
                "select seq, at, body from commands where seq > ? order by seq limit ?", from, limit);
        var entries = new Spliterators.AbstractSpliterator<Log.Entry>(
                Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {

            @Override
            public boolean tryAdvance(Consumer<? super Log.Entry> action) {
                if (!rows.next()) return false;
                action.accept(new Log.Entry(rows.integer(0), rows.integer(1), read(rows.text(2))));
                return true;
            }
        };
        return StreamSupport.stream(entries, false).onClose(rows::close);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public void close() {
        insert.close();
        database.close();
    }

    private long count() {
        try (var rows = database.query("select coalesce(max(seq), 0) from commands")) {
            return rows.next() ? rows.integer(0) : 0;
        }
    }

    private static Message read(String body) {
        if (Json.parse(body) instanceof Json.Object object) return new Message(object);
        throw new IllegalStateException("a logged command must be an object: " + body);
    }
}
