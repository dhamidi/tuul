package browser;

import browser.Symbols.Found;
import browser.Symbols.Symbol;
import harness.Check;
import java.util.List;
import json.Json;
import web.dispatch.Router;
import web.ui.Html;

/// The views, which are pure functions from a value to markup.
///
/// They need no server either, so what a page offers can be checked as cheaply
/// as what an update decides. What is asserted here is the part a reader
/// depends on — where a link goes, what a page says it is describing, which
/// panel a link drives — and never the words in between, which are the
/// application's to change.
public final class ViewsTest {

    private ViewsTest() {}

    public static void run(Router routes) {
        results(routes);
        links(routes);
        symbol(routes);
        documents(routes);
        members(routes);
        problems(routes);
        form(routes);
        navigates();
    }

    /// The four things the results panel can be, all of them inside the panel.
    private static void results(Router routes) {
        var blank = markup(Views.results(routes, Found.nothing()));
        Check.that("the front page's results are still a panel", blank.startsWith("<turbo-frame id=\"results\""));
        Check.that("holding nothing to click", !blank.contains("<ol"));
        Check.that("and offering no symbol", !blank.contains("itemtype=\"/Symbol\""));

        var nothing = markup(Views.results(routes, Found.nothing().asking("zzz").matching(List.of())));
        Check.that("a search that matched nothing is a panel too",
                nothing.startsWith("<turbo-frame id=\"results\""));
        Check.that("with nothing in it", !nothing.contains("itemtype=\"/Symbol\""));

        var some = markup(Views.results(routes, Found.nothing().asking("json").matching(
                List.of(match("json.Json", "INTERFACE"), match("json.JsonWriter", "CLASS")))));
        Check.that("results arrive in the panel", some.startsWith("<turbo-frame id=\"results\""));
        Check.equal("one entry for each of them", 2, count(some, "itemtype=\"/Symbol\""));
        Check.that("each of which is a resource a spec can ask about", some.contains("itemprop=\"name\""));
        Check.that("and says what kind of thing it is", some.contains("itemprop=\"kind\""));
        Check.that("in the way a person reads rather than the way an enum spells it",
                some.contains(">interface<") && !some.contains(">INTERFACE<"));

        var failed = markup(Views.results(routes, Found.nothing().asking("...").failed("the index said no")));
        Check.that("a failure is a panel as well, so the page still works",
                failed.startsWith("<turbo-frame id=\"results\""));
    }

    /// Where a result goes, which is the only reason a result exists.
    private static void links(Router routes) {
        var type = markup(Views.results(routes, Found.nothing().asking("json").matching(
                List.of(match("json.Json", "INTERFACE")))));
        Check.that("a result links to the symbol's page", type.contains("href=\"/symbols/json.Json\""));
        Check.that("and leaves the panel it was written in, since a page is not a panel",
                type.contains("data-turbo-frame=\"_top\""));

        var member = markup(Views.results(routes, Found.nothing().asking("write").matching(
                List.of(match("json.Json#write", "METHOD")))));
        Check.that("a member links to its type's page and the place on it",
                member.contains("href=\"/symbols/json.Json#write\""));

        var nested = markup(Views.results(routes, Found.nothing().asking("null").matching(
                List.of(match("json.Json.Null", "RECORD")))));
        Check.that("a nested type is a page of its own",
                nested.contains("href=\"/symbols/json.Json.Null\""));

        var slashed = markup(Views.results(routes, Found.nothing().asking("odd").matching(
                List.of(match("a.b.C/D", "CLASS")))));
        Check.that("a name with a separator in it is escaped into the path rather than splitting it",
                slashed.contains("href=\"/symbols/a.b.C%2FD\""));

        var junk = markup(Views.results(routes, Found.nothing().asking("j").matching(List.of(Json.of("not a match")))));
        Check.that("something that is not a match is left out rather than rendered as one",
                !junk.contains("<li"));

        var document = markup(Views.results(routes, Found.nothing().asking("first").matching(
                List.of(match("tcl/tutorial/first-script", "tutorial")))));
        Check.that("a document result links to its document route",
                document.contains("href=\"/symbols/tcl/tutorial/first-script\""));
    }

