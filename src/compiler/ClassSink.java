package compiler;

import java.io.IOException;
import java.io.OutputStream;

/// Receives class files from a [Compiler] as javac produces them.
///
/// The compiler calls [#open(String)] once for each binary name. The caller
/// owns the returned stream after the compiler closes it.
@FunctionalInterface
public interface ClassSink {

    /// Opens the output for one binary name. Nested classes contain `$` in the
    /// name. A module declaration has the name `module-info`.
    OutputStream open(String binaryName) throws IOException;

    /// Opens output for a binary name emitted by a named module.
    /// The default preserves single-module sinks.
    default OutputStream open(String module, String binaryName) throws IOException {
        return open(binaryName);
    }
}
