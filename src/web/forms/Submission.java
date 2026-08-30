package web.forms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import application.Message;
import json.Json;
import web.Parameter;
import web.Parameters;

/// What came of reading a request against a form: the values, what was wrong,
/// and — the part that matters — the text somebody actually typed.
///
/// Keeping the raw parameters is the whole reason this type exists. Rendering a
/// form again after a failed submission has to show what was typed rather than
/// what it coerced to: `007` is not `7` while somebody is still looking at it,
/// a date nobody could parse has to stay on the screen to be corrected, and a
/// form that comes back empty is the thing people write frameworks to avoid.
public record Submission(
        Form form,
        Parameters submitted,
        Json.Object values,
        Map<String, Object> parsed,
        List<Problem> problems,
        List<String> ignored) {

    public Submission {
        parsed = Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
        problems = List.copyOf(problems);
        ignored = List.copyOf(ignored);
    }

    public boolean ok() {
        return problems.isEmpty();
    }

    /// Whether anything was submitted at all — false for the blank form a GET
    /// asks for, which is how rendering knows not to show errors nobody has
    /// caused yet.
    public boolean answered() {
        return !submitted.isEmpty();
    }

    public List<String> problems(String field) {
        return problems.stream().filter(problem -> problem.field().equals(field)).map(Problem::message).toList();
    }

    public Optional<String> problem(String field) {
        return problems(field).stream().findFirst();
    }

    /// The problems that belong to the form rather than to any one field.
    public List<String> general() {
        return problems.stream().filter(Problem::general).map(Problem::message).toList();
    }

    public boolean wrong(String field) {
        return !problems(field).isEmpty();
    }

    /// What was typed into this field, exactly. Empty when nothing was.
    public String typed(String field) {
        return submitted.first(field, "");
    }

    /// Everything typed into a field that was answered more than once.
    public List<String> all(String field) {
        return submitted.all(field);
    }

    /// Whether a checkbox is ticked — which is the captured value rather than
    /// the raw text, because "not submitted" and "submitted as 0" both mean no
    /// and rendering should not have to know that.
    public boolean ticked(String field) {
        return values.get(field) instanceof Json.Bool(var value) && value;
    }

    /// Whether a value of a repeated field was chosen, for drawing a select
    /// that remembers what was selected.
    public boolean chose(String field, String value) {
        return all(field).contains(value);
    }

    public Optional<Json> value(String field) {
        return Optional.ofNullable(values.get(field));
    }

    /// The captured value as the same parameter type that parsed the field.
    /// Returns empty when the field has no captured value.
    public <T> Optional<T> value(Parameter<T> parameter) {
        var field = form.fields().stream()
                .filter(candidate -> candidate.parameter().equals(parameter))
                .findFirst();
        if (field.isEmpty() || field.get().multiple()) return Optional.empty();
        return Optional.ofNullable(cast(parsed.get(parameter.name())));
    }

    /// Every captured value of a repeated field, in submission order. Returns
    /// an empty list for a scalar field or a field with no captured value.
    public <T> List<T> values(Parameter<T> parameter) {
        var field = form.fields().stream()
                .filter(candidate -> candidate.parameter().equals(parameter))
                .findFirst();
        if (field.isEmpty() || !field.get().multiple()) return List.of();
        var values = parsed.get(parameter.name());
        if (!(values instanceof List<?> list)) return List.of();
        return list.stream().map(Submission::<T>cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    public String text(String field, String fallback) {
        return values.string(field, fallback);
    }

    /// The message an update function acts on: the values under `values`, and
    /// whether they can be trusted under `ok`.
    ///
    /// Problems travel with it rather than being thrown, because an update that
    /// has to catch an exception to find out that somebody typed a letter into
    /// a number is not the architecture this is built on.
    public Message message(String type) {
        var body = Json.Object.of()
                .with("values", values)
                .with("ok", ok())
                .with("problems", listed());
        return Message.of(type, body);
    }

    private Json.Array listed() {
        var listed = new ArrayList<Json>();
        for (var problem : problems) listed.add(problem.json());
        return Json.Array.of(listed);
    }
}
