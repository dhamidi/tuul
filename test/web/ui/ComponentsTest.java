package web.ui;

import static web.ui.Attributes.classes;
import static web.ui.Attributes.id;
import static web.ui.Tags.div;
import static web.ui.Tags.text;

import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;

/// The components, which are what a page is written in.
///
/// What is asserted here is what a caller depends on — that a component is
/// markup like any other, that what the caller puts on it survives, and that a
/// mistake in a prop is refused where it is written. The class names are
/// asserted only where the stylesheet's meaning depends on them.
public final class ComponentsTest {

    private ComponentsTest() {}

    public static void run() throws IOException {
        composes();
        merges();
        refuses();
        renders();
        defers();
    }

    /// The point of the design: a component is a [Node], so it goes wherever
    /// one goes and holds whatever one holds.
    private static void composes() {
        Check.that("a component goes inside an element",
                div(id("outer"), Ui.badge(text("new"))).markup().contains("<span class=\"ui-badge"));
        Check.that("and inside another component",
                Ui.card(Ui.stack(Ui.prose(text("inside")))).markup().contains("inside"));
        Check.that("and holds an element as a child",
                Ui.item(div(id("inner"))).markup().contains("<div id=\"inner\">"));
        Check.that("a component is Html, which is what lets any of that work",
                Ui.badge(text("x")) instanceof Html);
    }

    /// A component owns the outside of what it renders and a caller owns the
    /// rest. Both put things on the root, and two class attributes on one
    /// element is invalid HTML.
    private static void merges() {
        var card = Ui.card(classes("mine"), text("x")).markup();
        Check.equal("a caller's classes and the component's end up in one attribute",
                1, count(card, "class="));
        Check.that("with the component's own", card.contains("ui-card"));
        Check.that("and the caller's", card.contains("mine"));

        var badged = Ui.badge(Microdata.of("kind"), text("record")).markup();
        Check.that("an attribute the caller passes lands on the root",
                badged.contains("itemprop=\"kind\""));

        Check.that("and an id does too", Ui.item(id("write")).markup().contains("id=\"write\""));
    }

    /// A prop nobody understands is a typo, and a design system that ignores
    /// one renders something subtly wrong that nobody notices.
    private static void refuses() {
        Check.throwing("a prop the component does not take is refused",
                () -> Ui.card(Props.of("padd", "lg"), text("x")).markup());
        Check.throwing("and so is a value outside the set",
                () -> Ui.badge(Props.of("tone", "danger"), text("x")).markup());
        Check.throwing("props come in pairs", () -> Props.of("tone"));
        Check.throwing("a number that is not one says so", () -> Props.of("level", "big").number("level", 1));

        try {
            Ui.card(Props.of("padd", "lg"), text("x")).markup();
        } catch (HtmlException e) {
            Check.that("and the refusal names what was accepted: " + e.getMessage(),
                    e.getMessage().contains("padd") && e.getMessage().contains("pad"));
        }
    }

    /// What each component renders, where something depends on it.
    private static void renders() {
        Check.that("a heading is the level it says it is",
                Ui.heading(Props.of("level", "1"), text("t")).markup().startsWith("<h1"));
        Check.that("which is what a reader that cannot see the page goes by",
                Ui.heading(Props.of("level", "2"), text("t")).markup().startsWith("<h2"));
        Check.that("and a level nobody has is brought back to one that exists",
                Ui.heading(Props.of("level", "9"), text("t")).markup().startsWith("<h6"));

        Check.that("a list is a list", Ui.items(Ui.item(text("a"))).markup().startsWith("<ul"));
        Check.that("an ordered one is numbered by the browser rather than by us",
                Ui.items(Props.of().on("ordered"), Ui.item(text("a"))).markup().startsWith("<ol"));

        Check.that("a fact is a term and its value, which is what a description list is for",
                Ui.fact(Props.of("label", "extends"), text("Writer")).markup()
                        .equals("<dt class=\"ui-fact-label\">extends</dt>"
                                + "<dd class=\"ui-fact-value\">Writer</dd>"));

        var crumbs = Ui.breadcrumbs(Ui.crumb(Props.of("href", "/"), text("tuul")),
                Ui.crumb(Props.of(), text("here"))).markup();
        Check.that("a trail is a landmark, so something reading the page can skip it",
                crumbs.contains("aria-label=\"Breadcrumb\""));
        Check.that("a crumb with somewhere to go is a link", crumbs.contains("<a class=\"ui-link\" href=\"/\">"));
        Check.that("and the one the reader is on is not, since it would go nowhere",
                !crumbs.contains(">here</a>"));

        Check.that("a link can say which panel it drives",
                Ui.anchor(Props.of("href", "/a", "frame", "_top"), text("x")).markup()
                        .contains("data-turbo-frame=\"_top\""));
        Check.that("and says nothing when it drives the one it is in",
                !Ui.anchor(Props.of("href", "/a"), text("x")).markup().contains("data-turbo-frame"));

        Check.that("prose keeps its line breaks when asked, which is what a doc comment needs",
                Ui.prose(Props.of().on("wrap"), text("a\nb")).markup().contains("ui-prose--pre"));
        Check.that("and does not when it is not", !Ui.prose(text("a")).markup().contains("ui-prose--pre"));

        Check.that("a notice says what kind of thing it is to something reading it",
                Ui.notice(Props.of("tone", "error"), text("no")).markup().contains("role=\"note\""));

        Check.that("text in a component is escaped like text anywhere else",
                Ui.prose(text("<script>")).markup().contains("&lt;script&gt;"));
    }

    /// A component renders when it is written, not when it is built — which is
    /// what keeps a streamed page streamed.
    private static void defers() throws IOException {
        var rendered = new AtomicInteger();
        Component counted = () -> {
            rendered.incrementAndGet();
            return text("done");
        };
        var page = Ui.card(Ui.stack(counted));
        Check.equal("building a page renders nothing", 0, rendered.get());

        var out = new StringWriter();
        page.write(out);
        Check.equal("writing it renders once", 1, rendered.get());
        Check.that("and the result is in the page", out.toString().contains("done"));
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
