package web.hyperspec;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// A page as a client sees it: where it came from, what it answered, and what
/// it offers.
///
/// The offers are the point. A hypermedia client does not know what a page
/// says, it knows what it can do next — follow this link, submit that form —
/// and a test of such an application should be written in those terms. That is
/// why nothing here answers a question about the text of the page: a spec that
/// asserts on wording tests the wording, and the wording is the one thing that
/// changes for reasons that are nobody's business.
public record Page(
        URI uri,
        String method,
        int status,
        web.Headers headers,
        String body,
        Document.Element root) {

    /// Somewhere a client can go.
    public record Link(String label, String href, String rel) {}

    /// Something a client can send, and what it will send by default.
    public record Form(String name, String method, String action, List<Field> fields) {

        public Form {
            fields = List.copyOf(fields);
        }

        public Optional<Field> field(String name) {
            return fields.stream().filter(field -> field.name().equals(name)).findFirst();
        }

        /// What submitting this form sends, before anybody fills anything in.
        /// An unchecked box and an unselected radio are absent rather than
        /// empty, which is what a browser does and what a server expects.
        public Map<String, String> values() {
            var values = new LinkedHashMap<String, String>();
            for (var field : fields) {
                if (field.active() && !field.name().isEmpty()) values.put(field.name(), field.value());
            }
            return values;
        }
    }

    public record Field(String name, String type, String value, boolean active) {}

    /// A resource the page describes, in the page's own words.
    ///
    /// Microdata, rather than `data-` attributes: `itemscope`, `itemtype` and
    /// `itemprop` already mean resource, type and attribute, they are in the
    /// HTML specification, and other agents read them — so a page that exposes
    /// its resources this way is more useful to the world than one that only
    /// satisfies its own tests. `data-` is also spoken for in this framework:
    /// it is how Stimulus finds its controllers, and a test that read those
    /// would be reading the client's business rather than the resource's.
    public record Item(String type, String itemtype, Map<String, String> attributes) {

        public Optional<String> attribute(String name) {
            return Optional.ofNullable(attributes.get(name));
        }
    }

    public List<Link> links() {
        var links = new ArrayList<Link>();
        for (var anchor : root.find("a")) {
            anchor.attribute("href").ifPresent(href ->
                    links.add(new Link(label(anchor), href, anchor.attribute("rel", ""))));
        }
        return links;
    }

    /// A link by what it is called, or by its `rel`. Both are what the page
    /// says about the link rather than how it is styled, which is the line this
    /// package draws.
    public Optional<Link> link(String name) {
        var links = links();
        return links.stream().filter(link -> link.label().equals(name)).findFirst()
                .or(() -> links.stream().filter(link -> link.label().equalsIgnoreCase(name)).findFirst())
                .or(() -> links.stream().filter(link -> link.rel().equalsIgnoreCase(name)).findFirst());
    }

    public List<Form> forms() {
        return root.find("form").stream().map(Page::form).toList();
    }

    /// A form by its id or its name, or the only one there is. A page with one
    /// form is the common case and naming it every time would be ceremony.
    public Optional<Form> form(String name) {
        var forms = forms();
        if (name.isEmpty()) return forms.size() == 1 ? Optional.of(forms.getFirst()) : Optional.empty();
        return forms.stream().filter(form -> form.name().equals(name)).findFirst();
    }

    public List<Item> items() {
        var items = new ArrayList<Item>();
        for (var element : root.elements()) {
            if (element.has("itemscope")) items.add(item(element));
        }
        return items;
    }

    public List<Item> items(String type) {
        return items().stream().filter(item -> item.type().equalsIgnoreCase(type)).toList();
    }

    public Optional<Item> item(String type) {
        return items(type).stream().findFirst();
    }

    /// Where the page says to go next, if it said so.
    public Optional<String> location() {
        return headers.first("Location");
    }

    public boolean redirect() {
        return web.Status.redirect(status) && location().isPresent();
    }

    /// What a link is called: its text, or what it tells assistive technology
    /// when it has none — an icon is still an affordance and still has a name.
    private static String label(Document.Element anchor) {
        var text = anchor.text();
        if (!text.isEmpty()) return text;
        return anchor.attribute("aria-label").or(() -> anchor.attribute("title")).orElse("");
    }

    private static Form form(Document.Element element) {
        var fields = new ArrayList<Field>();
        for (var input : element.elements()) {
            field(input).ifPresent(fields::add);
        }
        return new Form(
                element.attribute("id").or(() -> element.attribute("name")).orElse(""),
                element.attribute("method", "get").toUpperCase(Locale.ROOT),
                element.attribute("action", ""),
                fields);
    }

    private static Optional<Field> field(Document.Element element) {
        return switch (element.name()) {
            case "input" -> Optional.of(input(element));
            case "textarea" -> Optional.of(new Field(
                    element.attribute("name", ""), "textarea", element.text(), true));
            case "select" -> Optional.of(select(element));
            case "button" -> Optional.of(new Field(
                    element.attribute("name", ""), "button", element.attribute("value", ""), false));
            default -> Optional.empty();
        };
    }

    private static Field input(Document.Element element) {
        var type = element.attribute("type", "text").toLowerCase(Locale.ROOT);
        var checkable = type.equals("checkbox") || type.equals("radio");
        var sends = !type.equals("submit") && !type.equals("button") && !type.equals("reset")
                && (!checkable || element.has("checked"));
        var value = element.attribute("value", checkable ? "on" : "");
        return new Field(element.attribute("name", ""), type, value, sends);
    }

    private static Field select(Document.Element element) {
        var options = element.find("option");
        var chosen = options.stream().filter(option -> option.has("selected")).findFirst()
                .or(() -> options.stream().findFirst());
        return new Field(
                element.attribute("name", ""),
                "select",
                chosen.map(option -> option.attribute("value").orElseGet(option::text)).orElse(""),
                chosen.isPresent());
    }

    private static Item item(Document.Element scope) {
        var attributes = new LinkedHashMap<String, String>();
        properties(scope, attributes, true);
        var itemtype = scope.attribute("itemtype", "");
        return new Item(shortName(itemtype), itemtype, attributes);
    }

    /// The properties of one item. A nested `itemscope` is an item of its own,
    /// so its properties belong to it and not to the one around it.
    private static void properties(Document.Element element, Map<String, String> into, boolean root) {
        if (!root && element.has("itemscope")) return;
        element.attribute("itemprop").ifPresent(name -> into.putIfAbsent(name, value(element)));
        for (var child : element.content()) {
            if (child instanceof Document.Element nested) properties(nested, into, false);
        }
    }

    /// A property's value is what the element is for: an anchor means its
    /// target, an image means its source, a `<time>` means its machine-readable
    /// date. Everything else means what it says.
    private static String value(Document.Element element) {
        return switch (element.name()) {
            case "a", "area", "link" -> element.attribute("href", "");
            case "img", "audio", "video", "source", "iframe", "embed" -> element.attribute("src", "");
            case "meta" -> element.attribute("content", "");
            case "time" -> element.attribute("datetime").orElseGet(element::text);
            case "data" -> element.attribute("value", "");
            case "input", "select", "textarea" -> element.attribute("value", "");
            default -> element.text();
        };
    }

    private static String shortName(String itemtype) {
        var slash = itemtype.lastIndexOf('/');
        return (slash < 0 ? itemtype : itemtype.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }
}
