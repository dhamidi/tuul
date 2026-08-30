package harness;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/// Collects assertions and reports one isolated result for each test suite.
///
/// Assertions belong to the suite running on the current thread. A suite can
/// therefore run on its own virtual thread without sharing counts or failures
/// with another suite.
public final class Check {

    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();
    private static final List<SuiteResult> RESULTS = new ArrayList<>();

    private Check() {}

    /// Records a failure when the expected and actual values differ.
    public static void equal(String what, Object expected, Object actual) {
        current().equal(what, expected, actual);
    }

    /// Records a failure when the condition is false.
    public static void that(String what, boolean condition) {
        current().that(what, condition);
    }

    /// Records a failure when the body does not throw a runtime exception.
    public static void throwing(String what, Runnable body) {
        current().throwing(what, body);
    }

    /// Runs a suite with a one-minute timeout.
    public static SuiteResult suite(String name, Suite body) {
        return suite(name, body, Duration.ofMinutes(1));
    }

    /// Runs a suite on a virtual thread and stores its isolated result.
    ///
    /// The suite stops normally when its body returns or throws. The result
    /// records the uncaught throwable when the body throws. The runner
    /// interrupts the suite thread when the timeout expires and records a
    /// timeout instead. The timeout does not remove the protection against a
    /// suite that does not return.
    public static SuiteResult suite(String name, Suite body, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("suite timeout must be positive");
        System.out.println("START " + name);
        System.out.flush();

        var run = new Run();
        var started = System.nanoTime();
        var thread = Thread.ofVirtual().name("test-" + name).start(() -> {
            CURRENT.set(run);
            try {
                body.run();
            } catch (Throwable thrown) {
                run.thrown = thrown;
            } finally {
                CURRENT.remove();
            }
        });

        boolean timedOut = false;
        try {
            thread.join(timeout);
            if (thread.isAlive()) {
                timedOut = true;
                thread.interrupt();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            timedOut = true;
            thread.interrupt();
        }

        var result = new SuiteResult(name, run.checks(), run.failures(),
                Duration.ofNanos(System.nanoTime() - started), run.thrown, timedOut);
        synchronized (RESULTS) {
            RESULTS.add(result);
        }
        return result;
    }

    /// Prints assertion failures, then all suite results from slowest to fastest.
    public static int report() {
        List<SuiteResult> results;
        synchronized (RESULTS) {
            results = List.copyOf(RESULTS);
        }

        var failed = 0;
        var assertionFailures = 0;
        var checks = 0;
        for (var result : results) {
            checks += result.checks();
            for (var failure : result.failures()) {
                System.out.println("FAIL " + result.name() + ": " + failure);
                failed++;
                assertionFailures++;
            }
            if (result.thrown() != null) {
                System.out.println("FAIL " + result.name() + " uncaught: " + result.thrown());
                failed++;
            }
            if (result.timedOut()) {
                System.out.println("FAIL " + result.name() + " timed out");
                failed++;
            }
        }

        System.out.println("SUITES");
        results.stream().sorted((left, right) -> right.elapsed().compareTo(left.elapsed()))
                .forEach(result -> System.out.println("  " + result.name() + " "
                        + (result.checks() - result.failures().size()) + "/" + result.checks()
                        + " checks " + result.elapsed().toMillis() + " ms"
                        + status(result)));
        System.out.println((checks - assertionFailures) + "/" + checks
                + " checks passed in " + results.size() + " suites");
        return failed == 0 ? 0 : 1;
    }

    private static String status(SuiteResult result) {
        if (result.timedOut()) return " TIMEOUT";
        if (result.thrown() != null) return " ERROR";
        return result.failures().isEmpty() ? " PASS" : " FAIL";
    }

    private static Run current() {
        var run = CURRENT.get();
        if (run == null) throw new IllegalStateException("an assertion ran outside a test suite");
        return run;
    }

    private static final class Run {

        private final List<String> failures = new ArrayList<>();
        private int checks;
        private Throwable thrown;

        private synchronized void equal(String what, Object expected, Object actual) {
            checks++;
            if (expected.equals(actual)) return;
            failures.add(what + "\n  expected: " + expected + "\n  actual:   " + actual);
        }

        private synchronized void that(String what, boolean condition) {
            checks++;
            if (!condition) failures.add(what);
        }

        private synchronized void throwing(String what, Runnable body) {
            checks++;
            try {
                body.run();
                failures.add(what + " — expected a failure, got none");
            } catch (RuntimeException expected) {
                // That is the point.
            }
        }

        private synchronized int checks() {
            return checks;
        }

        private synchronized List<String> failures() {
            return List.copyOf(failures);
        }
    }

    /// Describes one completed, failed, or timed-out suite.
    public record SuiteResult(String name, int checks, List<String> failures,
            Duration elapsed, Throwable thrown, boolean timedOut) {
    }

    /// Defines the body that a suite runs.
    @FunctionalInterface
    public interface Suite {

        void run() throws Exception;
    }
}
