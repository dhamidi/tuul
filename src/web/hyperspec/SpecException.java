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
    }

    public int line() {
        return line;
    }
}
