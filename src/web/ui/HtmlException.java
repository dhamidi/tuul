package web.ui;

/// Markup this library will not write.
///
/// Every one of these is a refusal rather than a failure: a name that is not a
/// name, children inside an element that cannot have them, or text that would
/// end the element it is inside. Guessing what the caller meant is how a
/// library produces markup nobody asked for.
public final class HtmlException extends RuntimeException {

    public HtmlException(String message) {
        super(message);
    }
}
