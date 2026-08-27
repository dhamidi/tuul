package project;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/// Runs a program in a JVM of its own and streams what it says.
///
/// A separate process, not a class loader: an application gets a real exit
/// status, a real stdout, and no way to reach back into tuul.
public final class Launch {

    private Launch() {}

    /// Streams the process output into `out` as it arrives and returns its exit
    /// status.
    public static int run(List<String> command, Path directory, Writer out) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        try (var output = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            var buffer = new char[4096];
            for (var read = output.read(buffer); read >= 0; read = output.read(buffer)) {
                out.write(buffer, 0, read);
                out.flush();
            }
        }
        return process.waitFor();
    }

    /// A command line for the JVM tuul is running on.
    public static List<String> java(List<String> options, List<Path> classpath, String main, List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.add("-classpath");
        command.add(classpath.stream().map(path -> path.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator)));
        command.add(main);
        command.addAll(arguments);
        return command;
    }
}
