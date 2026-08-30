package fetch;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Selects the executor that runs HTTP client work.
///
/// [Fetch] uses an execution for transport callbacks and request work. Factory
/// methods create executions that own their worker resources. [#of(Executor)]
/// adapts an executor owned by the application.
public interface Execution extends AutoCloseable {
    /// Runs `action` on this execution.
    void execute(Runnable action);

    /// Releases worker resources owned by this execution.
    @Override void close();

    /// Adapts an application-owned executor without taking ownership of it.
    static Execution of(Executor executor) { return new Impl(executor, null); }

    /// Creates an execution that runs each action immediately on the calling thread.
    static Execution currentThread() { return new Impl(Runnable::run, null); }

    /// Creates an execution with one platform-thread event loop.
    static Execution flow() { var service = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("fetch-flow-", 0).factory()); return new Impl(service, service); }

    /// Creates an execution that runs actions on virtual threads.
    static Execution virtualThreads() { var service = Executors.newVirtualThreadPerTaskExecutor(); return new Impl(service, service); }

    /// An execution adapter that optionally owns an executor service.
    ///
    /// A null `owned` value means [#close] does not close `executor`. A
    /// non-null `owned` value is closed by [#close].
    record Impl(
            /// The executor that receives actions.
            Executor executor,
            /// The executor service to close, or null when the executor is borrowed.
            ExecutorService owned) implements Execution {
        /// Creates an adapter for `executor` and an optional owned service.
        public Impl { Objects.requireNonNull(executor); }

        /// Returns the adapted executor.
        @Override
        public Executor executor() { return executor; }

        /// Returns the owned service, or null when the executor is borrowed.
        @Override
        public ExecutorService owned() { return owned; }

        /// Runs `action` with the adapted executor.
        public void execute(Runnable action) { executor.execute(action); }

        /// Closes the adapted executor only when this instance owns one.
        public void close() { if (owned != null) owned.close(); }
    }
}