    private static void documents(Router routes) {
        var listed = Json.Array.of(List.of(Json.Object.of()
                .with("kind", "tutorial").with("slug", "").with("title", "A first script")));
        var package_ = Json.Object.of()
                .with("class", "tcl").with("kind", "package")
                .with("documents", listed)
                .with("nested", Json.Array.of(List.of()));
        var hub = markup(Views.symbol(routes, Symbol.nothing().asking("tcl").describing(package_)));
        Check.that("a package links to each document", hub.contains("href=\"/symbols/tcl/tutorial\""));

        var description = Json.Object.of()
                .with("package", "tcl").with("kind", "tutorial").with("slug", "")
                .with("title", "A first script").with("doc", "# A first script\n\nRun Tcl.\n")
                .with("documents", listed);
        var page = markup(Views.packageDocument(routes, markdown.Links.NONE, description));
        Check.that("a document page describes a document", page.contains("itemtype=\"/Document\""));
        Check.that("the document names its package", page.contains("itemprop=\"package\" content=\"tcl\""));
        Check.equal("the source title does not add a second first-level heading", 1, count(page, "<h1"));
    }

    /// A symbol page: what it says it is describing, and what it links to.
    private static void symbol(Router routes) {
        var page = markup(Views.symbol(routes, Symbol.nothing().asking("json.JsonWriter").describing(description())));

        Check.that("a symbol page describes a symbol", page.contains("itemtype=\"/Symbol\""));
        Check.that("naming it, so a spec can ask which one", page.contains("itemprop=\"name\""));
        Check.that("saying what kind of thing it is", page.contains("itemprop=\"kind\""));
        Check.that("and what it says about itself", page.contains("itemprop=\"doc\""));
        Check.that("with the modifiers it was declared with",
                page.contains(">public<") && page.contains(">final<"));

        Check.that("a supertype is a link, since following one is why this page exists",
                page.contains("href=\"/symbols/java.io.Writer\""));
        Check.that("and is marked as a supertype rather than left as words",
                page.contains("itemprop=\"supertype\""));
        Check.that("an interface it implements is a link too",
                page.contains("href=\"/symbols/java.lang.Comparable\""));
        Check.that("with the type argument still written out, since that is what it says",
                page.contains("&lt;json.Json&gt;"));
        Check.that("but not linked, because there is no page for a name with brackets in it",
                !page.contains("href=\"/symbols/java.lang.Comparable&lt;"));

        Check.that("a sealed type's cases are links, because they are where the reader goes next",
                page.contains("href=\"/symbols/json.JsonWriter.Pretty\""));
        Check.that("and are called cases rather than supertypes, which they are the opposite of",
                page.contains("itemprop=\"case\""));
        Check.that("a type it declares is a link too — otherwise a marker interface is a blank page",
                page.contains("href=\"/symbols/json.JsonWriter.Frame\""));
        Check.that("said to be declared, not inherited", page.contains("itemprop=\"declares\""));

        Check.that("a tag on the type is shown", page.contains("@since"));

        var empty = markup(Views.symbol(routes, Symbol.nothing().asking("json.Empty")
                .describing(Json.Object.of().with("class", "json.Empty").with("kind", "class"))));
        Check.that("a type with no members has no section for them",
                !empty.contains("Constructors and methods") && !empty.contains("Fields"));
        Check.that("a type with no supertype says nothing about one", !empty.contains("extends"));
        Check.that("nor about cases it does not have", !empty.contains("permits"));
        Check.that("and one with nothing to say says nothing", !empty.contains("itemprop=\"doc\""));
    }

    /// The members, which are what a search result points into.
    private static void members(Router routes) {
        var page = markup(Views.symbol(routes, Symbol.nothing().asking("json.Json").describing(overloaded())));

        Check.that("a member is a resource of its own", page.contains("itemtype=\"/Member\""));
        Check.that("with the signature it was declared with", page.contains("itemprop=\"signature\""));
        Check.that("a member has the id a result links to", page.contains("id=\"write\""));
        Check.that("an overload gets an id of its own, because two cannot share one",
                page.contains("id=\"write-2\""));
        Check.equal("and a third gets a third", 1, count(page, "id=\"write-3\""));

        Check.that("a type named in a signature is a link, so a return type can be followed",
                page.contains("href=\"/symbols/java.io.Writer\""));
        Check.that("a parameter name is not, since it names nothing this browser can show",
                !page.contains("href=\"/symbols/out\""));
        Check.that("nor is a primitive", !page.contains("href=\"/symbols/boolean\""));
        Check.that("a member's own documentation is shown with it", page.contains("what was written"));
        Check.that("and its tags", page.contains("@param"));

        Check.that("fields are their own section", page.contains("Fields"));
        Check.that("and methods theirs", page.contains("Constructors and methods"));
    }

