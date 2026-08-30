package project;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Starts commands and streams their standard output to a writer.
///
/// Call [#system()] to start operating-system processes. A caller can provide
/// another runner to control exit status, output, and command execution.
@FunctionalInterface
public interface ProcessRunner {

    /// What happens to the command's standard error.
    enum Errors {
        MERGE, INHERIT
    }

    /// One command, its working directory, its environment changes, and its
    /// standard-error policy.
    record Command(List<String> arguments, Path directory, Map<String, String> environment, Errors errors) {

        public Command {
            arguments = List.copyOf(arguments);
            environment = Map.copyOf(environment);
        }

        public static Command merged(List<String> arguments, Path directory, Map<String, String> environment) {
            return new Command(arguments, directory, environment, Errors.MERGE);
        }

        public static Command inherited(List<String> arguments, Path directory) {
            return new Command(arguments, directory, Map.of(), Errors.INHERIT);
        }
    }

    /// Runs one command and returns its exit status. Output is complete when
    /// this method returns. An interruption restores the thread's interrupt
    /// status before it propagates.
    int run(Command command, Writer output) throws IOException, InterruptedException;

    /// Returns the runner that starts operating-system processes.
    static ProcessRunner system() {
        return SystemProcesses.INSTANCE;
    }
}
