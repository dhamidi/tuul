package web.forms;

import json.Json;

/// What was wrong with one thing somebody submitted.
///
/// A problem names the field it belongs to, because that is what rendering
/// needs: an error printed at the top of a page tells somebody that something
/// is wrong, and an error printed beside an input tells them what to do. A
/// problem with an empty field name belongs to the form as a whole — two dates
/// in the wrong order are not the fault of either one.
public record Problem(String field, String message) {

    /// A problem with the submission rather than with any one field.
    public static Problem of(String message) {
        return new Problem("", message);
    }

    public static Problem of(String field, String message) {
        return new Problem(field, message);
    }

    public boolean general() {
        return field.isEmpty();
    }

    /// As JSON, for the message an update function receives.
    public Json.Object json() {
        return Json.Object.of().with("field", field).with("message", message);
    }

    @Override
    public String toString() {
        return general() ? message : field + ": " + message;
    }
}
