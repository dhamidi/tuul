package web.ui;

import static web.ui.Attributes.classes;
import static web.ui.Attributes.href;

import java.util.List;
import web.Feature;
import web.assets.Bundled;

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
/// Each renders a stable class name, `ui-` prefixed, and [#assets()] holds the
/// stylesheet that says what those names mean. It travels with this package, so
/// an application gets the design by naming it rather than by copying it.
///
/// Props are declared, and an unknown one is refused — see [Props].
public final class Ui {

    /// The Stimulus identifier [Sidebar] and [Opener] are driven by, the module
    /// the controller is imported from, and the file it lives in. Named here
    /// because a disagreement between the pin, the file and the identifier is a
    /// menu that silently never opens.
    public static final String CONTROLLER = "ui-sidebar";

    public static final String MODULE = "@tuul/ui-sidebar";

    public static final String FILE = "sidebar.js";

    /// The stylesheet that says what every `ui-` class name means. Named here
    /// because [#feature()] both ships it and links it, and an application that
    /// had to write the `link` itself could ship the design without wearing it.
    public static final String STYLESHEET = "ui.css";

    /// The directory holding [#FILE] and the stylesheet, beside this package's
    /// own code.
    public static final String ASSETS = "assets";

    /// What a [Sidebar] is called when nobody says. The [Opener] has to name
    /// the thing it controls, so the two agree on a default rather than each
    /// having one.
    public static final String SIDEBAR = "sidebar";

    /// What this contributes to an application: the stylesheet, the sidebar
    /// controller, and the name a page imports the controller by.
    ///
    /// Every component here writes a `ui-` prefixed class name and nothing
    /// else, so a page without [#STYLESHEET] renders the right markup with none
    /// of the design on it. That is why the stylesheet is declared here and not
    /// in a layout: shipping the file and linking it are one statement, and an
    /// application says it wants both the same way it says it wants the cable —
    /// by naming this.
    ///
    /// There are no routes here, and that is the point of a feature being able
    /// to have none: a design system serves no URL of its own.
    public static Feature feature() {
        return Feature.named("web.ui")
                .from(Bundled.of(Ui.class, ASSETS))
                .stylesheet(STYLESHEET)
                .pin(MODULE, FILE);
    }

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

    /// Code: a name, a signature, a value. `block` makes it a block of code
    /// rather than a word inside a sentence — a modifier rather than a
    /// component of its own, because the difference is how it sits on the page
    /// and not what it is.
    public record Mono(Props props, Node[] content) implements Component {

        public Mono {
            props.only("block");
        }

        @Override
        public Html render() {
            if (!props.flag("block")) return Html.element("code", Component.rooted("ui-mono", content));

            // The caller's attributes belong on the block and its children
            // inside the code element, so a `pre` carrying microdata still
            // holds nothing but code.
            var outside = new java.util.ArrayList<Node>();
            var inside = new java.util.ArrayList<Node>();
            for (var node : Component.rooted("ui-mono ui-mono--block", content)) {
                if (node instanceof Attribute) outside.add(node);
                else inside.add(node);
            }
            outside.add(Html.element("code", inside.toArray(new Node[0])));
            return Html.element("pre", outside.toArray(new Node[0]));
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
            props.only("ordered", "divided", "columns");
        }

        @Override
        public Html render() {
            var divided = props.flag("divided") ? " ui-items--divided" : "";
            var columns = props.flag("columns") ? " ui-items--columns" : "";
            return Html.element(props.flag("ordered") ? "ol" : "ul",
                    Component.rooted("ui-items" + divided + columns, content));
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
            props.only("href", "frame");
        }

