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
/// makes an inspector possible without stopping the actor.
///
/// The `synchronous` pragma comes from [Spawn#durability()], so an actor picks
/// it and this class does not decide for everybody. [Durability#normal] flushes
/// at checkpoints and can lose the newest commands to a power cut, without ever
/// corrupting the file. [Durability#full] flushes on every commit and cannot
/// lose a command, at the cost of one flush per message. [Durability] carries
/// the full account of the trade.
public final class Journal implements Log {

    private final Database database;
    private final Statement insert;
    private long length;
    private long applied;

    Journal(java.nio.file.Path file, Address address, Durability durability) {
        database = Database.open(file);
        database.script("""
                pragma journal_mode = wal;
                create table if not exists commands (
                    seq  integer primary key,
                    at   integer not null,
                    type text    not null,
                    body text    not null);
                create table if not exists meta (name text primary key, value text not null);
                """);
        synchronous(durability);
        database.execute("insert or replace into meta (name, value) values ('address', ?)", address.toString());
        insert = Statement.of(database, "insert into commands (seq, at, type, body) values (?, ?, ?, ?)");
        length = count();
        applied = mark();
    }

    /// Applies the `synchronous` pragma.
    ///
    /// This runs on every open rather than only on the first one. The pragma
    /// belongs to a connection and [Journals] keeps one connection per actor for
    /// the life of the process, so setting it once would mean that changing
    /// [Spawn#durability()] and summoning the actor again quietly did nothing.
    /// One pragma per summon costs nothing and removes that surprise.
    void synchronous(Durability durability) {
        database.script("pragma synchronous = " + durability.pragma() + ";");
    }

    /// What this connection's `synchronous` pragma is set to right now.
    ///
    /// SQLite answers with a number: 1 is `normal` and 2 is `full`. The pragma
    /// belongs to a connection, so this can only be asked of the connection
    /// that set it, and that is why it lives here rather than in a test that
    /// opens the file a second time.
    Durability synchronous() {
        try (var rows = database.query("pragma synchronous")) {
            return rows.next() && rows.integer(0) == 2 ? Durability.full : Durability.normal;
        }
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

    /// Re-reads the highest sequence number from the file.
    ///
    /// [Journals] keeps this object open after its actor is evicted, so that an
    /// inspector can still read the history. If another process ran the actor in
    /// the meantime, the file grew and this object's idea of the length did not.
    /// Reading it back at the moment a claim is taken is what stops the next
    /// append from colliding with a row another owner wrote.
    @Override
    public void refresh() {
        length = count();
        applied = mark();
    }

    @Override
    public long applied() {
        return applied;
    }

    /// Moves the mark forward and writes it to the `meta` table.
    ///
    /// This is a separate write from the append rather than part of the same
    /// transaction, and that is the design. Wrapping them together would make
    /// the mark always equal the length, which would erase the very gap the mark
    /// exists to record: a command that was written down before the actor acted
    /// on it.
    @Override
    public void applied(long seq) {
        if (seq <= applied) return;
        applied = seq;
        database.execute("insert or replace into meta (name, value) values ('applied', ?)",
                java.lang.Long.toString(seq));
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

    /// Reads the applied mark, defaulting to zero for a log written before this
    /// column existed or by a store that never set it.
    private long mark() {
        try (var rows = database.query("select value from meta where name = 'applied'")) {
            if (!rows.next()) return 0;
            try {
                return java.lang.Long.parseLong(rows.text(0));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private static Message read(String body) {
        if (Json.parse(body) instanceof Json.Object object) return new Message(object);
        throw new IllegalStateException("a logged command must be an object: " + body);
    }
}
