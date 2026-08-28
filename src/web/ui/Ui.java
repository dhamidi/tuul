package web.ui;

import static web.ui.Attributes.classes;
import static web.ui.Attributes.href;

import java.util.List;

/// The components: the vocabulary a page is written in.
///
/// Every one of them is a plain class holding its props and its children, and
/// every one is [Html], so they compose with each other and with the tag
/// helpers without ceremony:
///
/// ```
/// Ui.card(
///         Ui.group(Ui.heading(Props.of("level", "1"), text(name)), Ui.badge(text(kind))),
///         Ui.prose(Props.of("tone", "muted"), text(summary)),
///         Ui.items(Html.each(members, member -> Ui.item(Ui.mono(text(member))))))
/// ```
///
/// The set is small on purpose. It covers laying things out, saying them,
/// listing them and warning about them — which is what a documentation browser
/// does — and stops there. Radix and Mantine ship a hundred components because
/// they must serve applications nobody has written yet; this serves one, and
/// every component in it is one somebody has to learn.
///
/// Each renders a stable class name, `ui-` prefixed, and `assets/ui/ui.css` is
/// what those names mean. The stylesheet ships the way Turbo and the cable's
/// controller do, so an application gets the design by pinning it rather than
/// by copying it.
///
/// Props are declared, and an unknown one is refused — see [Props].
public final class Ui {

    private Ui() {}

    // ---------------------------------------------------------------- layout

    /// Things one above another. `gap` is the space between them.
    public record Stack(Props props, Node[] content) implements Component {

        public Stack {
            props.only("gap");
        }

        @Override
        public Html render() {
            return Html.element("div", Component.rooted("ui-stack ui-gap-" + gap(props), content));
        }
    }

    /// Things beside one another, wrapping when they run out of room. `align`
    /// is how they line up across, `gap` the space between.
    public record Group(Props props, Node[] content) implements Component {

        public Group {
            props.only("gap", "align", "grow");
        }

        @Override
        public Html render() {
            var align = props.one("align", "center", "center", "start", "end", "baseline");
            var grow = props.flag("grow") ? " ui-group--grow" : "";
            return Html.element("div",
                    Component.rooted("ui-group ui-gap-" + gap(props) + " ui-align-" + align + grow, content));
        }
    }

    /// A surface with an edge, for something that is one thing.
    public record Card(Props props, Node[] content) implements Component {

        public Card {
            props.only("pad");
        }

        @Override
        public Html render() {
            var pad = props.one("pad", "md", "sm", "md", "lg");
            return Html.element("div", Component.rooted("ui-card ui-pad-" + pad, content));
        }
    }

    /// A line between things.
    public record Divider(Props props) implements Component {

        public Divider {
            props.only();
        }

        @Override
        public Html render() {
            return Html.element("hr", classes("ui-divider"));
        }
    }

    // ------------------------------------------------------------ typography

    /// A heading. `level` is both what it means and what it renders as, because
    /// a heading that looks like a heading but is not one is invisible to
    /// anything that reads the page rather than looks at it.
    public record Heading(Props props, Node[] content) implements Component {

        public Heading {
            props.only("level");
        }

        @Override
        public Html render() {
            var level = Math.clamp(props.number("level", 2), 1, 6);
            return Html.element("h" + level, Component.rooted("ui-heading ui-heading--" + level, content));
        }
    }

    /// A paragraph. `tone` dims it; `wrap` keeps the line breaks it was given,
    /// which is what a doc comment needs and ordinary prose does not.
    public record Prose(Props props, Node[] content) implements Component {

        public Prose {
            props.only("tone", "wrap");
        }

        @Override
        public Html render() {
            var tone = props.one("tone", "normal", "normal", "muted");
            var wrap = props.flag("wrap") ? " ui-prose--pre" : "";
            return Html.element("p", Component.rooted("ui-prose ui-tone-" + tone + wrap, content));
        }
    }

    /// Code, inline: a name, a signature, a value.
    public record Mono(Props props, Node[] content) implements Component {

        public Mono {
            props.only();
        }

        @Override
        public Html render() {
            return Html.element("code", Component.rooted("ui-mono", content));
        }
    }

    /// A link. `frame` says which panel it drives, and `_top` says it drives
    /// the page — the distinction a Turbo application lives or dies on.
    public record Anchor(Props props, Node[] content) implements Component {

        public Anchor {
            props.only("href", "frame");
        }

        @Override
        public Html render() {
            var frame = props.text("frame", "");
            var rooted = Component.rooted("ui-link", content);
            var nodes = new java.util.ArrayList<Node>(List.of(rooted));
            nodes.add(href(props.text("href", "#")));
            if (!frame.isEmpty()) nodes.add(Turbo.targetFrame(frame));
            return Html.element("a", nodes.toArray(new Node[0]));
        }
    }

    // ---------------------------------------------------------- data display

    /// A small word about something: a kind, a state, a count.
    public record Badge(Props props, Node[] content) implements Component {

        public Badge {
            props.only("tone");
        }

        @Override
        public Html render() {
            var tone = props.one("tone", "accent", "accent", "muted", "plain");
            return Html.element("span", Component.rooted("ui-badge ui-badge--" + tone, content));
        }
    }

    /// A list of [Item]s. `ordered` numbers them; `divided` rules between them.
    public record Items(Props props, Node[] content) implements Component {

        public Items {
            props.only("ordered", "divided");
        }

