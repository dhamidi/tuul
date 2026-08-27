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
    private static Html search(Submission submission) {
        return Forms.html(submission,
                classes("search"),
                Stimulus.controller("search"),
                Stimulus.action(Stimulus.on("input", "search", "ask")),
                Turbo.targetFrame(RESULTS),
                button(type("submit"), text("Search")));
    }

    /// The results, always inside their frame: Turbo takes the frame out of
    /// whatever page it arrives in, so the same markup answers a search whether
    /// it was typed or asked for directly.
    public static Html results(Router routes, Found found) {
        if (!found.problem().isEmpty()) return Turbo.frame(RESULTS, p(classes("problem"), text(found.problem())));
        if (!found.asked()) {
            return Turbo.frame(RESULTS, p(classes("hint"), text("Type to search the index.")));
        }
        if (found.matches().isEmpty()) {
            return Turbo.frame(RESULTS, p(classes("hint"), text("Nothing matches " + found.query() + ".")));
        }
        return Turbo.frame(RESULTS,
                ol(classes("matches"), Html.each(found.matches(), match -> match(routes, match))));
    }

    private static Html match(Router routes, Json match) {
        if (!(match instanceof Json.Object entry)) return Html.nothing();
        var symbol = entry.string("symbol", "");
        return li(Microdata.scope(), Microdata.type("/Symbol"),
                a(href(symbolPath(routes, symbol)),
                        code(Microdata.of("name"), text(symbol))),
                span(classes("kind"), Microdata.of("kind"), text(kind(entry.string("kind", "")))),
                documentation(entry.string("doc", "")));
    }

    /// One symbol: what it is, what it says about itself, and what it declares.
    public static Html symbol(Router routes, Symbol state) {
        if (!state.problem().isEmpty()) return section(classes("problem"), h1(text(state.name())),
                p(text(state.problem())));

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
        if (types.isEmpty()) return Html.nothing();
        var linked = new ArrayList<Html>();
        for (var type : types) {
            if (!linked.isEmpty()) linked.add(text(", "));
            linked.add(reference(routes, type));
        }
        return p(classes("inherits"), span(classes("label"), text(label + " ")), Html.fragment(linked));
    }

    /// A type name as a link, when it is a name this browser could show. A
    /// generic argument is part of what is written but not part of what is
    /// linked, so `Comparable<String>` links on `Comparable` and reads whole.
    private static Html reference(Router routes, String type) {
        var base = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        var rest = type.substring(base.length());
        if (!base.contains(".")) return code(Microdata.of("supertype"), text(type));
        return code(Microdata.of("supertype"),
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

    private static Html members(Router routes, String heading, Json.Object description, String key) {
        var members = objects(description, key);
        if (members.isEmpty()) return Html.nothing();
        return section(classes("members"),
                h2(text(heading)),
                ul(Html.each(members, member -> member(routes, member))));
    }

    private static Html member(Router routes, Json.Object member) {
        return li(Microdata.scope(), Microdata.type("/Member"),
                code(classes("signature"), Microdata.of("signature"), text(member.string("signature", ""))),
                documentation(member.string("doc", "")),
                tags(member));
    }

    /// The index files a kind as the enum spells it. A page is read by a
    /// person, and `RECORD` is shouting.
    private static String kind(String kind) {
        return kind.toLowerCase(java.util.Locale.ROOT);
    }

    private static String symbolPath(Router routes, String symbol) {
        return routes.path(Routes.SYMBOL, Map.of("name", symbol));
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
