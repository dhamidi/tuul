package reload;

/// How state crosses a generation boundary when replay cannot do it.
public enum StatePolicy {
    /// Discard the old in-memory state and use the candidate's initial state.
    RESTART,
    /// Rebuild durable actor state from its recorded command log.
    REPLAY,
    /// Export versioned JSON and let the candidate import it.
    TRANSFER,
    /// Reject replacement while the old state is loaded.
    REFUSE;

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
