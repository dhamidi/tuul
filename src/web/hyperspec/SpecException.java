package web.hyperspec;

/// A spec that cannot be run: it does not parse, it names a command nobody
/// knows, or it asks for something the page cannot give.
///
/// Every one of these carries the line it happened on, because a spec is a
/// document a person wrote and the only useful thing to say about a mistake in
/// one is where it is.
public final class SpecException extends RuntimeException {

    private final int line;

    public SpecException(int line, String message) {
        super(line > 0 ? "line " + line + ": " + message : message);
        this.line = line;
        this.reason = message;
    }

    /// What went wrong, without the line it went wrong on.
    ///
    /// [#getMessage] carries the line because an exception is often read on its
    /// own, and a reporter that already knows the line needs the rest without
    /// it — printing `line 2: line 2: …` is what happens when only one of the
    /// two exists.
    public String reason() {
        return reason;
    }

    private final String reason;

    public int line() {
        return line;
    }
}
