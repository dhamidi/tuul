package fetch;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Selects the executor that runs HTTP client work.
///
/// [Fetch] uses an execution for transport callbacks and request work. Factory
/// methods create executions that own their worker resources. [#of(Executor)]
/// adapts an executor owned by the application. An execution does not report
/// task results.
public interface Execution extends AutoCloseable {
    /// Runs `action` on this execution.
    ///
    /// The execution may run `action` before this method returns or schedule it
    /// for later. A rejected action follows the executor's rejection behavior.
    void execute(Runnable action);

    /// Releases worker resources owned by this execution.
    ///
    /// Closing a borrowed or current-thread execution does nothing. Closing an
    /// execution from [#flow] or [#virtualThreads] closes its executor service.
    @Override void close();

    /// Adapts an application-owned executor without taking ownership of it.
    /// Closing the returned execution leaves `executor` open.
    static Execution of(Executor executor) { return new Impl(executor, null); }

    /// Creates an execution that runs each action immediately on the thread that calls [#execute].
    ///
    /// Closing the returned execution does nothing.
    static Execution currentThread() { return new Impl(Runnable::run, null); }

    /// Creates an execution with one platform-thread event loop.
    ///
    /// The caller must close the returned execution to release the thread.
    static Execution flow() { var service = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("fetch-flow-", 0).factory()); return new Impl(service, service); }

    /// Creates an execution that runs each action on a new virtual thread.
    ///
    /// The caller must close the returned execution to release its executor.
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
