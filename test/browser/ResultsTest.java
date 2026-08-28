package browser;

import browser.ResultItemKind.Of;
import harness.Check;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import json.Json;
import web.dispatch.Router;
import web.ui.Attributes;
import web.ui.HtmlException;
import web.ui.Props;
import web.ui.Turbo;

/// The two components a search result is made of.
///
/// A result is the thing this application exists to offer, and it was assembled
/// by hand out of five other components until it became [ResultRow] and
/// [ResultItemKind]. What is checked here is what a reader depends on: which
/// letter stands for which kind, that no two kinds can be confused with each
/// other, that the chip is a control a keyboard can reach and a screen reader
/// can name, that pressing it cannot navigate, and that an attribute a caller
/// wrote survives — which is the failure this codebase has already shipped once.
public final class ResultsTest {

    private ResultsTest() {}

    public static void run(Router routes) {
        letters();
        distinct();
        spellings();
        chip();
        modifiers();
        refuses();
        row(routes);
        survives(routes);
    }

    /// Every kind a search result can carry, and the letter it is drawn as.
    ///
    /// The list is not a sample. `TypeInfo.Kind` has seven and the schema
    /// constrains a member to two, so these nine are all of them, and a tenth
    /// appearing without a letter would be a chip that silently says nothing.
    private static void letters() {
        Check.equal("a class is C", "C", Of.of("class").letter());
        Check.equal("an interface is I", "I", Of.of("interface").letter());
        Check.equal("a record is R", "R", Of.of("record").letter());
        Check.equal("an enum is E", "E", Of.of("enum").letter());
        Check.equal("an annotation is A", "A", Of.of("annotation").letter());
        Check.equal("a package is P", "P", Of.of("package").letter());
        Check.equal("a module is M", "M", Of.of("module").letter());
        Check.equal("a method is m", "m", Of.of("method").letter());
        Check.equal("a field is f", "f", Of.of("field").letter());

        Check.equal("and a kind from a newer index is a question mark rather than a blank",
                "?", Of.of("witchcraft").letter());
        Check.equal("said as a word a reader can read", "symbol", Of.of("witchcraft").word());
        Check.equal("a kind nobody gave is the same", Of.OTHER, Of.of(""));
    }

    /// The one clash there is, and the two things that settle it.
    ///
    /// `module` and `method` both begin with M. The case says which — uppercase
    /// is a symbol with a page of its own, lowercase one that lives on somebody
    /// else's — and the family says it a second time, so a reader who cannot
    /// tell the two colours apart still has the case, which is what WCAG 1.4.1
    /// is about.
    private static void distinct() {
        Check.that("module and method share a letter and not its case",
                Of.MODULE.letter().equalsIgnoreCase(Of.METHOD.letter())
                        && !Of.MODULE.letter().equals(Of.METHOD.letter()));
        Check.that("and are drawn in different colours as well",
                !Of.MODULE.family().equals(Of.METHOD.family()));

        Check.that("a symbol with a page of its own takes an uppercase letter",
                List.of(Of.CLASS, Of.INTERFACE, Of.RECORD, Of.ENUM, Of.ANNOTATION, Of.PACKAGE, Of.MODULE).stream()
                        .allMatch(of -> of.letter().equals(of.letter().toUpperCase(Locale.ROOT))));
        Check.that("and one that lives on somebody else's page a lowercase one",
                List.of(Of.METHOD, Of.FIELD).stream()
                        .allMatch(of -> of.letter().equals(of.letter().toLowerCase(Locale.ROOT))));

        var seen = new HashSet<String>();
        var clashing = new ArrayList<String>();
        for (var of : Of.values()) {
            if (!seen.add(of.letter() + " " + of.family())) clashing.add(of.word());
        }
        Check.equal("no two kinds are drawn the same way", List.of(), clashing);
    }

    /// The three spellings a kind arrives in.
    ///
    /// The index files a type's kind as the enum spells it — `CLASS` — because
    /// that is what `TypeInfo.Kind#name` gives, and a member's as the word the
    /// schema allows, `method`. A symbol page is described by `Docs.describe`,
    /// which uses `TypeInfo.Kind#keyword` and so spells an annotation
    /// `@interface`. All three reach the chip.
    private static void spellings() {
        Check.equal("the shouted spelling the index files a type under", Of.CLASS, Of.of("CLASS"));
        Check.equal("the word a person reads", Of.CLASS, Of.of("class"));
        Check.equal("the enum's name for an annotation", Of.ANNOTATION, Of.of("ANNOTATION"));
        Check.equal("and the way one is actually written in Java", Of.ANNOTATION, Of.of("@interface"));
        Check.equal("padding is not a kind of its own", Of.METHOD, Of.of("  method  "));

        var shouted = ResultItemKind.of("RECORD").markup();
        Check.that("so a shouted kind is drawn as the word a person reads",
                shouted.contains("value=\"record\"") && shouted.contains(">record<"));
        Check.that("and never as the enum spells it", !shouted.contains("RECORD"));
    }

