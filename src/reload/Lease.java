package reload;

/// Holds one active [Generation] for one unit of work.
public interface Lease extends AutoCloseable {

    /// The generation admitted by this lease.
    Generation generation();

    /// Releases this lease and permits retirement after the last release.
    @Override
    void close();
}
