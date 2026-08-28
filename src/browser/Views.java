package browser;

import static web.ui.Attributes.*;
import static web.ui.Tags.*;

import browser.Symbols.Found;
import browser.Symbols.Symbol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
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
import web.ui.Props;
import web.ui.Stimulus;
import web.ui.Turbo;
import web.ui.Ui;

/// The pages, written in components.
///
/// Every one renders from the same JSON description `tuul docs --json` prints,
/// so a page cannot say something about a type that the command would not.
/// Every link is a route asked for by name, so no page here contains a URL. And
/// every piece of it is a component from [Ui], so what a page *looks* like is
/// decided in one stylesheet rather than in the markup of whichever view
/// happened to need it.
public final class Views {

    /// The frame search results land in. Naming it once matters more than it
    /// looks: the form targets it, the response is wrapped in it, and a
    /// disagreement between the two is a page that silently reloads instead of
    /// updating.
    public static final String RESULTS = "results";

    /// The icon, by its logical name — the one place it is spelled, so the
    /// page's link and the conventional path cannot point at different files.
    public static final String ICON = "favicon.svg";

    /// A qualified type name, which is what can be linked: dotted, and ending
    /// in a segment that begins with a capital. `java.lang.String` is one and
    /// the parameter name after it is not.
    private static final Pattern QUALIFIED =
            Pattern.compile("[a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*\\.[A-Z][\\w$]*");

    private Views() {}

    /// A whole page. The import map and the module that starts Turbo and
    /// Stimulus are here rather than in each page, since a page that forgot
    /// them would be a page that quietly stopped being interactive.
    public static Html page(Router routes, Assets assets, Importmap modules, String heading, Submission search,
                            Node... content) {
        return document(
                lang("en"),
                head(
                        meta(charset("utf-8")),
                        meta(name("viewport"), content("width=device-width, initial-scale=1")),
                        title(text(heading + " — tuul")),
                        link(rel("icon"), type("image/svg+xml"), href(assets.url(ICON))),
                        link(rel("stylesheet"), href(assets.url("ui.css"))),
                        link(rel("stylesheet"), href(assets.url("browser.css"))),
                        Html.deferred(out -> modules.write(assets, out)),
                        script(BOOT, type("module"))),
                body(
                        Cable.source(routes.path(Routes.UPDATES)),
                        header(classes("bar"),
                                Ui.group(Props.of("gap", "lg"),
                                        Ui.anchor(Props.of("href", routes.path(Routes.HOME)), classes("brand"),
                                                text("tuul")),
                                        search(search))),
                        main(content)));
    }

