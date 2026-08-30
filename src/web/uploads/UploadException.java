package web.uploads;

/// A multipart request that the server refuses.
///
/// A malformed request has status 400. A request that exceeds a configured
/// limit has status 413. A handler uses [#status] when it writes the response.
public final class UploadException extends RuntimeException {

    private final int status;

    public UploadException(String message) {
        this(message, web.Status.BAD_REQUEST);
    }

    public UploadException(String message, int status) {
        super(message);
        this.status = status;
    }

    /// Returns the HTTP status for this refusal.
    public int status() {
        return status;
    }
}
