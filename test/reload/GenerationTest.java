package reload;

import harness.Check;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/// Checks service lookup, generation merging, and definition cleanup.
public final class GenerationTest {

    private GenerationTest() {}

    public static void run() throws Exception {
        servicesAreImmutableAndMergeable();
        compositionClosesPartialGenerations();
    }

    private static void servicesAreImmutableAndMergeable() throws Exception {
        var first = new AtomicInteger();
        var second = new AtomicInteger();
        var order = new ArrayList<String>();
        var capability = Capability.<String>create();
        var one = Generation.empty().with(capability, "one")
                .withServices(Runnable.class, List.of(() -> {}, () -> {}))
                .closing(() -> { order.add("one"); first.incrementAndGet(); });
        var two = Generation.empty().withServices(java.util.function.Supplier.class, List.of(() -> "two"))
                .closing(() -> { order.add("two"); second.incrementAndGet(); });
        var merged = Generation.merge(List.of(one, two));
        Check.equal("the first service list is attached", 2, merged.services(Runnable.class).size());
        Check.equal("the second service list is attached", 1, merged.services(java.util.function.Supplier.class).size());
        Check.equal("a service lookup returns its first provider", "two",
                merged.service(java.util.function.Supplier.class).orElseThrow().get());
        try {
            Generation.merge(List.of(Generation.empty().with(capability, "one"),
                    Generation.empty().with(capability, "duplicate")));
            throw new AssertionError("duplicate capability was accepted");
        } catch (IllegalStateException expected) {
            Check.that("duplicate capability is reported", expected.getMessage().contains("capability"));
        }
        merged.close();
        Check.equal("first merged resource closes", 1, first.get());
        Check.equal("second merged resource closes", 1, second.get());
        Check.equal("merged resources close in reverse generation order", List.of("two", "one"), order);

        var sharedClosed = new AtomicInteger();
        AutoCloseable shared = sharedClosed::incrementAndGet;
        try (var sharedGeneration = Generation.merge(
                Generation.empty().closing(shared), Generation.empty().closing(shared))) {
            Check.equal("shared resources are retained once", 1, sharedGeneration.resources().size());
        }
        Check.equal("shared resources close once", 1, sharedClosed.get());

        var failedClosed = new AtomicInteger();
        var duplicate = Capability.<String>create();
        try {
            Generation.merge(Generation.empty().with(duplicate, "one").closing(failedClosed::incrementAndGet),
                    Generation.empty().with(duplicate, "two").closing(failedClosed::incrementAndGet));
            throw new AssertionError("a failed merge retained resources");
        } catch (IllegalStateException expected) {
            Check.equal("a failed merge closes every input", 2, failedClosed.get());
        }
    }

    private static void compositionClosesPartialGenerations() {
        var closed = new AtomicInteger();
        AutoCloseable shared = closed::incrementAndGet;
        var first = Generation.compose(List.of(
                candidate -> Generation.empty().closing(shared),
                candidate -> Generation.empty().closing(shared),
                candidate -> { throw new IllegalStateException("second definition failed"); }));
        try {
            first.define(null);
            throw new AssertionError("definition failure was accepted");
        } catch (Exception expected) {
            Check.equal("composition closes shared partial resources once", 1, closed.get());
        }
    }
}
