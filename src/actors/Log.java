package actors;

import application.Message;
import java.util.stream.Stream;

/// The append-only history of one actor.
///
/// ## Commands, not events
///
/// A log holds the messages that arrived, in the order they were handled. They
/// are commands — statements of intent — and not events, and the difference is
/// what makes an upgrade free. Replaying `{"type":"add","body":{"sku":"…"}}`
/// through a new pricing rule produces a total computed by the new rule.
/// Replaying a recorded `total` would produce the old one forever.
///
/// The cost of that choice is real and worth stating: only the actor's own
/// definition can interpret its log. Nothing else can read a basket's commands
/// and work out what the basket contains, because the meaning lives in the
/// update functions. A derived view that needs the answer asks the authority,
/// or replays it through [ActorSystem#inspectAt(Address, long)].
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

    /// The envelope field holding where a replayed command sits in the
    /// history.
    ///
    /// A log used to hand back an `Entry` — a sequence number, a timestamp and
    /// the command. Two of those three are envelope fields now and the third is
    /// this, so the triple was a wrapper around a message that already carried
    /// everything it held. The message is the entry.
    String SEQ = "seq";

    /// Where a replayed command sits in the history, or zero for one that was
    /// never in a log.
    static long seq(Message command) {
        return (long) command.envelope().number(SEQ, 0);
    }

    /// Appends one command and answers with its sequence number. The sequence
    /// starts at one and never has a gap.
    ///
    /// The command carries when it arrived, so nothing is passed beside it.
    ///
    /// ## What a log keeps of an envelope
    ///
    /// A log keeps the type, the [application.Message#AT] stamp and the payload,
    /// and it assigns [#SEQ]. **Every other envelope field is dropped**, and a
    /// replayed command carries only those.
    ///
    /// That is a rule, not an accident of how one store is built. It is what
    /// makes it safe for an envelope to be an open bag: a field added to one
    /// cannot start being written to every actor's history because somebody
    /// added a column. A caller's reply address is the case that matters —
    /// [Delivery] keeps it out of the message for that reason, and this keeps
    /// it out of the log even if some future message does carry one.
    ///
    /// An implementation that wants to persist more has to change this
    /// sentence first.
    long append(Message command);

    /// Every command, in order, each carrying its sequence number and the
    /// moment it arrived. The stream holds a cursor, so close it.
    default Stream<Message> replay() {
        return replay(0, Long.MAX_VALUE);
    }

    /// Commands from `from` onwards, at most `limit` of them. The stream holds
    /// a cursor, so close it.
    Stream<Message> replay(long from, long limit);

    /// How many entries this log holds.
    long length();

    /// The highest sequence number whose effects have finished.
    ///
    /// Everything at or below this mark was appended, advanced through the
    /// state, and had its effects carried out. Everything above it was appended
    /// and may or may not have got any further, because the process stopped
    /// somewhere in between. That upper part is the *tail*, and
    /// [Spawn#redelivers()] decides what happens to it on the next summon.
    ///
    /// Only an actor with [Spawn#redelivers()] enabled uses this mark. A log
    /// that records no mark returns zero.
    default long applied() {
        return 0;
    }

    /// Records that everything up to `seq` has been applied.
    ///
    /// The mark only ever moves forward. It is one value rather than a flag on
    /// every entry because a single mailbox thread applies commands strictly in
    /// order, so a high-water mark says exactly what a column of flags would say
    /// and costs one row instead of one per command.
    default void applied(long seq) {
        // there is nothing to remember
    }

    /// Selects the recovery policy for future commands.
    ///
    /// A log records the policy so that a later change to `redelivers` has a
    /// defined boundary. When the policy changes from false to true, the log
    /// marks its current length as applied. Only later commands can redeliver.
    default void redelivers(boolean enabled) {
        // there is nothing to remember
    }

    /// Re-reads whatever this log caches about itself.
    ///
    /// A store may hand the same open log back for the whole life of a process,
    /// because reopening a SQLite file for every summon costs more than keeping
    /// it. That caching is safe while this process is the only writer, and a
    /// handover breaks it: an actor evicted here releases its claim, another
    /// process runs it and appends, and this process summons it again. The
    /// cached sequence number is then behind the file, and the next append would
    /// collide with a row that already exists.
    ///
    /// [ActorSystem] calls this immediately after taking a claim, which is exactly
    /// the moment the cache may be stale and the only moment it can be corrected
    /// safely. A log that caches nothing does nothing here.
    default void refresh() {
        // there is nothing cached
    }

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
            public long append(Message command) {
                return 0;
            }

            @Override
            public Stream<Message> replay(long from, long limit) {
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
