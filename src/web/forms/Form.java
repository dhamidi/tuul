package web.forms;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;
import json.Json;
import web.Middlewares;
import web.Parameters;
import web.Request;
import web.Requests;

/// A form, defined once and used three times: to draw itself, to read what
/// somebody sent, and to draw itself again with what they sent still in it.
///
/// ```
/// var form = Form.at("/symbols").post()
///         .with(Field.text("name").required().label("Symbol"))
///         .with(Field.checkbox("all").label("Include private members"));
///
/// var submission = form.capture(request);
/// if (!submission.ok()) Forms.reject(Views.page(submission), response);
/// ```
///
/// The definition is also the allowlist. A parameter nobody declared is not
/// captured, so a field that exists in a database and not in the form cannot be
/// set by somebody who guesses its name — which is the whole of what Rails
/// spent years learning to call strong parameters.
public record Form(String name, String action, String method, List<Field> fields, List<Check> checks) {

    /// A rule about the submission as a whole, attached to the field it should
    /// be shown beside. Two dates in the wrong order are nobody's fault
    /// individually, and a password confirmation is about the pair.
    public record Check(String field, String message, Predicate<Json.Object> ok) {}

    public Form {
        var seen = new java.util.HashSet<String>();
        for (var field : fields) {
            if (!seen.add(field.name())) throw new FormException("two fields named " + field.name());
        }
        fields = List.copyOf(fields);
        checks = List.copyOf(checks);
    }

    public static Form at(String action) {
        return new Form("form", action, "post", List.of(), List.of());
    }

    /// A form with a name of its own, which is what an id is built from when a
    /// page holds more than one.
    public static Form named(String name, String action) {
        return new Form(name, action, "post", List.of(), List.of());
    }

    public Form get() {
        return method("get");
    }

    public Form post() {
        return method("post");
    }

    /// A form that means PUT, PATCH or DELETE. It is still submitted as a POST,
    /// because a browser can send nothing else, and carries the parameter that
    /// says what it meant — see [web.Middlewares#methodOverride].
    public Form method(String method) {
        return new Form(name, action, method.toLowerCase(java.util.Locale.ROOT), fields, checks);
    }

    public Form with(Field... added) {
        var next = new ArrayList<>(fields);
        next.addAll(List.of(added));
        return new Form(name, action, method, next, checks);
    }

    public Form check(String field, String message, Predicate<Json.Object> ok) {
        var next = new ArrayList<>(checks);
        next.add(new Check(field, message, ok));
        return new Form(name, action, method, fields, next);
    }

