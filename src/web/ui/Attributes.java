package web.ui;

/// The attributes, by name.
///
/// Meant to be imported statically alongside [Tags].
///
/// `title` and `style` are missing on purpose. Both name an element as well as
/// an attribute, and with both classes imported Java would quietly choose the
/// attribute — so `head(title("tuul"))` would compile into a `<head>` with a
/// title attribute and no title. A library that cannot write broken markup by
/// accident cannot leave that lying around, and both are still reachable as
/// [#attribute(String, String)], which is what an inline style or a tooltip is
/// rare enough to deserve.
public final class Attributes {

    private Attributes() {}

    /// Any attribute, for the ones that are not spelled out here — including
    /// the ones a framework invents.
    public static Attribute attribute(String name, String value) {
        return Attribute.of(name, value);
    }

    /// A boolean attribute, by name.
    public static Attribute flag(String name) {
        return Attribute.flag(name);
    }

    /// `data-thing="value"`, which is how HTML carries what is not HTML.
    public static Attribute data(String name, String value) {
        return Attribute.of("data-" + name, value);
    }

    public static Attribute aria(String name, String value) {
        return Attribute.of("aria-" + name, value);
    }

    public static Attribute id(String value) {
        return Attribute.of("id", value);
    }

    /// `class`, which cannot be a method name in Java. Several names join with
    /// spaces, because that is what the attribute means.
    public static Attribute classes(String... names) {
        return Attribute.of("class", String.join(" ", names));
    }

    /// `for`, which cannot be a method name either.
    public static Attribute labelFor(String value) {
        return Attribute.of("for", value);
    }

    public static Attribute href(String value) {
        return Attribute.of("href", value);
    }

    public static Attribute src(String value) {
        return Attribute.of("src", value);
    }

    public static Attribute alt(String value) {
        return Attribute.of("alt", value);
    }

    public static Attribute type(String value) {
        return Attribute.of("type", value);
    }

    public static Attribute name(String value) {
        return Attribute.of("name", value);
    }

    public static Attribute value(String value) {
        return Attribute.of("value", value);
    }

    public static Attribute placeholder(String value) {
        return Attribute.of("placeholder", value);
    }

    public static Attribute action(String value) {
        return Attribute.of("action", value);
    }

    public static Attribute method(String value) {
        return Attribute.of("method", value);
    }

    public static Attribute enctype(String value) {
        return Attribute.of("enctype", value);
    }

    public static Attribute accept(String value) {
        return Attribute.of("accept", value);
    }

    public static Attribute rel(String value) {
        return Attribute.of("rel", value);
    }

    public static Attribute target(String value) {
        return Attribute.of("target", value);
    }

    public static Attribute role(String value) {
        return Attribute.of("role", value);
    }

    public static Attribute lang(String value) {
        return Attribute.of("lang", value);
    }

    public static Attribute charset(String value) {
        return Attribute.of("charset", value);
    }

    public static Attribute content(String value) {
        return Attribute.of("content", value);
    }

    public static Attribute loading(String value) {
        return Attribute.of("loading", value);
    }

    public static Attribute rows(int value) {
        return Attribute.of("rows", String.valueOf(value));
    }

    public static Attribute cols(int value) {
        return Attribute.of("cols", String.valueOf(value));
    }

    public static Attribute colspan(int value) {
        return Attribute.of("colspan", String.valueOf(value));
    }

    public static Attribute rowspan(int value) {
        return Attribute.of("rowspan", String.valueOf(value));
    }

    public static Attribute checked() {
        return Attribute.flag("checked");
    }

    public static Attribute disabled() {
        return Attribute.flag("disabled");
    }

    public static Attribute required() {
        return Attribute.flag("required");
    }

    public static Attribute selected() {
        return Attribute.flag("selected");
    }

    public static Attribute multiple() {
        return Attribute.flag("multiple");
    }

    public static Attribute readonly() {
        return Attribute.flag("readonly");
    }

    public static Attribute autofocus() {
        return Attribute.flag("autofocus");
    }

    public static Attribute novalidate() {
        return Attribute.flag("novalidate");
    }

    public static Attribute hidden() {
        return Attribute.flag("hidden");
    }

    public static Attribute open() {
        return Attribute.flag("open");
    }

    public static Attribute defer() {
        return Attribute.flag("defer");
    }

    public static Attribute async() {
        return Attribute.flag("async");
    }
}
