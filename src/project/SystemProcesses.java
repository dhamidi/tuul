package project;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/// Starts operating-system processes for [ProcessRunner#system()].
final class SystemProcesses implements ProcessRunner {

    static final SystemProcesses INSTANCE = new SystemProcesses();

    private SystemProcesses() {}

    @Override
    public int run(Command command, Writer out) throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command.arguments()).directory(command.directory().toFile());
        builder.environment().putAll(command.environment());
        if (command.errors() == Errors.MERGE) builder.redirectErrorStream(true);
        else builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        var process = builder.start();
        try (var output = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            var buffer = new char[8192];
            for (var read = output.read(buffer); read >= 0; read = output.read(buffer)) {
                out.write(buffer, 0, read);
                out.flush();
            }
        }
        try {
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }
}