    /// The chip as a control: what a keyboard reaches and what a screen reader
    /// is told.
    private static void chip() {
        var markup = ResultItemKind.of("class").markup();

        Check.that("the chip is a button, so Tab reaches it and Enter and Space work it",
                markup.contains("<button"));
        Check.that("and an explicit one: the default is submit, and a chip that submitted "
                        + "the search form would be a chip that navigated",
                markup.contains("type=\"button\""));
        Check.that("it says whether it is open, which is the state it has",
                markup.contains("aria-expanded=\"false\""));
        Check.that("and what opening it costs, which is a call to the controller",
                markup.contains("click-&gt;result-kind#toggle"));

        Check.that("the letter is not said, because the word beside it says the same thing",
                markup.contains("<span class=\"kind-mark\" aria-hidden=\"true\">C</span>"));
        Check.that("nor is the word that grows out of the square",
                markup.contains("<span class=\"kind-word\" aria-hidden=\"true\">class</span>"));
        Check.that("what is said is a span of its own, clipped rather than hidden, so the "
                        + "full word is announced whether the chip is open or shut",
                markup.contains("<span class=\"ui-hidden\">class</span>"));
        Check.that("and shown to a pointer as well", markup.contains("title=\"class\""));

        Check.that("the root is a data element, so a caller's itemprop reads the word "
                        + "rather than the letter and the word run together",
                markup.startsWith("<data ") && markup.contains("value=\"class\""));
        Check.that("it carries no id, since twenty-five results would carry twenty-five of it",
                !markup.contains("id="));
        Check.that("the family is a class name, because the colour lives in the stylesheet",
                markup.contains("kind kind--type"));
    }

    /// What a modifier does, and what it does when there is none.
    private static void modifiers() {
        var plain = ResultItemKind.of("class").markup();
        Check.that("a symbol declared with no modifiers is drawn as an ordinary chip",
                !plain.contains("kind--static") && !plain.contains("kind--private")
                        && !plain.contains("kind--abstract"));
        Check.that("and says only what it is", plain.contains("title=\"class\""));

        var absent = new ResultItemKind(Props.of("kind", "field"), new web.ui.Node[0]).markup();
        Check.that("an absent modifiers prop is the same as an empty one, which is the "
                        + "ordinary case rather than an error",
                absent.contains("title=\"field\"") && absent.contains(">f<"));

        var stat = ResultItemKind.of("method", "public static").markup();
        Check.that("static turns the square into a rhombus", stat.contains("kind--static"));
        Check.that("and is said, because a shape nothing names is a shape only somebody "
                        + "who already knows can read",
                stat.contains("title=\"public static method\""));
        Check.that("said in the accessible name too, not only in a tooltip",
                stat.contains("<span class=\"ui-hidden\">public static method</span>"));

        var abstracted = ResultItemKind.of("class", "public abstract").markup();
        Check.that("abstract breaks the outline", abstracted.contains("kind--abstract"));

        var hidden = ResultItemKind.of("field", "private static").markup();
        Check.that("private dulls it", hidden.contains("kind--private"));
        Check.that("and static still turns it, because two modifiers are two facts",
                hidden.contains("kind--static"));

        var undrawn = ResultItemKind.of("class", "public final").markup();
        Check.that("a modifier with no drawing of its own is said and not drawn",
                undrawn.contains("title=\"public final class\"") && !undrawn.contains("kind--final"));

        var noisy = ResultItemKind.of("method", "  PUBLIC   static  static ").markup();
        Check.that("modifiers are read the way the index writes them, however they arrive",
                noisy.contains("title=\"public static method\"") && noisy.contains("kind--static"));
    }

