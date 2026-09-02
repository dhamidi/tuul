package web.forms;

import static web.ui.Attributes.*;
import static web.ui.Tags.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import web.Middlewares;
import web.Response;
import web.Responses;
import web.Status;
import web.ui.Attribute;
import web.ui.Html;
import web.ui.Node;

/// Drawing a form, and drawing it again with what somebody typed still in it.
///
/// The markup is deliberately plain — an element per thing, a class per role,
/// and no opinion about layout — because the part worth having written once is
/// not the boxes. It is that every input carries what was typed rather than
/// what it coerced to, that a label points at its input, that an error is
/// beside the field it belongs to and named by `aria-describedby`, and that a
/// checkbox that is not ticked still says so.
///
/// The classes are `field`, `field-wrong`, `field-hint`, `field-problem` and
/// `form-problems`, and an application styles them. Anything more opinionated
/// would have to be undone by everybody who disagreed.
public final class Forms {

    private Forms() {}

    /// The whole form: the element, the fields in the order they were defined,
    /// and whatever the caller wants after them — a submit button says what it
    /// submits, and only the application knows that.
    public static Html html(Submission submission, Node... after) {
        var definition = submission.form();
        var content = new ArrayList<Node>();
        content.add(method(definition.submitted()));
        content.add(action(definition.action()));
        content.add(attribute("accept-charset", "utf-8"));
        if (!definition.name().equals("form")) content.add(id(definition.name()));
        if (definition.overridden()) content.add(override(definition.method()));
        content.add(problems(submission));
        for (var field : definition.fields()) content.add(field(submission, field));
        content.addAll(List.of(after));
        return form(content.toArray(new Node[0]));
    }

    /// The parameter that tells [web.Middlewares#methodOverride] what the form
    /// meant, since the element itself can only say GET or POST.
    public static Html override(String method) {
        return input(type("hidden"), name(Middlewares.METHOD), value(method));
    }

    /// The problems that belong to no field. Nothing at all when there are
    /// none — an empty box with an alert role announces itself to a screen
    /// reader as though something had happened.
    public static Html problems(Submission submission) {
        var general = submission.general();
        if (general.isEmpty()) return nothing();
        return ul(classes("form-problems"), role("alert"), each(general, problem -> li(text(problem))));
    }

    /// One field: its label, its control, its hint and its problem.
    public static Html field(Submission submission, String name) {
        return field(submission, submission.form().field(name));
    }

    public static Html field(Submission submission, Field field) {
        if (field.input().equals("hidden")) return control(submission, field);

        var wrong = submission.wrong(field.name());
        var described = describedBy(submission, field);
        var content = new ArrayList<Node>();
        content.add(classes(wrong ? "field field-wrong" : "field"));

        var label = label(labelFor(field.id()), text(field.label()));
        var control = control(submission, field, described, wrong);
        if (field.widget() == Field.Widget.CHECKBOX) {
            content.add(control);
            content.add(label);
        } else {
            content.add(label);
            content.add(control);
        }
        if (!field.hint().isEmpty()) {
            content.add(p(classes("field-hint"), id(field.id() + "-hint"), text(field.hint())));
        }
        for (var problem : submission.problems(field.name())) {
            content.add(p(classes("field-problem"), id(field.id() + "-problem"), text(problem)));
        }
        return div(content.toArray(new Node[0]));
    }

    /// Just the control, for a layout this package should not be inventing.
    public static Html control(Submission submission, Field field) {
        return control(submission, field, describedBy(submission, field), submission.wrong(field.name()));
    }

    private static Html control(Submission submission, Field field, List<String> described, boolean wrong) {
        var common = new ArrayList<Attribute>();
        common.add(id(field.id()));
        common.add(name(field.name()));
        if (field.mandatory()) common.add(required());
        if (wrong) common.add(aria("invalid", "true"));
        if (!described.isEmpty()) common.add(aria("describedby", String.join(" ", described)));

        return switch (field.widget()) {
            case TEXTAREA -> textarea(nodes(common, text(submission.typed(field.name()))));
            case CHECKBOX -> ticked(submission, field, common);
            case SELECT -> chosen(submission, field, common);
            case INPUT -> typed(submission, field, common);
        };
    }

    /// A text input, or one of each value when the field is a repeated one —
    /// which is what a browser sends back, and so what it has to be given.
    private static Html typed(Submission submission, Field field, List<Attribute> common) {
        var attributes = new ArrayList<>(common);
        attributes.add(type(field.input()));
        if (!field.placeholder().isBlank()) attributes.add(placeholder(field.placeholder()));
        if (!field.multiple()) {
            attributes.add(value(submission.typed(field.name())));
            return input(attributes.toArray(new Attribute[0]));
        }
        var values = submission.all(field.name());
        var each = values.isEmpty() ? List.of("") : values;
        return fragment(each.stream().map(one -> {
            var repeated = new ArrayList<>(attributes);
            repeated.add(value(one));
            return input(repeated.toArray(new Attribute[0]));
        }).toArray(Html[]::new));
    }

    /// A checkbox, and the hidden input in front of it that makes an unticked
    /// box send something. Without it a form that clears a checkbox sends
    /// nothing at all, which is indistinguishable from not having asked.
    private static Html ticked(Submission submission, Field field, List<Attribute> common) {
        var attributes = new ArrayList<>(common);
        attributes.add(type("checkbox"));
        attributes.add(value("1"));
        if (submission.ticked(field.name())) attributes.add(checked());
        return fragment(
                input(type("hidden"), name(field.name()), value("0")),
                input(attributes.toArray(new Attribute[0])));
    }

    private static Html chosen(Submission submission, Field field, List<Attribute> common) {
        var attributes = new ArrayList<>(common);
        if (field.multiple()) attributes.add(multiple());
        var options = each(field.choices(), choice -> option(chosen(submission, field, choice)));
        return select(nodes(attributes, options));
    }

    /// An option, marked as selected only when it was — a `selected="false"`
    /// selects the option, which is the class of mistake `web.ui` refuses to
    /// let anybody write in the first place.
    private static Node[] chosen(Submission submission, Field field, Field.Choice choice) {
        var nodes = new ArrayList<Node>();
        nodes.add(value(choice.value()));
        if (submission.chose(field.name(), choice.value())) nodes.add(selected());
        nodes.add(text(choice.label()));
        return nodes.toArray(new Node[0]);
    }

    /// The ids of everything describing this control, in the order a reader
    /// would want them read.
    private static List<String> describedBy(Submission submission, Field field) {
        var described = new ArrayList<String>();
        if (!field.hint().isEmpty()) described.add(field.id() + "-hint");
        if (submission.wrong(field.name())) described.add(field.id() + "-problem");
        return described;
    }

    /// Attributes and children as one array, since an element takes them the
    /// same way and sorts them out itself.
    private static Node[] nodes(List<Attribute> attributes, Html... children) {
        var nodes = new ArrayList<Node>(attributes);
        nodes.addAll(List.of(children));
        return nodes.toArray(new Node[0]);
    }

    /// A form that failed, answered the way Turbo needs to hear it: 422, with
    /// the form again. A 200 leaves the page as it was and the person looking
    /// at it with no idea what happened; a redirect throws away what they
    /// typed.
    public static void reject(Html html, Response response) throws IOException {
        Responses.html(html, Status.UNPROCESSABLE, response);
    }
}