    /// The search form, which lives in the bar on every page — one form, so
    /// that a search from a symbol page is the same search as one from the
    /// front page, and nothing has to decide which of two boxes was meant.
    ///
    /// It targets the results frame and asks Stimulus to submit it as somebody
    /// types — the debounce is the controller's, because a keystroke is not a
    /// question worth asking the index.
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
                Turbo.advance());
    }

    /// The results, always inside their frame: Turbo takes the frame out of
    /// whatever page it arrives in, so the same markup answers a search whether
    /// it was typed or asked for directly.
    public static Html results(Router routes, Found found) {
        return Turbo.frame(RESULTS, panel(routes, found));
    }

    private static Html panel(Router routes, Found found) {
        if (!found.problem().isEmpty()) return problem(found.problem());
        if (!found.asked()) return Ui.blank(text("Type to search the index."));
        if (found.matches().isEmpty()) return Ui.blank(text("Nothing matches " + found.query() + "."));
        return Ui.items(Props.of().on("divided"),
                Html.each(found.matches(), match -> match(routes, match)));
    }

    /// One result. The link leaves the frame it is in: a result is a
    /// navigation, and a link inside a Turbo Frame drives that frame by
    /// default — which would ask a symbol page for a frame it does not have and
    /// replace the results with `Content missing`.
    private static Html match(Router routes, Json match) {
        if (!(match instanceof Json.Object entry)) return Html.nothing();
        var symbol = entry.string("symbol", "");
        return Ui.item(Microdata.scope(), Microdata.type("/Symbol"),
                Ui.group(Props.of("gap", "sm", "align", "baseline"),
                        Ui.anchor(Props.of("href", symbolHref(routes, symbol), "frame", Turbo.TOP),
                                Ui.mono(Microdata.of("name"), text(symbol))),
                        Ui.badge(Props.of("tone", "muted"), Microdata.of("kind"),
                                text(kind(entry.string("kind", ""))))),
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
        return Ui.notice(Props.of("tone", "error"), Microdata.scope(), Microdata.type("/Problem"),
                Ui.prose(Microdata.of("message"), text(message)));
    }

    /// One symbol: where it sits, what it is, what it says about itself, and
    /// what it declares.
    public static Html symbol(Router routes, Symbol state) {
        if (!state.problem().isEmpty()) {
            return section(Ui.stack(Ui.heading(Props.of("level", "1"), text(state.name())),
                    problem(state.problem())));
        }

        var description = state.description();
        var qualified = description.string("class", "");
        return Ui.stack(Props.of("gap", "lg"), Microdata.scope(), Microdata.type("/Symbol"),
                where(routes, qualified),
                Ui.stack(Props.of("gap", "sm"),
                        Ui.group(Props.of("gap", "sm", "align", "baseline"),
                                Ui.heading(Props.of("level", "1"),
                                        Ui.mono(Microdata.of("name"), text(qualified))),
                                Ui.badge(Microdata.of("kind"), text(description.string("kind", "class")))),
                        modifiers(description),
                        documentation(description.string("doc", ""))),
                relations(routes, description),
                tags(description),
                members(routes, "Constructors and methods", description, "methods"),
                members(routes, "Fields", description, "fields"));
    }

    /// The package a type is in, as a trail. It is the only hierarchy this page
    /// can show today — a package has no page of its own yet — so the last
    /// crumb is the type and the ones before it are where it lives.
    private static Html where(Router routes, String qualified) {
        var dot = qualified.lastIndexOf('.');
        if (dot < 0) return Html.nothing();
        return Ui.breadcrumbs(
                Ui.crumb(Props.of("href", routes.path(Routes.HOME)), text("tuul")),
                Ui.crumb(Props.of(), text(qualified.substring(0, dot))),
                Ui.crumb(Props.of(), text(qualified.substring(dot + 1))));
    }

    private static Html modifiers(Json.Object description) {
        var modifiers = strings(description, "modifiers");
        if (modifiers.isEmpty()) return Html.nothing();
        return Ui.prose(Props.of("tone", "muted"), text(String.join(" ", modifiers)));
    }

    private static Html documentation(String doc) {
        if (doc.isBlank()) return Html.nothing();
        return Ui.prose(Props.of("tone", "muted").on("wrap"), Microdata.of("doc"), text(doc));
    }

    /// What a type extends, implements, permits and declares — one list of
    /// labelled values, because they are the same shape and a reader reads them
    /// together.
    private static Html relations(Router routes, Json.Object description) {
        var facts = new ArrayList<Node>();
        fact(routes, facts, "extends", "supertype", one(description, "extends"));
        fact(routes, facts, "implements", "supertype", strings(description, "implements"));
        fact(routes, facts, "permits", "case", strings(description, "permits"));
        fact(routes, facts, "declares", "declares", strings(description, "nested"));
        if (facts.isEmpty()) return Html.nothing();
        return Ui.facts(facts.toArray(new Node[0]));
    }

    /// One relation, with every named type a link and each said to be what it
    /// is: a supertype, a case of a sealed type, a type declared inside this
    /// one. A spec asking for a case should not have to accept a supertype.
    private static void fact(Router routes, List<Node> facts, String label, String property, List<String> types) {
        if (types.isEmpty()) return;
        var linked = new ArrayList<Html>();
        for (var type : types) {
            if (!linked.isEmpty()) linked.add(text(", "));
            linked.add(reference(routes, type, property));
        }
        facts.add(Ui.fact(Props.of("label", label), Html.fragment(linked)));
    }

    /// A type name as a link, when it is a name this browser could show. A
    /// generic argument is part of what is written but not part of what is
    /// linked, so `Comparable<String>` links on `Comparable` and reads whole.
    private static Html reference(Router routes, String type, String property) {
        var base = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        var rest = type.substring(base.length());
        if (!base.contains(".")) return Ui.mono(Microdata.of(property), text(type));
        return Ui.mono(Microdata.of(property),
                Ui.anchor(Props.of("href", symbolPath(routes, base)), text(base)),
                text(rest));
    }

    private static Html tags(Json.Object description) {
        var tags = objects(description, "tags");
        if (tags.isEmpty()) return Html.nothing();
        return Ui.items(Html.each(tags, tag -> Ui.item(
                Ui.group(Props.of("gap", "sm", "align", "baseline"),
                        Ui.badge(Props.of("tone", "plain"), text("@" + tag.string("tag", ""))),
                        Ui.prose(Props.of("tone", "muted"),
                                text((tag.string("name", "") + " " + tag.string("text", "")).strip()))))));
    }

    /// The members, each with an id, so that a search result for one can link
    /// to the place it is written. Overloads share a name and an id has to be
    /// unique, so the second `of` is `of-2` and a link to `#of` lands on the
    /// first — which is what somebody following a result meant.
    private static Html members(Router routes, String heading, Json.Object description, String key) {
        var members = objects(description, key);
        if (members.isEmpty()) return Html.nothing();
        var taken = new HashMap<String, Integer>();
        var written = new ArrayList<Html>();
        for (var member : members) {
            var name = member.string("name", "");
            var seen = taken.merge(name, 1, Integer::sum);
            written.add(Member.of(routes, member, seen == 1 ? name : name + "-" + seen));
        }
        return Ui.stack(Props.of("gap", "sm"),
                Ui.heading(Props.of("level", "2"), text(heading)),
                Ui.items(Props.of().on("divided"), Html.fragment(written)));
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
            parts.add(Ui.anchor(Props.of("href", symbolPath(routes, names.group())), text(names.group())));
            written = names.end();
        }
        if (written < signature.length()) parts.add(text(signature.substring(written)));
        return Html.fragment(parts);
    }

    /// The index files a kind as the enum spells it. A page is read by a
    /// person, and `RECORD` is shouting.
    private static String kind(String kind) {
        return kind.toLowerCase(Locale.ROOT);
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
        return symbolPath(routes, symbol.substring(0, member)) + "#" + symbol.substring(member + 1);
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