        @Override
        public Html render() {
            var divided = props.flag("divided") ? " ui-items--divided" : "";
            return Html.element(props.flag("ordered") ? "ol" : "ul",
                    Component.rooted("ui-items" + divided, content));
        }
    }

    public record Item(Props props, Node[] content) implements Component {

        public Item {
            props.only();
        }

        @Override
        public Html render() {
            return Html.element("li", Component.rooted("ui-item", content));
        }
    }

    /// Labelled values — what a type extends, what it permits — as a
    /// description list, which is what that shape is called in HTML.
    public record Facts(Props props, Node[] content) implements Component {

        public Facts {
            props.only();
        }

        @Override
        public Html render() {
            return Html.element("dl", Component.rooted("ui-facts", content));
        }
    }

    /// One labelled value. The label is a prop rather than a child because a
    /// fact without one is not a fact.
    public record Fact(Props props, Node[] content) implements Component {

        public Fact {
            props.only("label");
        }

        @Override
        public Html render() {
            return Html.fragment(
                    Html.element("dt", classes("ui-fact-label"), Html.text(props.text("label", ""))),
                    Html.element("dd", Component.rooted("ui-fact-value", content)));
        }
    }

    /// Where the reader is, and the way back up. `nav` and `aria-label` are
    /// what makes it a landmark rather than a row of links.
    public record Breadcrumbs(Props props, Node[] content) implements Component {

        public Breadcrumbs {
            props.only();
        }

        @Override
        public Html render() {
            return Html.element("nav", Attributes.aria("label", "Breadcrumb"),
                    Html.element("ol", Component.rooted("ui-crumbs", content)));
        }
    }

    public record Crumb(Props props, Node[] content) implements Component {

        public Crumb {
            props.only("href");
        }

        /// The last crumb is where the reader already is, so it is not a link
        /// — a link to here is a link that does nothing.
        @Override
        public Html render() {
            var target = props.text("href", "");
            var inside = target.isEmpty()
                    ? Html.fragment(children(content))
                    : new Anchor(Props.of("href", target), content);
            return Html.element("li", classes("ui-crumb"), inside);
        }
    }

    /// Nothing to show, said on purpose. An empty panel and a broken one look
    /// the same until one of them explains itself.
    public record Blank(Props props, Node[] content) implements Component {

        public Blank {
            props.only();
        }

        @Override
        public Html render() {
            return Html.element("div", Component.rooted("ui-blank", content));
        }
    }

    // -------------------------------------------------------------- feedback

    /// Something the reader needs to know: an explanation, a warning, a
    /// failure. `tone` is which.
    public record Notice(Props props, Node[] content) implements Component {

        public Notice {
            props.only("tone");
        }

        @Override
        public Html render() {
            var tone = props.one("tone", "info", "info", "warn", "error");
            return Html.element("div",
                    Component.rooted("ui-notice ui-notice--" + tone, prepend(Attributes.role("note"), content)));
        }
    }

    // ------------------------------------------------------------- factories

    public static Html stack(Node... content) {
        return new Stack(Props.NONE, content);
    }

    public static Html stack(Props props, Node... content) {
        return new Stack(props, content);
    }

    public static Html group(Node... content) {
        return new Group(Props.NONE, content);
    }

    public static Html group(Props props, Node... content) {
        return new Group(props, content);
    }

    public static Html card(Node... content) {
        return new Card(Props.NONE, content);
    }

    public static Html card(Props props, Node... content) {
        return new Card(props, content);
    }

    public static Html divider() {
        return new Divider(Props.NONE);
    }

    public static Html heading(Node... content) {
        return new Heading(Props.NONE, content);
    }

    public static Html heading(Props props, Node... content) {
        return new Heading(props, content);
    }

    public static Html prose(Node... content) {
        return new Prose(Props.NONE, content);
    }

    public static Html prose(Props props, Node... content) {
        return new Prose(props, content);
    }

    public static Html mono(Node... content) {
        return new Mono(Props.NONE, content);
    }

    public static Html anchor(Props props, Node... content) {
        return new Anchor(props, content);
    }

    public static Html badge(Node... content) {
        return new Badge(Props.NONE, content);
    }

    public static Html badge(Props props, Node... content) {
        return new Badge(props, content);
    }

    public static Html items(Node... content) {
        return new Items(Props.NONE, content);
    }

    public static Html items(Props props, Node... content) {
        return new Items(props, content);
    }

    public static Html item(Node... content) {
        return new Item(Props.NONE, content);
    }

    public static Html facts(Node... content) {
        return new Facts(Props.NONE, content);
    }

    public static Html fact(Props props, Node... content) {
        return new Fact(props, content);
    }

    public static Html breadcrumbs(Node... content) {
        return new Breadcrumbs(Props.NONE, content);
    }

    public static Html crumb(Props props, Node... content) {
        return new Crumb(props, content);
    }

    public static Html blank(Node... content) {
        return new Blank(Props.NONE, content);
    }

    public static Html notice(Props props, Node... content) {
        return new Notice(props, content);
    }

    private static String gap(Props props) {
        return props.one("gap", "md", "none", "sm", "md", "lg");
    }

    private static Html[] children(Node[] content) {
        var children = new java.util.ArrayList<Html>();
        for (var node : content) {
            if (node instanceof Html child) children.add(child);
        }
        return children.toArray(new Html[0]);
    }

    private static Node[] prepend(Attribute first, Node[] content) {
        var nodes = new java.util.ArrayList<Node>();
        nodes.add(first);
        nodes.addAll(List.of(content));
        return nodes.toArray(new Node[0]);
    }
}
