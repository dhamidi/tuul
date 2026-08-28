package actors;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Decides which process is allowed to run one actor.
///
/// ## Why this exists
///
/// A durable actor is defined by its log. Two processes pointed at the same
/// store will each summon `basket/42`, each replay the same commands, and each
/// append to the same file, and neither will notice the other. SQLite keeps
/// every individual write atomic and has nothing to say about two owners
/// interleaving commands into one history. The two states then diverge, and the
/// log records a sequence of commands that no single actor ever saw.
///
/// An actor whose identity is not exclusive is a cache with a log attached. This
/// interface is what makes the identity exclusive: a system claims an address
/// before it summons anything there, and it holds that claim until the actor
/// stops.
///
/// ## The seam
///
/// [#files(Path)] is the only implementation shipped, and it covers one machine.
/// A cluster needs a renewable lease with an expiry, so that the death of a
/// process releases its actors without anyone tidying up after it. That is a
/// different implementation of this same interface, and [System] does not have
/// to change to take it: the system claims, holds and releases, and it never
/// asks how the claim is enforced.
///
/// ## What a claim does not cover
///
/// A claim protects writing. Reading is left alone on purpose, because
/// [System#inspectAt(Address, long)] has to be able to read the history of an
/// actor that another process is running, and an inspector that had to take the
/// actor away from its owner would be useless.
public interface Ownership extends AutoCloseable {

    /// Claims the exclusive right to run one actor.
    ///
    /// Throws [OwnershipException] when another owner holds it. This never
    /// blocks and never waits: a second owner is a deployment fault rather than
    /// congestion, and waiting would turn a fault that should be reported into
    /// a request that hangs.
    Claim claim(Address address);

    /// Releases everything still held. A system calls this on the way out, as
    /// the backstop for an actor whose thread did not finish in time.
    @Override
    void close();

    /// One actor's right to run, held until it is released.
    ///
    /// The claim outlives the call that took it, so it is not
    /// [AutoCloseable]: it is taken on the thread that summons an actor and
    /// released when that actor's own thread finishes, and a
    /// try-with-resources cannot express that.
    interface Claim {

        /// Gives the actor up. Releasing twice is allowed and does nothing the
        /// second time, because an actor can be evicted and die in a race and
        /// both paths release.
        void release();
    }

    /// Ownership that grants everything to everybody.
    ///
    /// This is right for a system whose actors keep no log, and for a custom
    /// [Logs] implementation that arbitrates writers itself. It is wrong for a
    /// directory of files that two processes can both open, which is why
    /// [System#rooted(Path)] does not use it.
    static Ownership shared() {
        return new Ownership() {

            @Override
            public Claim claim(Address address) {
                return () -> {};
            }

            @Override
            public void close() {
                // nothing was ever held
            }
        };
    }

    /// Ownership enforced by an operating system file lock, for the actors
    /// whose logs live under `root`.
    static Ownership files(Path root) {
        return new Locks(root);
    }
}

/// File locks, one per actor, taken on a file beside the log.
///
/// The lock is taken on `<log>.sqlite.lock` and never on the database itself.
/// Locking the database file would fight SQLite's own locking, which takes and
/// drops locks on that file as it pleases, and the result is a deadlock or a
/// corrupted read depending on the timing. A separate file has no such rules and
/// belongs entirely to this class.
///
/// ## Two signals, one meaning
///
/// [FileChannel#tryLock()] reports a lock held elsewhere in two different ways.
/// Another *process* makes it answer with null. Another lock in *this* JVM makes
/// it throw [OverlappingFileLockException], because a file lock belongs to the
/// whole JVM rather than to a thread or an object. Both mean the same thing here
/// and both raise [OwnershipException]. The second case is what a test can
/// reproduce without starting a second JVM, and it is also what happens when one
/// program builds two systems over one directory by mistake.
final class Locks implements Ownership {

    private final Path root;
    private final Map<Address, Held> held = new ConcurrentHashMap<>();

    Locks(Path root) {
        this.root = root;
    }

    @Override
    public Claim claim(Address address) {
        var at = address.here();
        var file = Path.of(at.path(root) + ".lock");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot make the lock directory for " + at, e);
        }
        var channel = open(file, at);
        var lock = acquire(channel, at, file);
        var claim = new Held(at, channel, lock);
        held.put(at, claim);
        return claim;
    }

    @Override
    public void close() {
        held.values().forEach(Held::release);
        held.clear();
    }

    private static FileChannel open(Path file, Address at) {
        try {
            return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open the lock file for " + at, e);
        }
    }

    private FileLock acquire(FileChannel channel, Address at, Path file) {
        try {
            var lock = channel.tryLock();
            if (lock != null) return lock;
        } catch (OverlappingFileLockException taken) {
            shut(channel);
            throw OwnershipException.taken(at, file);
        } catch (IOException e) {
            shut(channel);
            throw new UncheckedIOException("cannot lock " + file, e);
        }
        shut(channel);
        throw OwnershipException.taken(at, file);
    }

    private static void shut(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // the claim already failed; a channel that will not close cannot make that worse
        }
    }

    private final class Held implements Claim {

        private final Address address;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean released;

        private Held(Address address, FileChannel channel, FileLock lock) {
            this.address = address;
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public synchronized void release() {
            if (released) return;
            released = true;
            held.remove(address, this);
            try {
                lock.release();
            } catch (IOException e) {
                // the process is giving the actor up either way
            }
            shut(channel);
        }
    }
}
