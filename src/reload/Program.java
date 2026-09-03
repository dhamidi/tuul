package reload;

/// Defines one complete application generation.
@FunctionalInterface
public interface Program {

    /// Builds the values that must change together. The coordinator calls this
    /// once per candidate. A throw rejects the candidate.
    Generation define() throws Exception;
}
