package browser;

import static web.ui.Attributes.*;
import static web.ui.Tags.*;

import browser.Symbols.Found;
import browser.Symbols.Symbol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import json.Json;
import web.assets.Assets;
import web.assets.Importmap;
import web.cable.Cable;
import web.dispatch.Router;
import web.forms.Forms;
import web.forms.Submission;
import web.ui.Html;
import web.ui.Microdata;
import web.ui.Node;
import web.ui.Stimulus;
import web.ui.Turbo;

/// The pages, as components.
///
/// Every one of them renders from the same JSON description `tuul docs --json`
/// prints, so a page cannot say something about a type that the command would
/// not. Every link is a route asked for by name, so no page here contains a URL.
public final class Views {

    /// The frame search results land in. Naming it once matters more than it
    /// looks: the form targets it, the response is wrapped in it, and a
    /// disagreement between the two is a page that silently reloads instead of
    /// updating.
    public static final String RESULTS = "results";

    /// A qualified type name, which is what can be linked: dotted, and ending
    /// in a segment that begins with a capital. `java.lang.String` is one and
    /// the parameter name after it is not.
    private static final java.util.regex.Pattern QUALIFIED =
            java.util.regex.Pattern.compile("[a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*\\.[A-Z][\\w$]*");

    /// The icon, by its logical name — the one place it is spelled, so the
    /// page's link and the conventional path cannot point at different files.
    public static final String ICON = "favicon.svg";

    private Views() {}

    /// A whole page. The import map and the module that starts Turbo and
    /// Stimulus are here rather than in each page, since a page that forgot
    /// them would be a page that quietly stopped being interactive.
    public static Html page(Router routes, Assets assets, Importmap modules, String title, Submission search,
                            Node... content) {
        return document(
                lang("en"),
                        head(
                                meta(charset("utf-8")),
                                meta(name("viewport"), content("width=device-width, initial-scale=1")),
                                title(text(title + " — tuul")),
                                link(rel("icon"), type("image/svg+xml"), href(assets.url(ICON))),
                                link(rel("stylesheet"), href(assets.url("browser.css"))),
                                Html.deferred(out -> modules.write(assets, out)),
                                script(BOOT, type("module"))),
                        body(
                                Cable.source(routes.path(Routes.UPDATES)),
                                header(classes("bar"),
                                        a(classes("brand"), href(routes.path(Routes.HOME)), text("tuul")),
                                        search(search)),
                                main(content)));
    }

    /// The search form, which lives in the bar on every page — one form, so
    /// that a search from a symbol page is the same search as one from the
    /// front page, and nothing has to decide which of two boxes was meant.
    ///
    /// It targets the results frame and asks Stimulus to
    /// submit it as somebody types — the debounce is the controller's, because
    /// a keystroke is not a question worth asking the index.
    ///
    /// The search advances the history, so a search has a URL: it can be
    /// shared, reloaded, and undone with Back. A frame that updates without
    /// saying so leaves the address bar describing the page somebody saw before
    /// they typed anything.
    private static Html search(Submission submission) {
        return Forms.html(submission,
                classes("search"),
                Stimulus.controller("search"),
                Stimulus.action(Stimulus.on("input", "search", "ask")),
                Turbo.targetFrame(RESULTS),
                Turbo.advance(),
                button(type("submit"), text("Search")));
    }

    /// The results, always inside their frame: Turbo takes the frame out of
    /// whatever page it arrives in, so the same markup answers a search whether
    /// it was typed or asked for directly.
    public static Html results(Router routes, Found found) {
        if (!found.problem().isEmpty()) return Turbo.frame(RESULTS, problem(found.problem()));
        if (!found.asked()) {
            return Turbo.frame(RESULTS, p(classes("hint"), text("Type to search the index.")));
        }
        if (found.matches().isEmpty()) {
            return Turbo.frame(RESULTS, p(classes("hint"), text("Nothing matches " + found.query() + ".")));
        }
        return Turbo.frame(RESULTS,
                ol(classes("matches"), Html.each(found.matches(), match -> match(routes, match))));
    }

