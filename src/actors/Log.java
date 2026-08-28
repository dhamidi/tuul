package actors;

import application.Message;
import java.util.stream.Stream;

/// The append-only history of one actor.
///
/// ## Commands, not events
///
/// A log holds the messages that arrived, in the order they were handled. They
/// are commands — statements of intent — and not events, and the difference is
/// what makes an upgrade free. Replaying `{"type":"add","sku":"…"}` through a
/// new pricing rule produces a total computed by the new rule. Replaying a
/// recorded `total` would produce the old one forever.
///
/// The cost of that choice is real and worth stating: only the actor's own
/// definition can interpret its log. Nothing else can read a basket's commands
/// and work out what the basket contains, because the meaning lives in the
/// update functions. A projection that needs the answer asks the actor, or
/// replays it through [System#inspectAt(Address, long)].
///
/// ## Every message, including the ones effects produced
///
/// An effect that reads a clock, a file or a socket returns what it learned as
/// a message, and that message enters the mailbox and is appended here like any
/// other. That is what makes replay safe without running the effect again: the
/// answer the outside world gave is already recorded. It also means the log
/// holds facts that must not be recomputed, which is correct — nobody wants a
/// replay to charge a card a second time or to fetch last year's exchange rate.
///
/// ## Storage is not decided here
///
/// This is an interface because the backing store is a deployment choice.
/// [Journal] writes SQLite files and [#none()] writes nothing. Nothing in this
/// package assumes a log is a file, so a log can move to another store without
/// any actor noticing.
public interface Log extends AutoCloseable {

    /// One entry: where it sits in the history, when it arrived, and what it
    /// said.
    record Entry(long seq, long at, Message command) {}

    /// Appends one command and answers with its sequence number. The sequence
    /// starts at one and never has a gap.
    long append(Message command, long at);

    /// Every entry, in order. The stream holds a cursor, so close it.
    default Stream<Entry> replay() {
        return replay(0, Long.MAX_VALUE);
    }

    /// Entries from `from` onwards, at most `limit` of them. The stream holds a
    /// cursor, so close it.
    Stream<Entry> replay(long from, long limit);

    /// How many entries this log holds.
    long length();

    @Override
    void close();

    /// A log that records nothing and replays nothing.
    ///
    /// This is the whole implementation difference between a durable actor and
    /// an undurable one. Both take the same path through [Actor]; one of them
    /// appends to a file and the other appends to nowhere. Keeping the
    /// undurable case on the same code path is what stops it from drifting into
    /// a second, less-tested kind of actor.
    static Log none() {
        return new Log() {

            @Override
            public long append(Message command, long at) {
                return 0;
            }

            @Override
            public Stream<Entry> replay(long from, long limit) {
                return Stream.of();
            }

            @Override
            public long length() {
                return 0;
            }

            @Override
            public void close() {
                // there is nothing open
            }
        };
    }
}
