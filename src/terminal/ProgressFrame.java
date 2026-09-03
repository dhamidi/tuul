package terminal;

import java.util.List;

/// The complete immutable value that one renderer writes for one frame.
/// `completed` excludes failures. [#finished()] includes them. The task,
/// notice, and optional overflow lines are the bounded frame body.
///
/// [Progress] enforces its configured body bound. A caller that constructs a
/// frame directly must bound these lists before it gives the frame to a
/// renderer.
public record ProgressFrame(int total, int completed, int failed,
        List<TaskLine> tasks, int overflowTasks, List<NoticeLine> notices,
        int overflowNotices, String summary) {

    public ProgressFrame(int total, int completed, int failed, List<TaskLine> tasks,
            int overflowTasks, List<NoticeLine> notices, int overflowNotices) {
        this(total, completed, failed, tasks, overflowTasks, notices, overflowNotices, "");
    }

    public ProgressFrame {
        if (total < 0 || completed < 0 || failed < 0) {
            throw new IllegalArgumentException("progress counts cannot be negative");
        }
        if (overflowTasks < 0 || overflowNotices < 0) {
            throw new IllegalArgumentException("progress overflow cannot be negative");
        }
        if ((long) completed + failed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("finished progress count is too large");
        }
        tasks = List.copyOf(tasks);
        notices = List.copyOf(notices);
        summary = summary == null ? "" : summary;
    }

    /// Returns the number of visible text lines, including the aggregate line.
    public int lines() {
        return 1 + tasks.size() + notices.size()
                + (overflowTasks == 0 && overflowNotices == 0 ? 0 : 1);
    }

    /// Returns the number of tasks with terminal outcomes, including failures.
    public int finished() {
        return completed + failed;
    }
}
