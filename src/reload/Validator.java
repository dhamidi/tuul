package reload;

import java.util.List;

/// Checks a complete candidate without changing the active generation.
@FunctionalInterface
public interface Validator {

    /// Returns problems. An empty list accepts the candidate.
    List<Problem> validate(Generation candidate) throws Exception;
}