    /// A prop nobody declared is a mistake, said where it was written.
    private static void refuses() {
        Check.throwing("the chip takes a kind and its modifiers and nothing else",
                () -> new ResultItemKind(Props.of("kind", "class", "modifer", "static"), new web.ui.Node[0]));
        Check.throwing("and a row takes only how much of a comment to show",
                () -> new ResultRow(Routes.of(), Props.of("summry", "40"), Json.Object.of(), new web.ui.Node[0]));

        try {
            new ResultItemKind(Props.of("kind", "class", "modifer", "static"), new web.ui.Node[0]);
            Check.that("unreachable", false);
        } catch (HtmlException refused) {
            Check.that("and says what it does take: " + refused.getMessage(),
                    refused.getMessage().contains("no such prop: modifer")
                            && refused.getMessage().contains("modifiers"));
        }
    }

    /// The row: where it goes, what it says, and how much of it.
    private static void row(Router routes) {
        var type = ResultRow.of(routes, match("json.Json", "INTERFACE", "public", "")).markup();
        Check.that("a result is a symbol a spec can ask about", type.contains("itemtype=\"/Symbol\""));
        Check.that("naming it", type.contains("itemprop=\"name\""));
        Check.that("saying what kind of thing it is", type.contains("itemprop=\"kind\""));
        Check.that("and linking to its page", type.contains("href=\"/symbols/json.Json\""));
        Check.that("out of the panel it was written in, since a page is not a panel",
                type.contains("data-turbo-frame=\"" + Turbo.TOP + "\""));
        Check.that("the chip is beside the link and never inside it, because a button "
                        + "inside an anchor is invalid markup that navigates",
                type.indexOf("</data>") < type.indexOf("<a "));

        var member = ResultRow.of(routes, match("json.Json#write", "method", "public static", "")).markup();
        Check.that("a member links to its type's page and the place on it",
                member.contains("href=\"/symbols/json.Json#write\""));
        Check.that("and is drawn as a member rather than as a module",
                member.contains("kind--member") && member.contains(">m<"));

        var said = ResultRow.of(routes, match("json.Json", "INTERFACE", "",
                "The first sentence. The second one, which the page it links to has.")).markup();
        Check.that("a row shows the first sentence of a comment", said.contains("The first sentence."));
        Check.that("and not the rest, because a row four times its neighbour is not a row in a list",
                !said.contains("The second one"));

        var quiet = ResultRow.of(routes, match("json.Json", "INTERFACE", "", "")).markup();
        Check.that("a symbol with nothing to say says nothing", !quiet.contains("itemprop=\"doc\""));

        var unkinded = ResultRow.of(routes, match("json.Json", "", "", "")).markup();
        Check.that("a result the index gave no kind for offers no chip rather than an empty one",
                !unkinded.contains("class=\"kind"));
        Check.that("and is still a symbol with a link", unkinded.contains("href=\"/symbols/json.Json\""));

        Check.equal("a comment is cut on a word boundary and marked as cut",
                "one two three…", ResultRow.shorten("one two three four", 14));
        Check.equal("a comment that fits is left alone",
                "one two three four", ResultRow.shorten("one two three four", 40));
    }

    /// An attribute a caller wrote reaches the element it was addressed to.
    ///
    /// This is the failure this codebase has already shipped. `Ui.Row`,
    /// `Ui.Disclosure` and `Ui.Sidebar` built their children by keeping only the
    /// markup among them, which threw away every attribute a caller passed —
    /// and a tree link stopped advancing the address bar with nothing anywhere
    /// saying so. Both new components take children, so both can make it again.
    private static void survives(Router routes) {
        var row = ResultRow.of(routes, match("json.Json", "INTERFACE", "", ""),
                Turbo.advance(), Attributes.data("test", "kept")).markup();
        Check.that("an attribute a caller wrote is on the row",
                row.contains("data-turbo-action=\"advance\"") && row.contains("data-test=\"kept\""));
        Check.that("on the row itself and not on something inside it",
                row.startsWith("<li ") && row.indexOf("data-test=\"kept\"") < row.indexOf("<div"));

        var chip = ResultItemKind.of("class", "", Attributes.data("test", "kept"),
                Attributes.classes("extra")).markup();
        Check.that("and one written on a chip is on the chip", chip.contains("data-test=\"kept\""));
        Check.that("with a caller's class folded into the component's own rather than "
                        + "written twice, which is invalid and silently loses one",
                chip.contains("class=\"kind kind--type extra\"") && count(chip, "class=\"kind ") == 1);
    }

    private static Json.Object match(String symbol, String kind, String modifiers, String doc) {
        return Json.Object.of()
                .with("symbol", symbol)
                .with("kind", kind)
                .with("modifiers", modifiers)
                .with("doc", doc);
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
