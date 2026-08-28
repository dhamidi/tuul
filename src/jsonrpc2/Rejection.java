package jsonrpc2;

/// A method throws a rejection to choose the error that the caller sees.
///
/// Every other exception a method throws becomes error -32603, because the
/// server cannot know what an arbitrary exception should mean to a client.
/// Throwing a rejection is how a method says that it does know.
///
/// This exception is unchecked on purpose. A method that refuses a call has
/// not met an exceptional condition. It has produced an answer. Requiring
/// every caller to declare that would add ceremony without adding safety.
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
