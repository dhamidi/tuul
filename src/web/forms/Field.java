package web.forms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import web.BooleanParameter;
import web.DateParameter;
import web.DecimalParameter;
import web.IDParameter;
import web.IntegerParameter;
import web.LongParameter;
import web.Parameter;
import web.StringParameter;

/// One thing a form asks for.
///
/// A field says three separate things, and keeping them apart is what lets the
/// same definition both read a submission and draw itself: the [Parameter]
/// that parses the value, the [Widget] that asks for it, and the rules that
/// decide whether the parsed value is acceptable.
///
/// The label is what a person reads, and it is derived from the name when
/// nobody says otherwise — `due_date` asks for "Due date" — so that the common
/// case needs no ceremony and the uncommon one is one call.
///
/// The two flags are read as `mandatory` and `multiple` and set as `required()`
/// and `repeated()`: a record cannot have an accessor and a builder of one
/// name, and of the two the builder is the one every application writes.
public record Field(
        Parameter<?> parameter,
        String label,
        Widget widget,
        String input,
        boolean mandatory,
        boolean multiple,
        List<Choice> choices,
        String hint,
        String placeholder,
        List<Rule> rules) {

    /// How the field is drawn. The parameter decides what a value means. This
    /// decides what somebody sees.
    public enum Widget {
        INPUT, TEXTAREA, CHECKBOX, SELECT
    }

    /// One option of a select. The value travels, the label is read.
    public record Choice(String value, String label) {

        public static Choice of(String value) {
            return new Choice(value, value);
        }

        public static Choice of(String value, String label) {
            return new Choice(value, label);
        }
    }

    public Field {
        choices = List.copyOf(choices);
        rules = List.copyOf(rules);
    }

    public String name() {
        return parameter.name();
    }

    public static Field text(String name) {
        return of(new StringParameter(name), Widget.INPUT, "text");
    }

    public static Field textarea(String name) {
        return of(new StringParameter(name), Widget.TEXTAREA, "");
    }

    public static Field password(String name) {
        return of(new StringParameter(name), Widget.INPUT, "password");
    }

    public static Field email(String name) {
        return of(new StringParameter(name), Widget.INPUT, "email").rule(Rules.email());
    }

    public static Field url(String name) {
        return of(new StringParameter(name), Widget.INPUT, "url");
    }

    public static Field search(String name) {
        return of(new StringParameter(name), Widget.INPUT, "search");
    }

    public static Field hidden(String name) {
        return of(new StringParameter(name), Widget.INPUT, "hidden");
    }

    public static Field number(String name) {
        return of(new LongParameter(name), Widget.INPUT, "number");
    }

    public static Field integer(String name) {
        return of(new IntegerParameter(name), Widget.INPUT, "number");
    }

    public static Field id(String name) {
        return of(new IDParameter(name), Widget.INPUT, "number");
    }

    public static Field decimal(String name) {
        return of(new DecimalParameter(name), Widget.INPUT, "number");
    }

    public static Field date(String name) {
        return of(new DateParameter(name), Widget.INPUT, "date");
    }

    /// A checkbox, which is the one field a browser does not send when the
    /// answer is no — see [Form#capture] for what that costs and how it is paid.
    public static Field checkbox(String name) {
        return of(new BooleanParameter(name), Widget.CHECKBOX, "checkbox");
    }

    public static Field choice(String name, Choice... choices) {
        return of(new StringParameter(name), Widget.SELECT, "").options(choices);
    }

    public static Field choice(String name, String... values) {
        var choices = new ArrayList<Choice>();
        for (var value : values) choices.add(Choice.of(value));
        return of(new StringParameter(name), Widget.SELECT, "").options(choices.toArray(new Choice[0]));
    }

    /// A field that parses with an application parameter. Standard parameter
    /// types select their matching HTML input. Other implementations start as
    /// text inputs and can use [#type(String)].
    public static Field of(Parameter<?> parameter) {
        return switch (parameter) {
            case BooleanParameter ignored -> of(parameter, Widget.CHECKBOX, "checkbox");
            case DateParameter ignored -> of(parameter, Widget.INPUT, "date");
            case DecimalParameter ignored -> of(parameter, Widget.INPUT, "number");
            case IDParameter ignored -> of(parameter, Widget.INPUT, "number");
            case IntegerParameter ignored -> of(parameter, Widget.INPUT, "number");
            case LongParameter ignored -> of(parameter, Widget.INPUT, "number");
            default -> of(parameter, Widget.INPUT, "text");
        };
    }

    private static Field of(Parameter<?> parameter, Widget widget, String input) {
        return new Field(parameter, titled(parameter.name()), widget, input, false, false, List.of(), "", "", List.of());
    }

    public Field label(String label) {
        return new Field(parameter, label, widget, input, mandatory, multiple, choices, hint, placeholder, rules);
    }

    /// A word or two under the input, for the thing a label cannot say without
    /// becoming a sentence.
    public Field hint(String hint) {
        return new Field(parameter, label, widget, input, mandatory, multiple, choices, hint, placeholder, rules);
    }

    /// An example or prompt for an empty input. Call this when the label does
    /// not show the expected input shape.
    ///
    /// Blank text omits the placeholder. Checkboxes, selects, and text areas
    /// ignore it. The placeholder disappears after input. It does not replace
    /// [#label(String)] or [#hint(String)].
    public Field placeholder(String placeholder) {
        return new Field(parameter, label, widget, input, mandatory, multiple, choices, hint, placeholder, rules);
    }

    public Field required() {
        return new Field(parameter, label, widget, input, true, multiple, choices, hint, placeholder, rules);
    }

    /// Keeps every value rather than the first, for the fields that are a list:
    /// a multiple select, or a row of checkboxes sharing a name.
    public Field repeated() {
        return new Field(parameter, label, widget, input, mandatory, true, choices, hint, placeholder, rules);
    }

    public Field options(Choice... choices) {
        return new Field(parameter, label, widget, input, mandatory, multiple, List.of(choices), hint, placeholder, rules);
    }

    public Field rule(Rule rule) {
        var next = new ArrayList<>(rules);
        next.add(rule);
        return new Field(parameter, label, widget, input, mandatory, multiple, choices, hint, placeholder, next);
    }

    public Field type(String input) {
        return new Field(parameter, label, widget, input, mandatory, multiple, choices, hint, placeholder, rules);
    }

    /// The id an input gets, and what a label points at. One field, one id —
    /// which is also what makes `aria-describedby` able to name its error. A
    /// name may be anything a form can send, and an id may not, so the
    /// characters an id cannot have become dashes.
    public String id() {
        var id = new StringBuilder();
        for (var character : name().toCharArray()) {
            var keep = Character.isLetterOrDigit(character) || character == '-' || character == '_';
            if (keep) id.append(character);
            else if (!id.isEmpty() && id.charAt(id.length() - 1) != '-') id.append('-');
        }
        while (!id.isEmpty() && id.charAt(id.length() - 1) == '-') id.deleteCharAt(id.length() - 1);
        return id.isEmpty() ? name() : id.toString();
    }

    /// `due_date` and `dueDate` both ask for "Due date". A generated label is
    /// better than a missing one and no worse than a repeated one.
    static String titled(String name) {
        var words = new StringBuilder();
        for (var index = 0; index < name.length(); index++) {
            var character = name.charAt(index);
            if (character == '_' || character == '-' || character == '.') {
                words.append(' ');
                continue;
            }
            if (Character.isUpperCase(character) && !words.isEmpty()) words.append(' ');
            words.append(Character.toLowerCase(character));
        }
        var label = words.toString().strip();
        return label.isEmpty() ? name : label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
    }
}
