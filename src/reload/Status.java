package reload;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/// An immutable snapshot of coordinator state.
public record Status(String phase, String activeRevision, Instant activatedAt,
        String candidateRevision, String rejectedRevision, List<Problem> problems,
        Map<String, Integer> leases, long submitted, long activated, long rejected,
        long retired) {

    public Status {
        phase = phase == null ? "idle" : phase;
        activeRevision = activeRevision == null ? "" : activeRevision;
        candidateRevision = candidateRevision == null ? "" : candidateRevision;
        rejectedRevision = rejectedRevision == null ? "" : rejectedRevision;
        problems = problems == null ? List.of() : List.copyOf(problems);
        leases = leases == null ? Map.of() : Map.copyOf(leases);
    }

    /// Answers whether the snapshot has an active revision.
    public boolean active() { return !activeRevision.isEmpty(); }
}
