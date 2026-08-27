package web.controllers;

/// Something a request asked for that will not be done.
///
/// Every one of these is a refusal rather than a breakage: an upload larger
/// than the limit it was given, a multipart body that ends in the middle of a
/// part, a cookie whose value would end the header it is written into. The
/// alternative to refusing is guessing, and a guess about a hostile request is
/// how a framework acquires a vulnerability.
public final class ControllerException extends RuntimeException {

    private final int status;

    public ControllerException(String message) {
        this(message, web.Status.BAD_REQUEST);
    }

    public ControllerException(String message, int status) {
        super(message);
        this.status = status;
    }

    /// What a handler should answer with if it lets this reach the client. A
    /// body that is too large is a 413 and a malformed one is a 400, and the
    /// difference matters to whoever is reading the log.
    public int status() {
        return status;
    }
}
