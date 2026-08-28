package actors;

/// How hard a log works to keep a command that has just been appended.
///
/// This is the SQLite `synchronous` pragma, named for what it decides rather
/// than for how it is spelled. Both settings are used with write-ahead logging,
/// and neither risks a corrupt database: the choice is only about how many of
/// the most recent commands survive a machine that stops without warning.
///
/// ## What each one risks
///
/// [#normal] flushes when the write-ahead log is checkpointed rather than on
/// every commit. A process that crashes loses nothing, because the operating
/// system still holds the writes and will complete them. A machine that loses
/// power, or a kernel that panics, can lose the last few commands. The database
/// is still intact and still readable, and the actor replays to a slightly
/// earlier point in its history than it reached.
///
/// [#full] flushes on every commit. A command that has been appended has
/// reached the disk before the actor acts on it, so power loss costs nothing.
/// The price is one fsync per message, which on a spinning disk is a few
/// milliseconds and on consumer SSDs is often worse than the arithmetic
/// suggests.
///
/// ## Which one to pick
///
/// [#normal] is the default because most actors would rather replay a second
/// time than pay a flush per message, and because a lost command is usually
/// re-sent by whoever sent it. Choose [#full] for an actor whose commands cannot
/// be reconstructed by anyone else — a ledger, an audit trail, anything where
/// the log is the only record that the thing happened.
///
/// The setting belongs to one actor rather than to a store, because these two
/// kinds of actor live side by side in one system and only some of them are
/// worth the fsync.
public enum Durability {

    /// Flush at checkpoints. A power cut can lose the newest commands.
    normal,

    /// Flush on every commit. An appended command has reached the disk.
    full;

    /// This setting as SQLite spells it.
    String pragma() {
        return name();
    }
}
