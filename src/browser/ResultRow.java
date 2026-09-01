package browser;

import static web.ui.Attributes.classes;
import static web.ui.Tags.text;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import json.Json;
import web.Router;
import web.ui.Component;
import web.ui.Html;
import web.ui.Microdata;
import web.ui.Node;
import web.ui.Props;
import web.ui.Turbo;
import web.ui.Ui;

/// One search result: what it is, what it is called, and the first thing it
/// says about itself.
///
/// This was three components and a paragraph assembled by hand in [Views] — an
/// item holding a group holding an anchor holding a mono, a badge beside it, and
/// a prose under it. Assembled by hand it could not be named, could not be
/// tested as a thing, and could not be given a chip without every caller
/// learning how a result is built. It is a component here for the reason
/// [Member] is one: a result is about symbols, not about cards and stacks, so it
/// belongs to the application rather than to [web.ui].
///
/// It takes the router, because where a result goes is a route and only the
/// route table knows the URL, and the match itself as JSON, because that is the
/// shape the index answers in and the shape a message carries.
///
/// Attributes a caller writes among `content` land on the row itself. That is
/// not a detail: [Ui.Row] and [Ui.Disclosure] once filtered a caller's children
/// down to markup and dropped every attribute, and a tree link stopped moving
/// the address bar with nothing anywhere saying so.
public record ResultRow(Router routes, Props props, Json.Object match, Node[] content) implements Component {

    /// How much of a comment a result shows before it stops being a row in a
    /// list and starts being a paragraph.
    public static final int SUMMARY = 160;

    public ResultRow {
        props.only("summary");
    }

    public static ResultRow of(Router routes, Json.Object match, Node... content) {
        return new ResultRow(routes, Props.NONE, match, content);
    }

    /// The row.
    ///
    /// The chip is a sibling of the link and never a child of it. A `<button>`
    /// inside an `<a>` is invalid HTML, and a browser handed one does something
    /// different in every engine — usually navigating, which is exactly what
    /// pressing the chip must not do.
    @Override
    public Html render() {
        var symbol = match.string("symbol", "");
        var kind = match.string("kind", "");

        var nodes = new ArrayList<Node>();
        nodes.add(classes("result"));
        nodes.add(Microdata.scope());
        nodes.add(Microdata.type("/Symbol"));
        nodes.addAll(List.of(content));
        nodes.add(Html.element("div", classes("result-head"),
                kind.isBlank() ? Html.nothing() : chip(kind),
                Ui.anchor(Props.of("href", href(symbol), "frame", Turbo.TOP),
                        Ui.mono(Microdata.of("name"), text(symbol)))));
        nodes.add(summary(match.string("doc", "")));
        return Ui.item(nodes.toArray(new Node[0]));
    }

    /// The kind, as a chip that says its own word when it is pressed. The
    /// microdata goes on it rather than beside it, so `expect item symbol` still
    /// reads a kind off a result — and it reads the word, not the letter,
    /// because the chip's root is a `<data>` with the word as its value.
    private Html chip(String kind) {
        return ResultItemKind.of(kind, match.string("modifiers", ""), Microdata.of("kind"));
    }

    /// Where a result goes. A member is not a page — it is a place on its type's
    /// page — so `json.Json#of` is the `json.Json` page and the anchor `of`,
    /// which is the only URL that exists for it.
    private String href(String symbol) {
        var member = symbol.indexOf('#');
        if (member < 0) return path(symbol);
        return path(symbol.substring(0, member)) + "#" + symbol.substring(member + 1);
    }

    private String path(String symbol) {
        if (symbols.Document.KINDS.contains(match.string("kind", ""))) {
            var parts = symbol.split("/", -1);
            if (parts.length == 2) return routes.path(Routes.DOCUMENT_KIND
                    .with(Routes.NAME, parts[0]).with(Routes.KIND, parts[1]));
            if (parts.length == 3) return routes.path(Routes.DOCUMENT
                    .with(Routes.NAME, parts[0]).with(Routes.KIND, parts[1]).with(Routes.SLUG, parts[2]));
        }
        return routes.path(Routes.SYMBOL.with(Routes.NAME, symbol));
    }

    /// The first sentence of what a symbol says about itself.
    ///
    /// A result list is scanned, and a row is scannable when it is the same
    /// shape as the row above it. One entry dumping a whole multi-paragraph
    /// class comment made rows run from 41 to 191 pixels, which defeats the scan
    /// the list exists for. The page it links to has the rest.
    private Html summary(String doc) {
        if (doc.isBlank()) return Html.nothing();
        var first = shorten(doc, props.number("summary", SUMMARY));
        if (first.isBlank()) return Html.nothing();
        return Ui.prose(Props.of("tone", "muted"), classes("result-doc"), Microdata.of("doc"), text(first));
    }

    /// The first sentence of the first paragraph, and no more than a line of it.
    ///
    /// The paragraph comes first because a comment's opening sentence is
    /// followed as often as not by an example, and a sentence boundary after
    /// `Router.of()` is not one a sentence breaker will find. The length cap is
    /// the backstop for a comment written as one long sentence — a row that is
    /// four times its neighbour is not a row in a list.
    static String shorten(String doc, int limit) {
        var paragraph = doc.strip().split("\n\\s*\n", 2)[0];
        var flat = paragraph.replace('\n', ' ').replaceAll("\\s+", " ").strip();
        var sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(flat);
        var end = sentences.next();
        var first = end == BreakIterator.DONE ? flat : flat.substring(0, end).strip();
        if (first.length() <= limit) return first;
        var cut = first.lastIndexOf(' ', limit);
        return first.substring(0, cut < 40 ? limit : cut).strip() + "…";
    }
}