    /// One result. The link leaves the frame it is in: a result is a
    /// navigation, and a link inside a Turbo Frame drives that frame by
    /// default — which would ask a symbol page for a frame it does not have and
    /// replace the results with `Content missing`.
    private static Html match(Router routes, Json match) {
        if (!(match instanceof Json.Object entry)) return Html.nothing();
        var symbol = entry.string("symbol", "");
        return li(Microdata.scope(), Microdata.type("/Symbol"),
                a(href(symbolHref(routes, symbol)), Turbo.targetFrame(Turbo.TOP),
                        code(Microdata.of("name"), text(symbol))),
                span(classes("kind"), Microdata.of("kind"), text(kind(entry.string("kind", "")))),
                documentation(entry.string("doc", "")));
    }

    /// A failure the reader can see, described as a resource rather than left
    /// as prose.
    ///
    /// A test cannot honestly assert on a sentence — that is a raw string
    /// assertion, which is what web.hyperspec exists to refuse — so a page that
    /// says what went wrong only in prose is a page whose failures no spec can
    /// catch. Saying it is a problem makes `expect no item problem` mean
    /// something, and it meant nothing while this was a bare paragraph.
    private static Html problem(String message) {
        return p(classes("problem"), Microdata.scope(), Microdata.type("/Problem"),
                span(Microdata.of("message"), text(message)));
    }

    /// One symbol: what it is, what it says about itself, and what it declares.
    public static Html symbol(Router routes, Symbol state) {
        if (!state.problem().isEmpty()) return section(h1(text(state.name())), problem(state.problem()));

        var description = state.description();
        var content = new ArrayList<Node>();
        content.add(Microdata.scope());
        content.add(Microdata.type("/Symbol"));
        content.add(h1(
                span(classes("kind"), Microdata.of("kind"), text(description.string("kind", "class"))),
                text(" "),
                code(Microdata.of("name"), text(description.string("class", "")))));
        content.add(modifiers(description));
        content.add(documentation(description.string("doc", "")));
        content.add(inherits(routes, "extends", one(description, "extends")));
        content.add(inherits(routes, "implements", strings(description, "implements")));
        content.add(relates(routes, "permits", "case", strings(description, "permits")));
        content.add(relates(routes, "declares", "declares", strings(description, "nested")));
        content.add(tags(description));
        content.add(members(routes, "Constructors and methods", description, "methods"));
        content.add(members(routes, "Fields", description, "fields"));
        return article(content.toArray(new Node[0]));
    }

    private static Html modifiers(Json.Object description) {
        var modifiers = strings(description, "modifiers");
        if (modifiers.isEmpty()) return Html.nothing();
        return p(classes("modifiers"), text(String.join(" ", modifiers)));
    }

    private static Html documentation(String doc) {
        return doc.isBlank() ? Html.nothing() : p(classes("doc"), Microdata.of("doc"), text(doc));
    }

    /// `extends` and `implements`, with every named type a link — following a
    /// type to its supertype is the whole reason somebody opens a page like
    /// this.
    private static Html inherits(Router routes, String label, List<String> types) {
        return relates(routes, label, "supertype", types);
    }

    /// The types a type names, each a link and each said to be what it is: a
    /// supertype, a case of a sealed type, a type declared inside this one. A
    /// spec asking for a case should not have to accept a supertype.
    private static Html relates(Router routes, String label, String property, List<String> types) {
        if (types.isEmpty()) return Html.nothing();
        var linked = new ArrayList<Html>();
        for (var type : types) {
            if (!linked.isEmpty()) linked.add(text(", "));
            linked.add(reference(routes, type, property));
        }
        return p(classes("inherits"), span(classes("label"), text(label + " ")), Html.fragment(linked));
    }

    /// A type name as a link, when it is a name this browser could show. A
    /// generic argument is part of what is written but not part of what is
    /// linked, so `Comparable<String>` links on `Comparable` and reads whole.
    private static Html reference(Router routes, String type, String property) {
        var base = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        var rest = type.substring(base.length());
        if (!base.contains(".")) return code(Microdata.of(property), text(type));
        return code(Microdata.of(property),
                a(href(symbolPath(routes, base)), text(base)),
                text(rest));
    }

    private static Html tags(Json.Object description) {
        var tags = objects(description, "tags");
        if (tags.isEmpty()) return Html.nothing();
        return ul(classes("tags"), Html.each(tags, tag -> li(
                span(classes("tag"), text("@" + tag.string("tag", ""))),
                text(" " + (tag.string("name", "") + " " + tag.string("text", "")).strip()))));
    }

