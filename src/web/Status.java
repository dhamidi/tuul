package web;

/// The status codes this framework has an opinion about, by name.
///
/// Not all of them — a number is a perfectly good status — but the ones whose
/// meaning is easy to get wrong, and the reason phrases, so a bare response
/// still says something to whoever is reading a log.
public final class Status {

    public static final int OK = 200;
    public static final int CREATED = 201;
    public static final int NO_CONTENT = 204;

    public static final int MOVED = 301;
    public static final int FOUND = 302;

    /// What a redirect after a form submission has to be. A browser turns 302
    /// into a GET anyway, but Turbo follows the letter of the specification and
    /// will repeat the POST at the new location unless it is told 303 — which is
    /// the single most common way a hypermedia application built on anything
    /// else ends up submitting twice.
    public static final int SEE_OTHER = 303;

    public static final int NOT_MODIFIED = 304;

    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int NOT_ALLOWED = 405;
    public static final int CONFLICT = 409;

    /// What a form that failed validation answers with. Turbo replaces the page
    /// on a 422 and ignores a 200, so the status is what makes the errors
    /// appear.
    public static final int UNPROCESSABLE = 422;

    public static final int TOO_MANY = 429;
    public static final int ERROR = 500;

    private Status() {}

    /// Whether a response with this status is allowed a body at all. A body
    /// after a 204 or a 304 is a protocol error, not a stray write, so the
    /// question belongs here rather than in each server binding.
    public static boolean bodiless(int status) {
        return status == NO_CONTENT || status == NOT_MODIFIED || (status >= 100 && status < 200);
    }

    public static boolean redirect(int status) {
        return status == MOVED || status == FOUND || status == SEE_OTHER || status == 307 || status == 308;
    }

    public static String reason(int status) {
        return switch (status) {
            case OK -> "OK";
            case CREATED -> "Created";
            case NO_CONTENT -> "No Content";
            case MOVED -> "Moved Permanently";
            case FOUND -> "Found";
            case SEE_OTHER -> "See Other";
            case NOT_MODIFIED -> "Not Modified";
            case BAD_REQUEST -> "Bad Request";
            case UNAUTHORIZED -> "Unauthorized";
            case FORBIDDEN -> "Forbidden";
            case NOT_FOUND -> "Not Found";
            case NOT_ALLOWED -> "Method Not Allowed";
            case CONFLICT -> "Conflict";
            case UNPROCESSABLE -> "Unprocessable Content";
            case TOO_MANY -> "Too Many Requests";
            case ERROR -> "Internal Server Error";
            default -> status >= 500 ? "Server Error" : status >= 400 ? "Client Error" : "OK";
        };
    }
}
