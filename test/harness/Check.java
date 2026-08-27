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

    /// Prints what failed and returns the exit status.
    public static int report() {
        failures.forEach(failure -> System.out.println("FAIL " + failure));
        System.out.println(checks - failures.size() + "/" + checks + " checks passed");
        return failures.isEmpty() ? 0 : 1;
    }
}
