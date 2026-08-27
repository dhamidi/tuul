package web.ui;

import java.util.function.Function;

/// The elements, by name.
///
/// Meant to be imported statically: `import static web.ui.Tags.*;` beside
/// `import static web.ui.Attributes.*;`, after which a component reads close to
/// the markup it produces.
///
/// ```
/// Html card(String name) {
///     return div(classes("card"),
///             h1(text(name)),
///             img(src("/avatar/" + name), alt(name)));
/// }
/// ```
///
/// The content factories from [Html] are repeated here — `text`, `unsafe`,
/// `each` and the rest — so that one static import is enough to write a
/// component. They are the same methods; [Html] is where they are explained.
///
/// What is not here is [Html#element(String, Node...)], which writes any
/// element at all — including the custom elements a framework brings, which is
/// how [Turbo] is built.
public final class Tags {

    private Tags() {}

    public static Html text(String value) {
        return Html.text(value);
    }

    /// Markup that something else has already made safe. Say why at the call
    /// site.
    public static Html unsafe(String html) {
        return Html.unsafe(html);
    }

    public static Html fragment(Html... children) {
        return Html.fragment(children);
    }

    public static Html nothing() {
        return Html.nothing();
    }

    public static Html deferred(Html.Body body) {
        return Html.deferred(body);
    }

    public static <T> Html each(Iterable<T> items, Function<? super T, Html> render) {
        return Html.each(items, render);
    }

    /// A whole document, doctype and all.
    public static Html document(Node... content) {
        return Html.fragment(Html.unsafe("<!DOCTYPE html>"), html(content));
    }

    public static Html html(Node... content) {
        return Html.element("html", content);
    }

    public static Html head(Node... content) {
        return Html.element("head", content);
    }

    public static Html body(Node... content) {
        return Html.element("body", content);
    }

    public static Html title(Node... content) {
        return Html.element("title", content);
    }

    public static Html main(Node... content) {
        return Html.element("main", content);
    }

    public static Html header(Node... content) {
        return Html.element("header", content);
    }

    public static Html footer(Node... content) {
        return Html.element("footer", content);
    }

    public static Html nav(Node... content) {
        return Html.element("nav", content);
    }

    public static Html section(Node... content) {
        return Html.element("section", content);
    }

    public static Html article(Node... content) {
        return Html.element("article", content);
    }

    public static Html aside(Node... content) {
        return Html.element("aside", content);
    }

    public static Html div(Node... content) {
        return Html.element("div", content);
    }

    public static Html span(Node... content) {
        return Html.element("span", content);
    }

    public static Html h1(Node... content) {
        return Html.element("h1", content);
    }

    public static Html h2(Node... content) {
        return Html.element("h2", content);
    }

    public static Html h3(Node... content) {
        return Html.element("h3", content);
    }

    public static Html h4(Node... content) {
        return Html.element("h4", content);
    }

    public static Html h5(Node... content) {
        return Html.element("h5", content);
    }

    public static Html h6(Node... content) {
        return Html.element("h6", content);
    }

    public static Html p(Node... content) {
        return Html.element("p", content);
    }

    public static Html a(Node... content) {
        return Html.element("a", content);
    }

    public static Html em(Node... content) {
        return Html.element("em", content);
    }

    public static Html strong(Node... content) {
        return Html.element("strong", content);
    }

    public static Html small(Node... content) {
        return Html.element("small", content);
    }

    public static Html code(Node... content) {
        return Html.element("code", content);
    }

    public static Html pre(Node... content) {
        return Html.element("pre", content);
    }

    public static Html blockquote(Node... content) {
        return Html.element("blockquote", content);
    }

    public static Html time(Node... content) {
        return Html.element("time", content);
    }

    public static Html mark(Node... content) {
        return Html.element("mark", content);
    }

    public static Html label(Node... content) {
        return Html.element("label", content);
    }

    public static Html ul(Node... content) {
        return Html.element("ul", content);
    }

    public static Html ol(Node... content) {
        return Html.element("ol", content);
    }

    public static Html li(Node... content) {
        return Html.element("li", content);
    }

    public static Html dl(Node... content) {
        return Html.element("dl", content);
    }

    public static Html dt(Node... content) {
        return Html.element("dt", content);
    }

    public static Html dd(Node... content) {
        return Html.element("dd", content);
    }

    public static Html table(Node... content) {
        return Html.element("table", content);
    }

    public static Html thead(Node... content) {
        return Html.element("thead", content);
    }

    public static Html tbody(Node... content) {
        return Html.element("tbody", content);
    }

    public static Html tfoot(Node... content) {
        return Html.element("tfoot", content);
    }

    public static Html tr(Node... content) {
        return Html.element("tr", content);
    }

    public static Html th(Node... content) {
        return Html.element("th", content);
    }

    public static Html td(Node... content) {
        return Html.element("td", content);
    }

    public static Html caption(Node... content) {
        return Html.element("caption", content);
    }

    public static Html form(Node... content) {
        return Html.element("form", content);
    }

    public static Html button(Node... content) {
        return Html.element("button", content);
    }

    public static Html select(Node... content) {
        return Html.element("select", content);
    }

    public static Html option(Node... content) {
        return Html.element("option", content);
    }

    public static Html optgroup(Node... content) {
        return Html.element("optgroup", content);
    }

    public static Html textarea(Node... content) {
        return Html.element("textarea", content);
    }

    public static Html fieldset(Node... content) {
        return Html.element("fieldset", content);
    }

    public static Html legend(Node... content) {
        return Html.element("legend", content);
    }

    public static Html figure(Node... content) {
        return Html.element("figure", content);
    }

    public static Html figcaption(Node... content) {
        return Html.element("figcaption", content);
    }

    public static Html details(Node... content) {
        return Html.element("details", content);
    }

    public static Html summary(Node... content) {
        return Html.element("summary", content);
    }

    public static Html dialog(Node... content) {
        return Html.element("dialog", content);
    }

    public static Html template(Node... content) {
        return Html.element("template", content);
    }

    public static Html noscript(Node... content) {
        return Html.element("noscript", content);
    }

    public static Html iframe(Node... content) {
        return Html.element("iframe", content);
    }

    public static Html canvas(Node... content) {
        return Html.element("canvas", content);
    }

    /// The void elements. A tag is the whole of one, so it takes attributes and
    /// nothing else — and [Html.Element] refuses children for them besides.
    public static Html br(Attribute... attributes) {
        return Html.element("br", attributes);
    }

    public static Html hr(Attribute... attributes) {
        return Html.element("hr", attributes);
    }

    public static Html img(Attribute... attributes) {
        return Html.element("img", attributes);
    }

    public static Html input(Attribute... attributes) {
        return Html.element("input", attributes);
    }

    public static Html link(Attribute... attributes) {
        return Html.element("link", attributes);
    }

    public static Html meta(Attribute... attributes) {
        return Html.element("meta", attributes);
    }

    /// A script. Its content is a program rather than markup, so it is written
    /// exactly as given — and refused if it contains anything that would end
    /// the script early.
    public static Html script(String source, Attribute... attributes) {
        return Html.rawText("script", source, attributes);
    }

    /// A script that is only a reference to one.
    public static Html script(Attribute... attributes) {
        return Html.rawText("script", "", attributes);
    }

    /// Stylesheet text, written as given. The attribute of the same name is
    /// `Attributes.style`.
    public static Html style(String css, Attribute... attributes) {
        return Html.rawText("style", css, attributes);
    }
}
