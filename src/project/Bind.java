package project;

import ffi.Binding;
import ffi.Header;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Generates a Java binding for a native module: run the C preprocessor over
/// its header, read the declarations, write the Java.
///
/// The compiler is already there to build the module, and it knows the macros,
/// the conditionals and the include paths. Preprocessing with the module's own
/// `cflags` is what makes the binding match the library that was actually
/// built — the same flags decide both.
public final class Bind {

    public record Result(Path header, Path output, int functions, int constants, List<String> skipped) {}

    private Bind() {}

    public static Result generate(Layout layout, String module, String packageName, String type)
            throws IOException, InterruptedException {
        var directory = layout.nativeRoot().resolve(module);
        var header = header(directory, module);
        var output = layout.src().resolve(packageName).resolve(type + ".java");

        var command = new ArrayList<>(List.of(Native.compiler(), "cc", "-E", "-dD"));
        if (command.getFirst().equals("cc")) command.remove(1);
        command.addAll(Native.flags(directory));
        command.add(header.toString());

        var preprocessed = new StringWriter();
        var status = Launch.capture(command, layout.root(), preprocessed);
        if (status != 0) throw new IOException("the preprocessor refused " + header + " (exit " + status + ")");

        var declarations = Header.read(preprocessed.toString(), header.getFileName().toString());
        if (declarations.functions().isEmpty()) throw new IOException("no functions declared in " + header);

        Files.createDirectories(output.getParent());
        try (var out = Files.newBufferedWriter(output)) {
            Binding.write(packageName, type, module, header.toString(), declarations, out);
        }
        return new Result(header, output, declarations.functions().size(), declarations.constants().size(),
                declarations.skipped());
    }

    /// The module's own header — `sqlite3/sqlite3.h` — or the only one there
    /// is.
    private static Path header(Path directory, String module) throws IOException {
        var named = directory.resolve(module + ".h");
        if (Files.isRegularFile(named)) return named;
        try (var files = Files.list(directory)) {
            var headers = files.filter(path -> path.toString().endsWith(".h")).sorted().toList();
            if (headers.size() == 1) return headers.getFirst();
            throw new IOException("no header to bind in " + directory + " — expected " + named.getFileName());
        }
    }
}
