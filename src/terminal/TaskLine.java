package terminal;

import java.util.Objects;

/// One task line in an immutable [ProgressFrame]. The slot is the actor's
/// stable visible position, or `-1` when the line is an unassigned outcome.
public record TaskLine(String id, String label, TaskUpdate.Status status,
        long current, long total, String detail, int slot) {

    public TaskLine {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("a task line id cannot be blank");
        label = label == null || label.isBlank() ? id : label;
        status = Objects.requireNonNull(status, "status");
        if (current < 0) throw new IllegalArgumentException("task current count cannot be negative");
        if (total < -1) throw new IllegalArgumentException("task total must be -1 or non-negative");
        if (slot < -1) throw new IllegalArgumentException("task slot must be -1 or non-negative");
        detail = detail == null ? "" : detail;
    }

    static TaskLine of(TaskUpdate update, int slot) {
        return new TaskLine(update.id(), update.label(), update.status(),
                update.current(), update.total(), update.detail(), slot);
    }
}
