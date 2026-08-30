package web.dispatch;

/// A mistake in a route definition, or in a use of one.
///
/// Every one of these is a programming error rather than a bad request: a route
/// that cannot be recognised, a name that is not a route, a URL built without
/// the values it needs. They are thrown where the mistake is, which is why they
/// are unchecked — nothing downstream can do anything useful with one except
/// let it through to whoever wrote the route.
public final class DispatchException extends RuntimeException {

    public DispatchException(String message) {
        super(message);
    }
}
