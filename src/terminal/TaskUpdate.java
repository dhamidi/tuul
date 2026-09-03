package terminal;

import java.util.Objects;

/// One immutable state update for one task. `current` is zero or positive.
/// `total` is zero or positive, or `-1` when the total is not known.
public record TaskUpdate(String id, String label, Status status, long current, long total, String detail) {

    /// Identifies whether the task is active, complete, or failed.
    public enum Status {
        RUNNING,
        COMPLETE,
        FAILED
    }

    public TaskUpdate {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("a task id cannot be blank");
        label = label == null || label.isBlank() ? id : label;
        status = Objects.requireNonNull(status, "status");
        if (current < 0) throw new IllegalArgumentException("task current count cannot be negative");
        if (total < -1) throw new IllegalArgumentException("task total must be -1 or non-negative");
        detail = detail == null ? "" : detail;
    }

    /// Creates a running update with no detail text. Use `-1` for an unknown total.
    public static TaskUpdate running(String id, String label, long current, long total) {
        return new TaskUpdate(id, label, Status.RUNNING, current, total, "");
    }

    /// Creates a terminal completion update. Use `-1` for an unknown total.
    public static TaskUpdate complete(String id, String label, long total, String detail) {
        return new TaskUpdate(id, label, Status.COMPLETE, total < 0 ? 0 : total, total, detail);
    }

    /// Creates a terminal failure update with no measured progress.
    public static TaskUpdate failed(String id, String label, String detail) {
        return new TaskUpdate(id, label, Status.FAILED, 0, 0, detail);
    }

    /// Returns whether this update prevents all later updates for the task.
    public boolean terminal() {
        return status != Status.RUNNING;
    }
}
