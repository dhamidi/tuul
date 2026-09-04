package project;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Runs a program in a JVM of its own and streams what it says.
///
/// A separate process, not a class loader: an application gets a real exit
/// status, a real stdout, and no way to reach back into tuul.
public final class Launch {

    private Launch() {}

    /// Streams the process output into `out` as it arrives and returns its exit
    /// status.
    public static int run(List<String> command, Path directory, Writer out) throws IOException, InterruptedException {
        return run(command, directory, out, Map.of(), ProcessRunner.system());
    }

    /// The same, with part of the environment replaced — for proving that
    /// something is not being used by taking it away.
    public static int run(List<String> command, Path directory, Writer out, Map<String, String> environment)
            throws IOException, InterruptedException {
        return run(command, directory, out, environment, ProcessRunner.system());
    }

    /// Runs through `processes`. The runner receives merged standard error and
    /// the supplied environment changes.
    public static int run(List<String> command, Path directory, Writer out, Map<String, String> environment,
            ProcessRunner processes) throws IOException, InterruptedException {
        return processes.run(ProcessRunner.Command.merged(command, directory, environment), out);
    }

    /// Runs a command and captures only what it writes to stdout — for the
    /// programs whose output is data rather than conversation. Anything they
    /// say on stderr goes to the console, where a complaint belongs.
    public static int capture(List<String> command, Path directory, Writer out) throws IOException, InterruptedException {
        return capture(command, directory, out, ProcessRunner.system());
    }

    /// Captures standard output through `processes`. Standard error is
    /// inherited and is not written to `out`.
    public static int capture(List<String> command, Path directory, Writer out, ProcessRunner processes)
            throws IOException, InterruptedException {
        return processes.run(ProcessRunner.Command.inherited(command, directory), out);
    }

    /// A command line for one named module and its resolved module path.
    public static List<String> javaModule(List<String> options, List<Path> modulePath,
            String module, String main, List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.add("--module-path");
        command.add(String.join(java.io.File.pathSeparator,
                modulePath.stream().map(path -> path.toAbsolutePath().toString()).toList()));
        command.add("--module");
        command.add(module + "/" + main);
        command.addAll(arguments);
        return command;
    }
}
