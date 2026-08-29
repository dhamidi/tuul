/// Embeds Tcl syntax around JVM objects.
///
/// [tcl.Tcl] evaluates source from a [java.io.Reader]. It reads and runs one
/// command before it reads the next command. Variables and results hold JVM
/// objects without an extra string representation.
///
/// Use [tcl.Tcl#invoke(java.lang.Object...)] when the host already has command
/// words. This avoids source construction and keeps each object unchanged.
/// Use [tcl.Repl] when a caller needs one result for each complete command.
///
/// ```
/// var tcl = Tcl.of();
/// tcl.set("counter", new java.util.concurrent.atomic.AtomicInteger());
/// var result = tcl.eval("$counter incrementAndGet; $counter get");
/// ```
///
/// One thread can run an interpreter at a time. A nested call from that thread
/// is valid. A concurrent call from another thread fails with `TCL BUSY`.
package tcl;
