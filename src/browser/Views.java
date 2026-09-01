package browser;

import static web.ui.Attributes.*;
import static web.ui.Tags.*;

import browser.Symbols.Found;
import browser.Symbols.Symbol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import json.Json;
import markdown.Links;
import markdown.Markdown;
import symbols.Index;
import symbols.Catalog;
import web.Contribution;
import web.Features;
import web.Router;
import web.forms.Forms;
import web.forms.Submission;
import web.ui.Attribute;
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

    /// The frame the content pane is. A link in the tree drives it, so a click
    /// in the sidebar costs the page rather than the page and the tree — and
    /// every page therefore has to *be* one, or a tree link would ask for a
    /// frame that is not in the answer and blank the pane.
    public static final String CONTENT = "content";

    /// Where the skip link lands. Deliberately not [#CONTENT]: the frame owns
    /// that id, and two elements sharing one is invalid HTML that fails
    /// silently and expensively — Turbo looks its target frame up by id, finds
    /// whichever element comes first, and a link that meant to swap a frame
    /// quietly reloads the page instead.
    public static final String MAIN = "main";

    /// The icon, by its logical name — the one place it is spelled, so the
    /// page's link and the conventional path cannot point at different files.
    public static final String ICON = "favicon.svg";

    /// A qualified type name, which is what can be linked: dotted, and ending
    /// in a segment that begins with a capital. `java.lang.String` is one and
    /// the parameter name after it is not.
    private static final Pattern QUALIFIED =
            Pattern.compile("[a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*\\.[A-Z][\\w$]*");

    private Views() {}

    /// The children of a page, as markup. A page is given nodes and the frame
    /// that holds them takes markup, and an attribute among them would be an
    /// attribute of the frame rather than of the page.
    private static Html[] htmls(Node[] content) {
        var found = new ArrayList<Html>();
        for (var node : content) {
            if (node instanceof Html child) found.add(child);
        }
        return found.toArray(new Html[0]);
    }

    private static Node[] prepend(Node first, Node[] rest) {
        var nodes = new ArrayList<Node>();
        nodes.add(first);
        nodes.addAll(List.of(rest));
        return nodes.toArray(new Node[0]);
    }

    /// The link that says where the icon is.
    ///
    /// A contribution rather than a line in [#page]: it is this application's
    /// own file, and a mechanism an application opts out of for its own files
    /// is half a mechanism. The URL is resolved when the page is written,
    /// because it carries the digest of the file.
    public static Contribution icon() {
        return (assets, routes, out) ->
                link(rel("icon"), type("image/svg+xml"), href(assets.url(ICON))).write(out);
    }

    /// A whole page.
    ///
    /// Nothing here names a file. The stylesheets, the icon, the import map and
    /// the element the cable listens through all arrive through
    /// [Features#head()] and [Features#body()], from the packages that ship
    /// them — so this cannot link a stylesheet that is not served, or serve one
    /// it forgot to link. What is left is the module that starts the
    /// application, which is this application's own and has to come after the
    /// import map it imports through.
    public static Html page(Features wiring, String heading, Submission search,
                            List<Catalog.Root> roots, Node... content) {
        var routes = wiring.router();
        return document(
                lang("en"),
                head(
                        meta(charset("utf-8")),
                        meta(name("viewport"), content("width=device-width, initial-scale=1")),
                        title(text(heading + " — tuul")),
                        Html.deferred(wiring.head()::write),
                        script(BOOT, type("module"))),
                body(
                        Html.deferred(wiring.body()::write),
                        a(classes("skip"), href("#" + MAIN), text("Skip to content")),
                        Html.element("div", classes("shell"), Stimulus.controller(Ui.CONTROLLER),
                                header(classes("bar"),
                                        Ui.group(Props.of("gap", "lg"),
                                                Ui.opener(Props.of("href", routes.path(Routes.TREE)),
                                                        text("Browse")),
                                                Ui.anchor(Props.of("href", routes.path(Routes.HOME)),
                                                        classes("brand"), text("tuul")),
                                                search(search))),
                                Html.element("div", classes("panes"),
                                        Ui.sidebar(Props.of("label", "What there is"), tree(routes, roots)),
                                        main(id(MAIN),
                                                framed(Html.fragment(htmls(content))))))));
    }

    /// What there is, as a tree: the three places symbols come from, each
    /// holding the packages or modules a reader can go into.
    ///
    /// It goes one level deep and no further. A tree that expanded a package
    /// into its types would be the whole index in a panel — `java.base` alone
    /// is fifty-eight packages — and the page a link leads to already lists
    /// what it holds. The tree is how a reader gets somewhere; the breadcrumb
    /// on the page is where they are. Keeping those apart is what lets a tree
    /// link swap only the content pane without the tree going stale about a
    /// position it never claimed to know.
    ///
    /// The project is open and the rest are closed, because a reader of a
    /// project came for the project, and ninety JDK modules unfolded is a wall.
    public static Html tree(Router routes, List<Catalog.Root> roots) {
        if (roots.isEmpty()) return Ui.prose(text("Indexing documentation…"));
        return Ui.stack(Props.of("gap", "none"),
                Html.each(roots, root -> Ui.disclosure(
                        root.name().equals(Index.PROJECT) ? Props.of("label", root.label()).on("open")
                                : Props.of("label", root.label()),
                        Html.each(root.contents(), name -> Ui.row(
                                Props.of("href", symbolPath(routes, name), "frame", CONTENT),
                                Turbo.advance(), Ui.mono(text(name)))))));
    }

    /// The same tree as a page of its own, for the opener to point at where
    /// there is no room for a pane and no JavaScript to open one.
    public static Html browsing(Router routes, List<Catalog.Root> roots) {
        return Ui.stack(Props.of("gap", "lg"),
                Ui.heading(Props.of("level", "1"), text("What there is")),
                tree(routes, roots));
    }

    /// The content pane on its own, for a request that asked for the frame
    /// rather than the page.
    public static Html pane(Html content) {
        return framed(content);
    }

    /// The content pane.
    ///
    /// It carries no `target`, and that is deliberate: `target="_top"` on a
    /// frame does not only redirect the links written inside it, it promotes
    /// *any* navigation of the frame to a page navigation — including the tree
    /// links that exist to swap it. A frame that cannot be swapped is not a
    /// frame.
    ///
    /// Which leaves the other half of the rule to the links: a link written
    /// inside the pane says `_top` for itself, because it is a navigation and
    /// not a pane swap. Saying it at each site rather than once on the frame is
    /// the price of a frame that can still be swapped, and it is the same thing
    /// the result links have always said.
    private static Html framed(Html inside) {
        return Turbo.frame(CONTENT, inside);
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
                Turbo.advance(),
                Ui.button(Props.of().on("submit"), text("Search")));
    }

    /// The results, always inside their frame: Turbo takes the frame out of
    /// whatever page it arrives in, so the same markup answers a search whether
    /// it was typed or asked for directly.
    public static Html results(Router routes, Found found) {
        return Turbo.frame(RESULTS, panel(routes, found));
    }

    /// The results as a whole page rather than as a panel.
    ///
    /// A page needs a first-level heading — it is how anything reading rather
    /// than looking finds out what it has arrived at, and this page's is the
    /// only one that would say nothing new to somebody who can see it, since
    /// the search box above it says the same. So it is said once, to the
    /// readers who need it.
    public static Html searching(Router routes, Found found) {
        return Ui.stack(Props.of("gap", "sm"),
                Ui.heading(Props.of("level", "1"), classes("ui-hidden"),
                        text(found.asked() ? "Results for " + found.query() : "Search the index")),
                results(routes, found));
    }

    private static Html panel(Router routes, Found found) {
        if (!found.problem().isEmpty()) return problem(found.problem());
        if (!found.asked()) return Ui.blank(text("Type to search the index."));
        if (found.matches().isEmpty()) return Ui.blank(text("Nothing matches " + found.query() + "."));
        return Ui.stack(Props.of("gap", "md"),
                found.every() ? Html.nothing() : Ui.prose(Props.of("tone", "muted"),
                        text("Nothing holds every word of " + found.query() + ". These hold some of them.")),
                Html.each(found.groups(), group -> group(routes, group)));
    }

    /// One group of results: the name its results share, then the results.
    ///
    /// The name links to its own page. It does not link when one of the
    /// results already is that page, so a list never repeats a place. A
    /// package that the search surfaced only through its members still
    /// reaches its own page, one click away.
    private static Html group(Router routes, Json group) {
        if (!(group instanceof Json.Object entry)) return Html.nothing();
        var prefix = entry.string("prefix", "");
        var matches = entry.list("matches");
        var listed = matches.stream()
                .anyMatch(match -> match instanceof Json.Object held && held.string("symbol", "").equals(prefix));
        var heading = prefix.isEmpty() ? Html.nothing() : Html.element("h2", classes("result-group"),
                listed || matches.size() < 2
                        ? Ui.mono(text(prefix))
                        : Ui.anchor(Props.of("href", symbolPath(routes, prefix), "frame", Turbo.TOP),
                                Ui.mono(text(prefix))));
        return Ui.stack(Props.of("gap", "sm"), heading,
                Ui.items(Props.of().on("divided"), Html.each(matches, match -> row(routes, match))));
    }

    /// One result, as the component that is one.
    ///
    /// Something in the list that is not a match is left out rather than drawn
    /// as an empty row: the matches arrive as JSON in a message, so a value of
    /// the wrong shape is possible and a row that names nothing is worse than
    /// no row.
    private static Html row(Router routes, Json match) {
        if (!(match instanceof Json.Object entry)) return Html.nothing();
        return ResultRow.of(routes, entry);
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
        return symbol(routes, Links.NONE, state);
    }

    /// The same page, with `links` given the cross-references in its prose.
    ///
    /// A doc comment writes one as `[Invoice#compareTo(Invoice)]`, which is
    /// javadoc's syntax and CommonMark's punctuation for a link definition
    /// nobody wrote — so without somebody to ask, a page of cross-references
    /// renders as a page of square brackets. Who to ask is not decided here:
    /// this hands the answer on to the comment and has no opinion about what a
    /// label means. See [markdown.Links].
    public static Html symbol(Router routes, Links links, Symbol state) {
        if (!state.problem().isEmpty()) return missing(routes, state);

        var description = state.description();
        var qualified = description.string("class", "");
        if (holder(description.string("kind", ""))) return holding(routes, links, description, qualified);
        return Ui.stack(Props.of("gap", "lg"), Microdata.scope(), Microdata.type("/Symbol"),
                where(routes, qualified),
                Ui.stack(Props.of("gap", "sm"),
                        Ui.group(Props.of("gap", "sm", "align", "baseline"),
                                Ui.heading(Props.of("level", "1"),
                                        Ui.mono(Microdata.of("name"), text(qualified))),
                                Ui.badge(Microdata.of("kind"), text(description.string("kind", "class"))),
                                modifiers(description)),
                        documentation(links, description.string("doc", ""))),
                relations(routes, description),
                tags(description),
                members(routes, links, "Constructors and methods", description, "methods"),
                members(routes, links, "Fields", description, "fields"));
    }

    /// A symbol nobody has. It is still a page of this browser — the trail, the
    /// name in the face every other page names a symbol in — and it offers
    /// somewhere to go, because a dead end that only says "no" makes the reader
    /// reach for the back button and lose their place.
    private static Html missing(Router routes, Symbol state) {
        var name = state.name();
        var dot = name.lastIndexOf('.');
        var onward = new ArrayList<Node>();
        onward.add(Ui.anchor(Props.of("href", routes.path(Routes.HOME), "frame", Turbo.TOP),
                text("Search from the beginning")));
        if (dot > 0) {
            onward.add(Ui.anchor(Props.of("href", symbolPath(routes, name.substring(0, dot)), "frame", Turbo.TOP),
                    text("Look in " + name.substring(0, dot))));
        }
        return Ui.stack(Props.of("gap", "lg"),
                where(routes, name),
                Ui.stack(Props.of("gap", "sm"),
                        Ui.heading(Props.of("level", "1"), Ui.mono(text(name))),
                        problem(state.problem())),
                Ui.group(Props.of("gap", "md"), onward.toArray(new Node[0])));
    }

    /// A package or a module is a container, not a declaration: it has no
    /// members, and what it holds is the whole of what a reader came for.
    private static boolean holder(String kind) {
        return kind.equals("package") || kind.equals("module");
    }

    /// What a package or a module holds, as a column of short names.
    ///
    /// It was one run-on paragraph of comma-separated fully-qualified names —
    /// 134 of them for `java.util`, each repeating `java.util.`, wrapping over
    /// thirty lines. Nobody can find `HashMap` by reading prose. The eye runs
    /// down a column, so the names are a column; the prefix is the page you are
    /// already on, so it goes; and packages are listed apart from types because
    /// going deeper and stopping here are different questions.
    private static Html holding(Router routes, Links links, Json.Object description, String qualified) {
        var packages = new ArrayList<String>();
        var types = new ArrayList<String>();
        for (var held : strings(description, "nested")) {
            (nested(held) ? types : packages).add(held);
        }
        return Ui.stack(Props.of("gap", "lg"), Microdata.scope(), Microdata.type("/Symbol"),
                where(routes, qualified),
                Ui.stack(Props.of("gap", "sm"),
                        Ui.group(Props.of("gap", "sm", "align", "baseline"),
                                Ui.heading(Props.of("level", "1"),
                                        Ui.mono(Microdata.of("name"), text(qualified))),
                                Ui.badge(Microdata.of("kind"), text(description.string("kind", "package")))),
                        documentation(links, description.string("doc", ""))),
                contents(routes, "Packages", qualified, packages),
                documents(routes, description),
                contents(routes, "Types", qualified, types));
    }

    private static Html documents(Router routes, Json.Object description) {
        var written = new ArrayList<Html>();
        for (var kind : symbols.Document.KINDS) {
            var documents = objects(description, "documents").stream()
                    .filter(document -> document.string("kind", "").equals(kind))
                    .toList();
            if (documents.isEmpty()) continue;
            written.add(documentGroup(routes, description.string("class", ""), kind, documents));
        }
        if (written.isEmpty()) return Html.nothing();
        return Html.element("div", classes("document-grid"), Html.fragment(written));
    }

    private static Html documentGroup(Router routes, String packageName, String kind, List<Json.Object> documents) {
        return Html.element("section", classes("document-group"),
                Html.element("h2", classes("document-kind"), text(section(kind))),
                Html.element("div", classes("document-list"),
                        Html.each(documents, document -> documentEntry(routes, packageName, kind, document))));
    }

    private static Html documentEntry(Router routes, String packageName, String kind, Json.Object document) {
        var path = documentPath(routes, packageName, kind, document.string("slug", ""));
        var sections = objects(document, "sections");
        var outline = sections.isEmpty() ? Html.nothing() : Ui.disclosure(
                Props.of("label", sections.size() + (sections.size() == 1 ? " section" : " sections")),
                Ui.items(Html.each(sections, section -> Ui.item(
                        Ui.anchor(Props.of("href", path + "#" + section.string("anchor", ""), "frame", Turbo.TOP),
                                text(section.string("title", "")))))));
        return Html.element("div", classes("document-entry"),
                Ui.anchor(Props.of("href", path, "frame", Turbo.TOP), classes("document-title"),
                        text(document.string("title", ""))),
                outline);
    }

    /// Renders one document or a list for a kind that has no intro document.
    public static Html packageDocument(Router routes, Links links, Json.Object description) {
        var packageName = description.string("package", "");
        var kind = description.string("kind", "");
        var title = description.string("title", section(kind));
        var body = description.string("doc", "");
        var others = objects(description, "documents").stream()
                .filter(document -> !document.string("slug", "").equals(description.string("slug", "")))
                .toList();
        return Ui.stack(Props.of("gap", "lg"), Microdata.scope(), Microdata.type("/Document"),
                documentTrail(routes, packageName, kind, title),
                Ui.stack(Props.of("gap", "sm"),
                        Ui.group(Props.of("gap", "sm", "align", "baseline"),
                                Ui.heading(Props.of("level", "1"), Microdata.of("title"), text(title)),
                                Ui.badge(Microdata.of("kind"), text(kind))),
                        Html.element("meta", Microdata.of("package"), content(packageName)),
                        body.isBlank() ? Html.nothing() : anchoredDocumentation(links, symbols.Document.content(body))),
                documentLinks(routes, packageName, kind, others));
    }

    private static Html documentLinks(Router routes, String packageName, String kind, List<Json.Object> documents) {
        if (documents.isEmpty()) return Html.nothing();
        return Ui.stack(Props.of("gap", "sm"),
                Ui.heading(Props.of("level", "2"), text("More " + section(kind).toLowerCase())),
                Ui.items(Html.each(documents, document -> Ui.item(
                        Ui.anchor(Props.of("href", documentPath(routes, packageName, kind,
                                        document.string("slug", "")), "frame", Turbo.TOP),
                                text(document.string("title", "")))))));
    }

    private static Html documentTrail(Router routes, String packageName, String kind, String title) {
        return Ui.breadcrumbs(
                Ui.crumb(Props.of("href", routes.path(Routes.HOME), "frame", Turbo.TOP), text("tuul")),
                Ui.crumb(Props.of("href", symbolPath(routes, packageName), "frame", Turbo.TOP), text(packageName)),
                Ui.crumb(Props.of("href", documentPath(routes, packageName, kind, ""), "frame", Turbo.TOP), text(kind)),
                Ui.crumb(Props.of(), text(title)));
    }

    private static String section(String kind) {
        return switch (kind) {
            case symbols.Document.README -> "README";
            case "tutorial" -> "Tutorials";
            case "howto" -> "How-tos";
            case "reference" -> "Reference";
            case "guide" -> "Guides";
            default -> "Documents";
        };
    }

    private static String documentPath(Router routes, String packageName, String kind, String slug) {
        return slug.isEmpty()
                ? routes.path(Routes.DOCUMENT_KIND.with(Routes.NAME, packageName).with(Routes.KIND, kind))
                : routes.path(Routes.DOCUMENT.with(Routes.NAME, packageName).with(Routes.KIND, kind)
                        .with(Routes.SLUG, slug));
    }

    /// A name held by a package is a type when its last segment is capitalised,
    /// and a package when it is not. That is a convention rather than a rule,
    /// but it is the convention every Java package on disk follows, and the
    /// alternative is asking the index about each of a hundred names.
    private static boolean nested(String qualified) {
        var last = qualified.substring(qualified.lastIndexOf('.') + 1);
        return !last.isEmpty() && Character.isUpperCase(last.charAt(0));
    }

    private static Html contents(Router routes, String heading, String within, List<String> held) {
        if (held.isEmpty()) return Html.nothing();
        var prefix = within + ".";
        return Ui.stack(Props.of("gap", "sm"),
                Ui.heading(Props.of("level", "2"), text(heading)),
                Ui.items(Props.of().on("columns"),
                        Html.each(held, name -> Ui.item(
                                Ui.mono(Microdata.of("contains"),
                                        Ui.anchor(Props.of("href", symbolPath(routes, name), "frame", Turbo.TOP),
                                                text(name.startsWith(prefix) ? name.substring(prefix.length()) : name)))))));
    }

    /// The package a type is in, as a trail: the last crumb is the type and the
    /// ones before it are where it lives.
    ///
    /// The package crumb is a link, because a package is a page now. It was
    /// plain text back when it named nothing, and stayed plain after packages
    /// became symbols — so the one crumb a reader would actually click was the
    /// one that did nothing.
    private static Html where(Router routes, String qualified) {
        var dot = qualified.lastIndexOf('.');
        if (dot < 0) return Html.nothing();
        var owner = qualified.substring(0, dot);
        return Ui.breadcrumbs(
                Ui.crumb(Props.of("href", routes.path(Routes.HOME), "frame", Turbo.TOP), text("tuul")),
                Ui.crumb(Props.of("href", symbolPath(routes, owner), "frame", Turbo.TOP), text(owner)),
                Ui.crumb(Props.of(), text(qualified.substring(dot + 1))));
    }

    /// How a type was declared, beside what it is rather than under it. A bare
    /// `public final` on a line of its own reads as a sentence somebody forgot
    /// to finish; next to the kind it reads as what it is, which is two more
    /// facts of the same sort.
    private static Html modifiers(Json.Object description) {
        var modifiers = strings(description, "modifiers");
        if (modifiers.isEmpty()) return Html.nothing();
        return Html.each(modifiers, modifier -> Ui.badge(Props.of("tone", "muted"), text(modifier)));
    }

    /// A doc comment, rendered as the markdown it is.
    ///
    /// A `///` comment is markdown by language rule since JDK 23, and this
    /// repository writes them everywhere; the older kind arrives as prose with
    /// blank lines between paragraphs and its examples indented, which markdown
    /// reads the same way. So one renderer serves both, and the heuristic that
    /// used to guess where a code block began is gone — it guessed wrong on a
    /// fenced block, putting its first line in the prose and the rest in a box.
    ///
    /// It renders into the page's own writer rather than building a string,
    /// which is what keeps a long comment from being held twice.
    static Html documentation(String doc) {
        return documentation(Links.NONE, doc);
    }

    static Html documentation(Links links, String doc) {
        if (doc.isBlank()) return Html.nothing();
        return Html.element("div", classes("ui-doc"), Microdata.of("doc"),
                Html.deferred(out -> Markdown.render(doc, links, out)));
    }

    /// Renders long-form documentation with fragment targets on its headings.
    private static Html anchoredDocumentation(Links links, String doc) {
        if (doc.isBlank()) return Html.nothing();
        return Html.element("div", classes("ui-doc"), Microdata.of("doc"),
                Html.deferred(out -> Markdown.renderAnchored(doc, links, out)));
    }

    /// Code keeps its shape but not the indentation that marked it as code, so
    /// a four-space example does not sit four spaces in twice.
    private static String strip(String code) {
        var lines = code.strip().isEmpty() ? new String[0] : code.split("\n", -1);
        var common = Integer.MAX_VALUE;
        for (var line : lines) {
            if (line.isBlank()) continue;
            var spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
            common = Math.min(common, spaces);
        }
        if (common == Integer.MAX_VALUE || common == 0) return code.stripTrailing();
        var out = new StringBuilder();
        for (var line : lines) {
            out.append(line.length() >= common ? line.substring(common) : line).append('\n');
        }
        return out.toString().stripTrailing();
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
                Ui.anchor(Props.of("href", symbolPath(routes, base), "frame", Turbo.TOP), text(base)),
                text(rest));
    }

    private static Html tags(Json.Object description) {
        var tags = objects(description, "tags");
        if (tags.isEmpty()) return Html.nothing();
        return Ui.items(Html.each(tags, Member::tag));
    }

    /// The members, each with an id, so that a search result for one can link
    /// to the place it is written. Overloads share a name and an id has to be
    /// unique, so the second `of` is `of-2` and a link to `#of` lands on the
    /// first — which is what somebody following a result meant.
    private static Html members(Router routes, Links links, String heading, Json.Object description, String key) {
        var members = objects(description, key);
        if (members.isEmpty()) return Html.nothing();
        var taken = new HashMap<String, Integer>();
        var written = new ArrayList<Html>();
        for (var member : members) {
            var name = member.string("name", "");
            var seen = taken.merge(name, 1, Integer::sum);
            written.add(Member.of(routes, links, member, seen == 1 ? name : name + "-" + seen));
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

    private static String symbolPath(Router routes, String symbol) {
        return routes.path(Routes.SYMBOL.with(Routes.NAME, symbol));
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
            import Sidebar from "@tuul/ui-sidebar";
            import Search from "@tuul/browser-search";
            import ResultKind from "@tuul/result-kind";

            const stimulus = Application.start();
            stimulus.register("cable-stream", CableStream);
            stimulus.register("ui-sidebar", Sidebar);
            stimulus.register("search", Search);
            stimulus.register("result-kind", ResultKind);
            """;
}
