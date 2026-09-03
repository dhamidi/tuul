package terminal;

import actors.ActorSystem;
import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TerminalTest {

    private TerminalTest() {}

    public static void run() throws Exception {
        replacesGrowShrinkGrowFrames();
        coalescesActorIngressWithinOneBound();
        terminalUpdateWins();
        runningUpdatesDoNotRegressAcrossFrames();
        reusesStableSlots();
        groupsNotices();
        sharesOneBoundAcrossTasksAndNotices();
        restoresAfterRejectedClose();
    }

    private static void replacesGrowShrinkGrowFrames() throws IOException {
        var out = new FlushWriter();
        var renderer = new TerminalRenderer();
        var one = new ProgressFrame(10, 0, 0, List.of(), 0, List.of(), 0);
        var four = new ProgressFrame(10, 4, 1,
                List.of(
                        new TaskLine("a", "a\nname\033[31m", TaskUpdate.Status.RUNNING,
                                Long.MAX_VALUE - 1, Long.MAX_VALUE, "detail\rtext\u0007", 0),
                        new TaskLine("b", "b", TaskUpdate.Status.RUNNING, 4, -1, "", 1)),
                0, List.of(new NoticeLine("checksum", "missing\nchecksum", 8)), 0);
        var two = new ProgressFrame(10, 5, 1,
                List.of(new TaskLine("a", "a", TaskUpdate.Status.RUNNING, 5, 10, "", 0)),
                0, List.of(), 0);

        var at = 0;
        renderer.write(one, out);
        var first = out.text().substring(at);
        at = out.text().length();
        renderer.write(four, out);
        var grow = out.text().substring(at);
        at = out.text().length();
        renderer.write(two, out);
        var shrink = out.text().substring(at);
        at = out.text().length();
        renderer.write(four, out);
        var regrow = out.text().substring(at);
        at = out.text().length();
        renderer.restore(four, out);
        var restore = out.text().substring(at);
        out.flush();

        Check.equal("the renderer flushes only when its caller asks", 1, out.flushes);
        Check.equal("the first frame clears one row", 1, occurrences(first, "\033[2K"));
        Check.equal("a growing frame clears its four rows", 4, occurrences(grow, "\033[2K"));
        Check.that("a growing frame returns the cursor to its first row", grow.endsWith("\033[3A"));
        Check.equal("a shrinking frame clears stale rows", 4, occurrences(shrink, "\033[2K"));
        Check.that("a shrinking frame preserves the region cursor invariant", shrink.endsWith("\033[3A"));
        Check.equal("a regrowing frame reuses the bounded region", 4, occurrences(regrow, "\033[2K"));
        Check.equal("restore returns modes before one final newline",
                "\033[3B\033[?7h\033[?25h\r\n", restore);
        Check.that("the renderer sanitizes line breaks",
                grow.contains("a name") && grow.contains("detail text") && grow.contains("missing checksum"));
        Check.that("task text cannot inject terminal controls",
                !grow.contains("\033[31m") && !grow.contains("\u0007"));
        Check.that("the renderer marks an unknown task total", grow.contains("[??]") && grow.contains("/?"));
    }

    private static void coalescesActorIngressWithinOneBound() {
        var renderer = new RecordingRenderer();
        var out = new FlushWriter();
        var progress = new Progress(out, 3, Duration.ofHours(1), renderer);
        progress.schedule(5_000);
        for (var at = 0; at < 5_000; at++) {
            progress.publish(TaskUpdate.running("task-" + at, "task-" + at, at, 5_000));
        }
        try (var system = ActorSystem.named("terminal-test")) {
            progress.attach(system);
            progress.close();
        }

        var frame = renderer.last();
        Check.equal("one bounded frame shows two tasks and one overflow row", 2, frame.tasks().size());
        Check.equal("visible slots follow publish order", List.of("task-0", "task-1"),
                frame.tasks().stream().map(TaskLine::id).toList());
        Check.equal("the overflow row counts every hidden task", 4_998, frame.overflowTasks());
        Check.equal("the aggregate and body stay within the line bound", 4, frame.lines());
        Check.equal("each rendered actor frame flushes once", renderer.writes(), out.flushes);
        Check.that("coalescing does not render one frame per update", renderer.writes() < 20);
    }

    private static void terminalUpdateWins() {
        var renderer = new RecordingRenderer();
        var progress = new Progress(new FlushWriter(), 2, Duration.ofHours(1), renderer);
        progress.schedule(1);
        progress.publish(TaskUpdate.running("one", "one", 1, 2));
        progress.publish(TaskUpdate.failed("one", "one", "broken"));
        progress.publish(TaskUpdate.running("one", "one", 2, 2));
        try (var system = ActorSystem.named("terminal-outcome-test")) {
            progress.attach(system);
            progress.close();
        }

        var frame = renderer.last();
        Check.that("a terminal update cannot regress to running", frame.tasks().stream()
                .anyMatch(task -> task.id().equals("one") && task.status() == TaskUpdate.Status.FAILED));
        Check.equal("a failed task counts as finished", 1, frame.finished());
        Check.equal("a failed task increments the failure count", 1, frame.failed());
    }

    private static void runningUpdatesDoNotRegressAcrossFrames() throws Exception {
        var renderer = new RecordingRenderer();
        var progress = new Progress(new FlushWriter(), 2, Duration.ZERO, renderer);
        progress.publish(TaskUpdate.running("one", "one", 10, 100));
        try (var system = ActorSystem.named("terminal-monotonic-test")) {
            progress.attach(system);
            Check.that("the first running update renders", renderer.first.await(5, TimeUnit.SECONDS));
            progress.publish(TaskUpdate.running("one", "one", 2, -1));
            progress.close();
        }

        var task = renderer.last().tasks().stream()
                .filter(line -> line.id().equals("one"))
                .findFirst().orElseThrow();
        Check.equal("running progress does not regress across actor frames", 10L, task.current());
        Check.equal("a later unknown total does not erase a known total", 100L, task.total());
    }

    private static void reusesStableSlots() throws Exception {
        var renderer = new RecordingRenderer();
        var progress = new Progress(new FlushWriter(), 3, Duration.ZERO, renderer);
        progress.publish(TaskUpdate.running("a", "a", 1, 10));
        progress.publish(TaskUpdate.running("b", "b", 1, 10));
        progress.publish(TaskUpdate.running("c", "c", 1, 10));
        try (var system = ActorSystem.named("terminal-slots-test")) {
            progress.attach(system);
            Check.that("the initial slots render", renderer.first.await(5, TimeUnit.SECONDS));
            progress.publish(TaskUpdate.complete("b", "b", 10, "done"));
            progress.publish(TaskUpdate.running("d", "d", 1, 10));
            progress.close();
        }

        var frame = renderer.last();
        var replacement = frame.tasks().stream().filter(task -> task.id().equals("d")).findFirst().orElseThrow();
        Check.equal("a waiting task reuses the completed task slot", 1, replacement.slot());
        Check.that("hidden outcomes remain represented by overflow", frame.overflowTasks() > 0);
        Check.that("slot reuse does not exceed the body bound", frame.lines() <= 4);
    }

    private static void groupsNotices() {
        var renderer = new RecordingRenderer();
        var progress = new Progress(new FlushWriter(), 3, Duration.ofHours(1), renderer);
        progress.publish(new Notice("checksum", "checksum unavailable"));
        progress.publish(new Notice("checksum", "checksum unavailable"));
        progress.publish(new Notice("checksum", "checksum unavailable"));
        try (var system = ActorSystem.named("terminal-notices-test")) {
            progress.attach(system);
            progress.close();
        }

        var notice = renderer.last().notices().getFirst();
        Check.equal("notices are grouped by stable key", 3, notice.count());
    }

    private static void sharesOneBoundAcrossTasksAndNotices() {
        var renderer = new RecordingRenderer();
        var progress = new Progress(new FlushWriter(), 3, Duration.ofHours(1), renderer);
        progress.publish(TaskUpdate.running("a", "a", 1, 10));
        progress.publish(TaskUpdate.running("b", "b", 1, 10));
        progress.publish(TaskUpdate.running("c", "c", 1, 10));
        progress.publish(new Notice("one", "first notice"));
        progress.publish(new Notice("two", "second notice"));
        try (var system = ActorSystem.named("terminal-shared-bound-test")) {
            progress.attach(system);
            progress.close();
        }

        var frame = renderer.last();
        Check.equal("the shared body bound includes its overflow row", 4, frame.lines());
        Check.equal("the overflow row counts a displaced task", 1, frame.overflowTasks());
        Check.equal("the overflow row counts hidden notices", 2, frame.overflowNotices());
    }

    private static void restoresAfterRejectedClose() throws Exception {
        var renderer = new RecordingRenderer();
        var out = new FlushWriter();
        var progress = new Progress(out, 1, Duration.ZERO, renderer);
        var system = ActorSystem.named("terminal-rejected-close-test");
        progress.publish(TaskUpdate.running("one", "one", 1, 2));
        progress.attach(system);
        Check.that("a live frame renders before system close", renderer.first.await(5, TimeUnit.SECONDS));
        system.close();
        progress.close();

        Check.equal("a rejected close still restores the renderer", 1, renderer.restores());
        Check.equal("a rejected close flushes the restore", renderer.writes() + 1, out.flushes);
    }

    private static int occurrences(String text, String needle) {
        var count = 0;
        for (var at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private static final class RecordingRenderer implements ProgressRenderer {
        private final List<ProgressFrame> frames = new ArrayList<>();
        private final CountDownLatch first = new CountDownLatch(1);
        private int writes;
        private int restores;

        @Override
        public synchronized void write(ProgressFrame frame, Writer out) {
            frames.add(frame);
            writes++;
            first.countDown();
        }

        @Override
        public synchronized void restore(ProgressFrame frame, Writer out) {
            restores++;
        }

        private synchronized ProgressFrame last() {
            return frames.getLast();
        }

        private synchronized int writes() {
            return writes;
        }

        private synchronized int restores() {
            return restores;
        }
    }

    private static final class FlushWriter extends Writer {
        private final StringWriter delegate = new StringWriter();
        private int flushes;

        @Override
        public void write(char[] characters, int offset, int length) {
            delegate.write(characters, offset, length);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private String text() {
            return delegate.toString();
        }
    }
}
