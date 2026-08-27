package web.assets;

/// An asset that was asked for and is not there.
///
/// This is a failure rather than an empty answer on purpose. A missing asset is
/// a broken page, and a page that half-works while the browser complains about
/// a module specifier it cannot resolve is harder to diagnose than a server
/// that says which file it wanted and where it looked.
public final class AssetException extends RuntimeException {

    public AssetException(String message) {
        super(message);
    }

    public AssetException(String message, Throwable cause) {
        super(message, cause);
    }
}
