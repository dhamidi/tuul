package web.forms;

/// A mistake in a form's definition, rather than in what somebody submitted.
///
/// Every one of these is a programming error and is thrown where the mistake is
/// written — defining two fields with one name, or asking a form about a field
/// it does not have. What a person types is never one of these; that is a
/// [Problem], which is data.
public final class FormException extends RuntimeException {

    public FormException(String message) {
        super(message);
    }
}