    /// The members, each with an id, so that a search result for one can link
    /// to the place it is written. Overloads share a name and an id has to be
    /// unique, so the second `of` is `of-2` and a link to `#of` lands on the
    /// first — which is what somebody following a result meant.
    private static Html members(Router routes, String heading, Json.Object description, String key) {
        var members = objects(description, key);
        if (members.isEmpty()) return Html.nothing();
        var taken = new java.util.HashMap<String, Integer>();
        var written = new ArrayList<Html>();
        for (var member : members) {
            var name = member.string("name", "");
            var seen = taken.merge(name, 1, Integer::sum);
            written.add(member(routes, member, seen == 1 ? anchor(name) : anchor(name) + "-" + seen));
        }
        return section(classes("members"), h2(text(heading)), ul(Html.fragment(written)));
    }

    private static Html member(Router routes, Json.Object member, String anchor) {
        return li(id(anchor), Microdata.scope(), Microdata.type("/Member"),
                code(classes("signature"), Microdata.of("signature"),
                        signature(routes, member.string("signature", ""))),
                documentation(member.string("doc", "")),
                tags(member));
    }

    /// A signature with every type in it a link.
    ///
    /// Following a return type to its own page is how somebody reads an API,
    /// and it is the difference between a page that documents a type and one
    /// that lets you explore from it. A qualified name is one this browser can
    /// show; a parameter name has no dots and stays as it is written.
    private static Html signature(Router routes, String signature) {
        var parts = new ArrayList<Html>();
        var names = QUALIFIED.matcher(signature);
        var written = 0;
        while (names.find()) {
            if (names.start() > written) parts.add(text(signature.substring(written, names.start())));
            parts.add(a(href(symbolPath(routes, names.group())), text(names.group())));
            written = names.end();
        }
        if (written < signature.length()) parts.add(text(signature.substring(written)));
        return Html.fragment(parts);
    }

    /// The index files a kind as the enum spells it. A page is read by a
    /// person, and `RECORD` is shouting.
    private static String kind(String kind) {
        return kind.toLowerCase(java.util.Locale.ROOT);
    }

    private static String symbolPath(Router routes, String symbol) {
        return routes.path(Routes.SYMBOL, Map.of("name", symbol));
    }

    /// Where a search result goes. A member is not a page — it is a place on
    /// its type's page — so `json.Json#of` is the `json.Json` page and the
    /// anchor `of`, which is the only URL that exists for it.
    private static String symbolHref(Router routes, String symbol) {
        var member = symbol.indexOf('#');
        if (member < 0) return symbolPath(routes, symbol);
        return symbolPath(routes, symbol.substring(0, member)) + "#" + anchor(symbol.substring(member + 1));
    }

    /// The id a member is given on its type's page. Overloads share a name, so
    /// the later ones are numbered — an id has to be unique, and the plain name
    /// lands on the first, which is what a link to `#of` should do.
    private static String anchor(String member) {
        return member;
    }

    private static List<String> one(Json.Object description, String key) {
        var value = description.string(key, "");
        return value.isBlank() ? List.of() : List.of(value);
    }

    private static List<String> strings(Json.Object description, String key) {
        var values = new ArrayList<String>();
        for (var value : description.list(key)) {
            if (value instanceof Json.Str(var text)) values.add(text);
        }
        return List.copyOf(values);
    }

    private static List<Json.Object> objects(Json.Object description, String key) {
        var values = new ArrayList<Json.Object>();
        for (var value : description.list(key)) {
            if (value instanceof Json.Object entry) values.add(entry);
        }
        return List.copyOf(values);
    }

    /// Starting Turbo and registering the controllers. Written here rather than
    /// vendored because it is this application's wiring, and it is short enough
    /// to read in one go.
    private static final String BOOT = """
            import "@hotwired/turbo";
            import { Application } from "@hotwired/stimulus";
            import CableStream from "@tuul/cable-stream";
            import Search from "@tuul/browser-search";

            const stimulus = Application.start();
            stimulus.register("cable-stream", CableStream);
            stimulus.register("search", Search);
            """;
}
