package harness;

import java.util.ArrayList;
import java.util.List;

/// The whole test framework. Assertions collect failures instead of stopping,
/// so one run reports everything that is wrong.
public final class Check {

    private static final List<String> failures = new ArrayList<>();
    private static int checks;

    private Check() {}

    public static void equal(String what, Object expected, Object actual) {
        checks++;
        if (expected.equals(actual)) return;
        failures.add(what + "\n  expected: " + expected + "\n  actual:   " + actual);
    }

    public static void that(String what, boolean condition) {
        checks++;
        if (!condition) failures.add(what);
    }

    public static void throwing(String what, Runnable body) {
        checks++;
        try {
            body.run();
            failures.add(what + " — expected a failure, got none");
        } catch (RuntimeException expected) {
            // that is the point
        }
    }

    /// One suite, run so that a throw inside it does not take the results with
    /// it.
    ///
    /// [#report()] prints only at the end, so anything thrown out of a suite
    /// skipped it and every failure gathered anywhere was lost — the run said
    /// nothing at all about what it had already found. That is how a mutation
    /// that broke a check came to look like a check that did not catch it: the
    /// failure was recorded and never printed, because a later suite crashed
    /// first. A throw is now a failure like any other, named, and the suites
    /// after it still run.
    public static void suite(String name, Suite body) {
        try {
            body.run();
        } catch (Throwable thrown) {
            checks++;
            failures.add(name + " stopped: " + thrown
                    + " — the checks it had not reached did not run");
        }
    }

    /// A suite, which may throw whatever it likes.
    @FunctionalInterface
    public interface Suite {

        void run() throws Exception;
    }

    /// Prints what failed and returns the exit status.
    public static int report() {
        failures.forEach(failure -> System.out.println("FAIL " + failure));
        System.out.println(checks - failures.size() + "/" + checks + " checks passed");
        return failures.isEmpty() ? 0 : 1;
    }
}
