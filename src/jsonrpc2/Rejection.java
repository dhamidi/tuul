package jsonrpc2;

/// A [Failure], thrown.
///
/// A method reports a specific error by throwing this. Every other exception a
/// method throws becomes error -32603, because the server cannot know what an
/// arbitrary exception means to a client. A [Rejection] is the method saying it
/// does know.
///
/// It is unchecked. A method that refuses a call does not meet an exceptional
/// condition. It answers. To make every caller declare that would be ceremony
/// for nothing.
public final class Rejection extends RuntimeException {

    private final transient Failure failure;

    private Rejection(Failure failure) {
        super(failure.code() + " " + failure.message());
        this.failure = failure;
    }

    public static Rejection of(Failure failure) {
        return new Rejection(failure);
    }

    public static Rejection of(int code, String message) {
        return new Rejection(Failure.of(code, message));
    }

    public Failure failure() {
        return failure;
    }
}