        /// The last crumb is where the reader already is, so it is not a link
        /// — a link to here is a link that does nothing.
        @Override
        public Html render() {
            var target = props.text("href", "");
            var frame = props.text("frame", "");
            var inside = target.isEmpty()
                    ? Html.fragment(children(content))
                    : new Anchor(frame.isEmpty() ? Props.of("href", target) : Props.of("href", target, "frame", frame),
                            content);
            var here = target.isEmpty()
                    ? Attributes.aria("current", "page")
                    : Html.nothing();
            return Html.element("li", classes("ui-crumb"), here, inside);
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

    // -------------------------------------------------------------- controls

    /// A button. `submit` is what a form needs and what says, by being there,
    /// that there is something to submit — a text box alone is a blank
    /// rectangle that a reader has to guess at.
    ///
    /// The fifteenth component, and the first that is a control rather than a
    /// way of saying something. A design system without a button is a design
    /// system for reading only.
    public record Button(Props props, Node[] content) implements Component {

        public Button {
            props.only("submit");
        }

        @Override
        public Html render() {
            var kind = props.flag("submit") ? "submit" : "button";
            return Html.element("button",
                    Component.rooted("ui-button", prepend(Attributes.type(kind), content)));
        }
    }

    /// A panel of navigation beside the page on a wide screen, and behind a
    /// button on a narrow one — one component, because it is one thing.
    ///
    /// It is a `<dialog>`, and that is the whole reason it is worth writing.
    /// `showModal()` brings focus trapping, Escape to close, a backdrop and the
    /// rest of the page made inert, all of which are what a hand-written drawer
    /// gets wrong. The wide case does not open it: the element carries `open`
    /// from the server, so the pane is *there* with no script at all, and CSS
    /// lays it out beside the content. The narrow case is the only one that
    /// needs JavaScript, and [#opener] is a link before it is a button, so a
    /// reader whose scripts failed follows it to a page instead of pressing
    /// something that does nothing.
    ///
    /// `label` names it, because a landmark nobody can name is a landmark
    /// nothing can navigate to.
    public record Sidebar(Props props, Node[] content) implements Component {

        public Sidebar {
            props.only("label", "id");
        }

        /// A header that names it and closes it, then a body that scrolls.
        ///
        /// The close button is not decoration. As a drawer this is a modal
        /// dialog, so Escape closes it and a reader who knows that is fine —
        /// but nothing on screen said so, and a menu somebody cannot see how to
        /// shut is a menu that traps them. The button is the visible half of
        /// what Escape already did.
        ///
        /// The body is the scroller, and it has to be: a dialog with a fixed
        /// height whose content overflows a child paints that content outside
        /// itself and scrolls nothing, which put two thirds of the tree out of
        /// reach on a phone. A flex column with a body that may shrink is what
        /// makes the overflow land somewhere that scrolls.
        @Override
        public Html render() {
            var name = props.text("label", "Navigation");
            var head = Html.element("div", classes("ui-sidebar-head"),
                    Html.element("span", classes("ui-sidebar-name"), Tags.text(name)),
                    new Button(Props.of(), new Node[] {
                            classes("ui-sidebar-close"),
                            Attributes.aria("label", "Close " + name),
                            Stimulus.action(Stimulus.on("click", CONTROLLER, "close")),
                            Html.element("span", Attributes.aria("hidden", "true"), Tags.text("\u00d7"))}));
            var body = Html.element("div", classes("ui-sidebar-body"), Html.fragment(children(content)));

            var nodes = new java.util.ArrayList<Node>(List.of(Component.rooted("ui-sidebar", new Node[] {head, body})));
            nodes.addAll(List.of(attributes(content)));
            nodes.add(Attributes.id(props.text("id", SIDEBAR)));
            nodes.add(Attribute.flag("open"));
            nodes.add(Attributes.aria("label", name));
            nodes.add(Stimulus.target(CONTROLLER, "panel"));
            nodes.add(Stimulus.action(Stimulus.on("click", CONTROLLER, "dismiss")));
            return Html.element("dialog", nodes.toArray(new Node[0]));
        }
    }

    /// What opens a [Sidebar] where there is no room for it beside the content.
    ///
    /// A link, not a button: without JavaScript it goes to `href`, which should
    /// be a page saying what the sidebar says. The controller turns it into a
    /// button — `aria-expanded`, `aria-controls`, focus returned on close — so
    /// with JavaScript it is a menu and without it is a link, and neither is a
    /// dead control.
    public record Opener(Props props, Node[] content) implements Component {

        public Opener {
            props.only("href", "controls");
        }

        @Override
        public Html render() {
            var controls = props.text("controls", SIDEBAR);
            var nodes = new java.util.ArrayList<Node>(List.of(Component.rooted("ui-opener", content)));
            nodes.add(href(props.text("href", "#" + controls)));
            nodes.add(Attributes.aria("controls", controls));
            nodes.add(Attributes.aria("expanded", "false"));
            nodes.add(Stimulus.action(Stimulus.on("click", CONTROLLER, "open")));
            return Html.element("a", nodes.toArray(new Node[0]));
        }
    }

    /// A line in a list somebody navigates: a target big enough to hit, a state
    /// worth seeing, and room for a label and a hint.
    ///
    /// Radix calls this a NavigationMenu item and Mantine a NavLink; the name
    /// here is what it is. It exists because a bare anchor in a list is 18
    /// pixels tall, and a thumb is not — WCAG asks for 44, and everything a
    /// reader taps in a sidebar was under half of that.
    ///
    /// `current` marks where the reader already is. It sets `aria-current` as
    /// well as a class, because "you are here" is information and not
    /// decoration.
    public record Row(Props props, Node[] content) implements Component {

        public Row {
            props.only("href", "frame", "hint", "current");
        }

        @Override
        public Html render() {
            var target = props.text("href", "");
            var hint = props.text("hint", "");
            var here = props.flag("current");
            var inside = new java.util.ArrayList<Node>();
            inside.add(Html.element("span", classes("ui-row-label"), Html.fragment(children(content))));
            if (!hint.isEmpty()) inside.add(Html.element("span", classes("ui-row-hint"), Tags.text(hint)));

            var classes = "ui-row" + (here ? " ui-row--current" : "");
            if (target.isEmpty()) {
                inside.addAll(List.of(attributes(content)));
                return Html.element("span", Component.rooted(classes, inside.toArray(new Node[0])));
            }
            var link = new java.util.ArrayList<Node>(inside);
            link.addAll(List.of(attributes(content)));
            link.add(href(target));
            if (here) link.add(Attributes.aria("current", "true"));
            var frame = props.text("frame", "");
            if (!frame.isEmpty()) link.add(Turbo.targetFrame(frame));
            return Html.element("a", Component.rooted(classes, link.toArray(new Node[0])));
        }
    }

    /// A section that opens and closes, with a heading that says which it is.
    ///
    /// Radix calls it an Accordion and Mantine calls it one too; underneath it
    /// is `<details>` and `<summary>`, because the browser already implements
    /// opening, closing, keyboard operation and find-in-page, and a hand-built
    /// one gets those wrong one at a time. What was missing was never the
    /// behaviour — it was that a bare summary is 29 pixels tall, shows no state
    /// but a default triangle, and looks like nothing anybody designed.
    ///
    /// `open` says it starts open. `label` is the heading.
    public record Disclosure(Props props, Node[] content) implements Component {

        public Disclosure {
            props.only("label", "open");
        }

        @Override
        public Html render() {
            var nodes = new java.util.ArrayList<Node>();
            nodes.add(Html.element("summary", classes("ui-disclosure-head"),
                    Html.element("span", classes("ui-disclosure-mark"), Attributes.aria("hidden", "true")),
                    Html.element("span", classes("ui-disclosure-label"), Tags.text(props.text("label", "")))));
            nodes.add(Html.element("div", classes("ui-disclosure-body"), Html.fragment(children(content))));
            nodes.addAll(List.of(attributes(content)));
            if (props.flag("open")) nodes.add(Attribute.flag("open"));
            return Html.element("details", Component.rooted("ui-disclosure", nodes.toArray(new Node[0])));
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

    public static Html mono(Props props, Node... content) {
        return new Mono(props, content);
    }

    public static Html button(Props props, Node... content) {
        return new Button(props, content);
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

    public static Html row(Props props, Node... content) {
        return new Row(props, content);
    }

    public static Html disclosure(Props props, Node... content) {
        return new Disclosure(props, content);
    }

    public static Html sidebar(Props props, Node... content) {
        return new Sidebar(props, content);
    }

    public static Html opener(Props props, Node... content) {
        return new Opener(props, content);
    }

    private static String gap(Props props) {
        return props.one("gap", "md", "none", "sm", "md", "lg");
    }

    /// The attributes a caller wrote among the children.
    ///
    /// A component that wraps its children in an inner element cannot pass them
    /// all inward: markup belongs inside, but an attribute — `Microdata.of`,
    /// `Turbo.advance()` — was addressed to the thing the component *is*, and
    /// putting it on an inner span loses it as surely as dropping it. So
    /// [#children] takes the markup, this takes the rest, and the root gets
    /// what was meant for it.
    ///
    /// Dropping them silently is what stopped a tree link advancing the URL:
    /// the caller passed `Turbo.advance()`, the row filtered it out, and
    /// nothing anywhere said so.
    private static Node[] attributes(Node[] content) {
        var attributes = new java.util.ArrayList<Node>();
        for (var node : content) {
            if (node instanceof Attribute attribute) attributes.add(attribute);
        }
        return attributes.toArray(new Node[0]);
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
