package uritemplates;

/// A template that cannot be read, or cannot be expanded with the values it was
/// given.
///
/// Both are the caller's mistake rather than a condition to recover from — a
/// template is written once and expanded many times, so a bad one is a bug in
/// the program, not an event in its life.
public final class TemplateException extends RuntimeException {

    public TemplateException(String message) {
        super(message);
    }
}