    /// A failure is a resource, not a sentence — which is what makes it
    /// something a spec can assert about at all.
    private static void problems(Router routes) {
        var failed = markup(Views.results(routes, Found.nothing().asking("...").failed("the index said no")));
        Check.that("a failed search says it is a problem", failed.contains("itemtype=\"/Problem\""));
        Check.that("and carries what went wrong as a property rather than as prose",
                failed.contains("itemprop=\"message\""));

        var missing = markup(Views.symbol(routes, Symbol.nothing().asking("nothing.At.All")
                .failed("no symbol called nothing.At.All")));
        Check.that("so does a symbol nobody has", missing.contains("itemtype=\"/Problem\""));
        Check.that("which is not a symbol, whatever else it is", !missing.contains("itemtype=\"/Symbol\""));
        Check.that("and still names what was asked for", missing.contains("nothing.At.All"));
    }

    /// The search box, which drives the panel — the one piece of the design
    /// that a change would break silently.
    private static void form(Router routes) {
        var blank = markup(web.forms.Forms.html(Search.blank(routes)));
        Check.that("the search box asks for the field the update reads", blank.contains("name=\"q\""));

        var asked = markup(web.forms.Forms.html(Search.asking(routes, "json.Json")));
        Check.that("and holds what was typed, so a page re-render does not empty it",
                asked.contains("value=\"json.Json\""));

        var page = markup(Views.results(routes, Found.nothing()));
        Check.that("the panel the form drives is the one the results arrive in",
                page.contains("id=\"" + Views.RESULTS + "\""));
    }

    private static Json.Object description() {
        return Json.Object.of()
                .with("class", "json.JsonWriter")
                .with("kind", "class")
                .with("doc", "Writes JSON to a writer.")
                .with("modifiers", Json.Array.strings(List.of("public", "final")))
                .with("extends", "java.io.Writer")
                .with("implements", Json.Array.strings(List.of("java.lang.Comparable<json.Json>")))
                .with("permits", Json.Array.strings(List.of("json.JsonWriter.Pretty")))
                .with("nested", Json.Array.strings(List.of("json.JsonWriter.Frame")))
                .with("tags", Json.Array.of(List.of(tag("since", "", "1.0"))))
                .with("methods", Json.Array.of(List.of()))
                .with("fields", Json.Array.of(List.of()));
    }

    private static Json.Object overloaded() {
        return Json.Object.of()
                .with("class", "json.Json")
                .with("kind", "interface")
                .with("methods", Json.Array.of(List.of(
                        member("write", "java.lang.String write(java.io.Writer out)", "what was written",
                                List.of(tag("param", "out", "where it goes"))),
                        member("write", "java.lang.String write(java.io.Writer out, boolean pretty)", "", List.of()),
                        member("write", "java.lang.String write(java.io.Writer out, int indent)", "", List.of()))))
                .with("fields", Json.Array.of(List.of(
                        member("NEWLINE", "java.lang.String NEWLINE", "What separates two values.", List.of()))));
    }

    private static Json member(String name, String signature, String doc, List<Json> tags) {
        return Json.Object.of()
                .with("name", name)
                .with("signature", signature)
                .with("doc", doc)
                .with("tags", Json.Array.of(tags));
    }

    private static Json tag(String tag, String name, String text) {
        return Json.Object.of().with("tag", tag).with("name", name).with("text", text);
    }

    private static Json match(String symbol, String kind) {
        return Json.Object.of().with("symbol", symbol).with("kind", kind).with("doc", "");
    }

    private static String markup(Html html) {
        return html.markup();
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    /// A tree row navigates: it swaps the pane *and* moves the address bar.
    ///
    /// Those two have traded places twice. First the pane swap was broken by a
    /// duplicate id, so the link fell back to a whole-page visit that did move
    /// the URL; then the row component dropped the attribute that moves the
    /// URL, so the pane swapped in silence and a reader could not link to what
    /// they were looking at. Both halves are checked here because fixing one
    /// has twice broken the other.
    private static void navigates() {
        var tree = Views.tree(Routes.of(), List.of(
                new symbols.Catalog.Root("project", "This project", List.of("json")))).markup();
        Check.that("a tree row targets the content pane",
                tree.contains("data-turbo-frame=\"content\""));
        Check.that("and advances the address bar",
                tree.contains("data-turbo-action=\"advance\""));
        Check.that("and both are on the anchor, where Turbo reads them",
                tree.contains("<a class=\"ui-row\" data-turbo-action=\"advance\""));
    }
}
