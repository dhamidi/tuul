package web.forms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// One thing a form asks for.
///
/// A field says three separate things, and keeping them apart is what lets the
/// same definition both read a submission and draw itself: what the value *is*
/// once it arrives ([Kind]), how somebody is asked for it ([Widget] and the
/// input type), and what makes an answer acceptable (required, and the rules).
///
/// The label is what a person reads, and it is derived from the name when
/// nobody says otherwise — `due_date` asks for "Due date" — so that the common
/// case needs no ceremony and the uncommon one is one call.
///
/// The two flags are read as `mandatory` and `multiple` and set as `required()`
/// and `repeated()`: a record cannot have an accessor and a builder of one
/// name, and of the two the builder is the one every application writes.
public record Field(
        String name,
        String label,
        Kind kind,
        Widget widget,
        String input,
        boolean mandatory,
        boolean multiple,
        List<Choice> choices,
        String hint,
        List<Rule> rules) {

    /// What the text arriving from a browser becomes.
    ///
    /// Only what a browser can actually send. Everything else — an address, a
    /// postcode, a colour — is text with a rule about it, because a kind that
    /// cannot be coerced back and forth is a validation wearing a type's
    /// clothes.
    public enum Kind {
        TEXT, NUMBER, DECIMAL, BOOLEAN, DATE
    }

    /// How the field is drawn. The kind decides what a value means; this
    /// decides what somebody sees, and the two are not the same question — a
    /// number can be a text box or a slider without changing what it is.
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
        if (name.isBlank()) throw new FormException("a field needs a name");
        choices = List.copyOf(choices);
        rules = List.copyOf(rules);
    }

    public static Field text(String name) {
        return of(name, Kind.TEXT, Widget.INPUT, "text");
    }

    public static Field textarea(String name) {
        return of(name, Kind.TEXT, Widget.TEXTAREA, "");
    }

    public static Field password(String name) {
        return of(name, Kind.TEXT, Widget.INPUT, "password");
    }

    public static Field email(String name) {
        return of(name, Kind.TEXT, Widget.INPUT, "email").rule(Rules.email());
    }

    public static Field url(String name) {
        return of(name, Kind.TEXT, Widget.INPUT, "url");
    }

    public static Field search(String name) {
        return of(name, Kind.TEXT, Widget.INPUT, "search");
    }

    public static Field hidden(String name) {
        return of(name, Kind.TEXT, Widget.INPUT, "hidden");
    }

    public static Field number(String name) {
        return of(name, Kind.NUMBER, Widget.INPUT, "number");
    }

    public static Field decimal(String name) {
        return of(name, Kind.DECIMAL, Widget.INPUT, "number");
    }

    public static Field date(String name) {
        return of(name, Kind.DATE, Widget.INPUT, "date");
    }

    /// A checkbox, which is the one field a browser does not send when the
    /// answer is no — see [Form#capture] for what that costs and how it is paid.
    public static Field checkbox(String name) {
        return of(name, Kind.BOOLEAN, Widget.CHECKBOX, "checkbox");
    }

    public static Field choice(String name, Choice... choices) {
        return of(name, Kind.TEXT, Widget.SELECT, "").options(choices);
    }

    public static Field choice(String name, String... values) {
        var choices = new ArrayList<Choice>();
        for (var value : values) choices.add(Choice.of(value));
        return of(name, Kind.TEXT, Widget.SELECT, "").options(choices.toArray(new Choice[0]));
    }

    private static Field of(String name, Kind kind, Widget widget, String input) {
        return new Field(name, titled(name), kind, widget, input, false, false, List.of(), "", List.of());
    }

    public Field label(String label) {
        return new Field(name, label, kind, widget, input, mandatory, multiple, choices, hint, rules);
    }

    /// A word or two under the input, for the thing a label cannot say without
    /// becoming a sentence.
    public Field hint(String hint) {
        return new Field(name, label, kind, widget, input, mandatory, multiple, choices, hint, rules);
    }

    public Field required() {
        return new Field(name, label, kind, widget, input, true, multiple, choices, hint, rules);
    }

    /// Keeps every value rather than the first, for the fields that are a list:
    /// a multiple select, or a row of checkboxes sharing a name.
    public Field repeated() {
        return new Field(name, label, kind, widget, input, mandatory, true, choices, hint, rules);
    }

    public Field options(Choice... choices) {
        return new Field(name, label, kind, widget, input, mandatory, multiple, List.of(choices), hint, rules);
    }

    public Field rule(Rule rule) {
        var next = new ArrayList<>(rules);
        next.add(rule);
        return new Field(name, label, kind, widget, input, mandatory, multiple, choices, hint, next);
    }

    public Field type(String input) {
        return new Field(name, label, kind, widget, input, mandatory, multiple, choices, hint, rules);
    }

    /// The id an input gets, and what a label points at. One field, one id —
    /// which is also what makes `aria-describedby` able to name its error. A
    /// name may be anything a form can send, and an id may not, so the
    /// characters an id cannot have become dashes.
    public String id() {
        var id = new StringBuilder();
        for (var character : name.toCharArray()) {
            var keep = Character.isLetterOrDigit(character) || character == '-' || character == '_';
            if (keep) id.append(character);
            else if (!id.isEmpty() && id.charAt(id.length() - 1) != '-') id.append('-');
        }
        while (!id.isEmpty() && id.charAt(id.length() - 1) == '-') id.deleteCharAt(id.length() - 1);
        return id.isEmpty() ? name : id.toString();
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