    public Field field(String name) {
        return fields.stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new FormException("no field named " + name + " in " + this.name));
    }

    public boolean has(String name) {
        return fields.stream().anyMatch(field -> field.name().equals(name));
    }

    /// What the form element's `method` attribute has to say — anything that is
    /// not a GET is submitted as a POST.
    public String submitted() {
        return method.equals("get") ? "get" : "post";
    }

    public boolean overridden() {
        return !method.equals("get") && !method.equals("post");
    }

    /// An empty form, for the request that is only asking to see one.
    public Submission blank() {
        return new Submission(this, Parameters.NONE, Json.Object.of(), List.of(), List.of());
    }

    /// A form filled in with what is already there, for editing something that
    /// exists. The values are turned back into the text a browser would have
    /// sent, so that rendering has only one thing to read.
    public Submission showing(Json.Object values) {
        var text = new LinkedHashMap<String, List<String>>();
        for (var field : fields) {
            var value = values.get(field.name());
            if (value == null || value instanceof Json.Null) continue;
            text.put(field.name(), texts(value));
        }
        return new Submission(this, new Parameters(text), values, List.of(), List.of());
    }

    /// Reads a request against this form, taking the parameters the way
    /// everything else in `web` does — path variables, query and body merged.
    public Submission capture(Request request) throws IOException {
        return capture(Requests.params(request));
    }

    /// Reads a submission. Nothing here throws: what somebody typed is data,
    /// and the answer is always a [Submission] an update function can act on.
    ///
    /// Four situations that look alike and are not, in the order they are
    /// decided:
    ///
    ///  - **absent** — the name was not submitted at all. For a checkbox that
    ///    means no, because a browser sends nothing for one that is not ticked;
    ///    for anything else it means unanswered.
    ///  - **blank** — submitted, and nothing but whitespace. Required makes it
    ///    a problem; otherwise a text field keeps the empty string, because
    ///    clearing a field is a thing somebody did, and a number keeps nothing,
    ///    because empty is not zero.
    ///  - **unparseable** — submitted, and not what it claims to be. That is a
    ///    problem about the field, and no value is captured for it.
    ///  - **repeated** — submitted more than once. A field that expects that
    ///    keeps every value; one that does not keeps the first, except a
    ///    checkbox, which keeps the last, because the hidden input that makes an
    ///    unticked box send `0` is written before the box itself.
    public Submission capture(Parameters submitted) {
        var values = Json.Object.of();
        var problems = new ArrayList<Problem>();
        for (var field : fields) {
            var captured = read(field, submitted, problems);
            if (captured != null) values = values.with(field.name(), captured);
        }
        if (problems.isEmpty()) verify(values, problems);
        return new Submission(this, submitted, values, problems, ignored(submitted));
    }

    /// The value of one field, or null when there is none to keep. Problems are
    /// added as they are found.
    private Json read(Field field, Parameters submitted, List<Problem> problems) {
        if (field.kind() == Field.Kind.BOOLEAN) return ticked(field, submitted, problems);

        var texts = field.multiple() ? submitted.all(field.name()) : submitted.first(field.name()).stream().toList();
        var answered = texts.stream().anyMatch(text -> !text.isBlank());
        if (!answered) {
            if (field.mandatory()) problems.add(Problem.of(field.name(), "is required"));
            var kept = field.kind() == Field.Kind.TEXT && submitted.has(field.name());
            return kept && !field.mandatory() ? empty(field) : null;
        }

        var values = new ArrayList<Json>();
        for (var text : texts) {
            var value = coerce(field, text);
            if (value == null) {
                problems.add(Problem.of(field.name(), unparseable(field)));
                return null;
            }
            values.add(value);
        }
        var captured = field.multiple() ? Json.Array.of(values) : values.getFirst();
        for (var rule : field.rules()) {
            var problem = rule.check(captured);
            if (problem.isPresent()) {
                problems.add(Problem.of(field.name(), problem.get()));
                return captured;
            }
        }
        return captured;
    }

    private static Json empty(Field field) {
        return field.multiple() ? Json.Array.of(List.of(Json.of(""))) : Json.of("");
    }

    /// A checkbox says yes by being there. The last value wins so that the
    /// hidden `0` written in front of it is only the answer when the box itself
    /// sent nothing.
    private static Json ticked(Field field, Parameters submitted, List<Problem> problems) {
        var values = submitted.all(field.name());
        var ticked = !values.isEmpty() && !NO.contains(values.getLast().strip().toLowerCase(java.util.Locale.ROOT));
        if (field.mandatory() && !ticked) problems.add(Problem.of(field.name(), "must be checked"));
        return Json.of(ticked);
    }

    private static final List<String> NO = List.of("", "0", "false", "off", "no");

    /// The text as the kind it claims to be, or null if it is not one.
    private static Json coerce(Field field, String text) {
        var value = text.strip();
        try {
            return switch (field.kind()) {
                case TEXT -> Json.of(text);
                case NUMBER -> Json.of(Long.parseLong(value));
                case DECIMAL -> Json.of(Double.parseDouble(value));
                case DATE -> Json.of(LocalDate.parse(value).toString());
                case BOOLEAN -> Json.of(!NO.contains(value.toLowerCase(java.util.Locale.ROOT)));
            };
        } catch (NumberFormatException | DateTimeParseException notThat) {
            return null;
        }
    }

    private static String unparseable(Field field) {
        return switch (field.kind()) {
            case NUMBER -> "must be a whole number";
            case DECIMAL -> "must be a number";
            case DATE -> "must be a date, as YYYY-MM-DD";
            case TEXT, BOOLEAN -> "is not valid";
        };
    }

    /// The checks that are about more than one field. They are only asked when
    /// every field is otherwise fine: telling somebody their passwords do not
    /// match while one of them is empty is not help.
    private void verify(Json.Object values, List<Problem> problems) {
        for (var check : checks) {
            if (!check.ok().test(values)) problems.add(Problem.of(check.field(), check.message()));
        }
    }

    /// What arrived and was not asked for. Not a problem — a form is submitted
    /// with all sorts of things attached to it — but worth being able to see,
    /// because a field that is quietly ignored looks exactly like one that is
    /// quietly broken.
    private List<String> ignored(Parameters submitted) {
        var ignored = new ArrayList<String>();
        for (var name : submitted.names()) {
            if (!has(name) && !name.equals(Middlewares.METHOD)) ignored.add(name);
        }
        return List.copyOf(ignored);
    }

    /// A value as the text a browser would have sent it as.
    private static List<String> texts(Json value) {
        if (value instanceof Json.Array(var items)) return items.stream().map(Rules::plain).toList();
        return List.of(Rules.plain(value));
    }
}
