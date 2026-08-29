package tcl;

import java.util.List;

/// Runs one Tcl command with words that the interpreter already substituted.
///
/// The argument list does not contain the command name. Return a JVM object as
/// the Tcl result. Throw [TclException] to return a non-zero Tcl code.
@FunctionalInterface
public interface Command {

    /// Runs with the active interpreter and the words after the command name.
    Object call(Tcl tcl, List<Object> args);
}
