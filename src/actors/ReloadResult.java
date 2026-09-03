package actors;

import java.util.List;

/// The awaited result of one actor generation handoff.
///
/// A failed handoff leaves the previous definitions active and keeps every
/// delivery accepted during the boundary queued for those definitions.
/// Problems are intentionally plain text here; the reload coordinator adds
/// source and revision context at its own boundary.
public record ReloadResult(boolean activated, long generation, List<String> problems) {

    public ReloadResult {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }
}
