package web.hyperspec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// A page, as far as a hypermedia client needs to see it: elements, their
/// attributes, and the text between them.
///
/// This is not a DOM and does not want to be. It exists so that [Page] can
/// answer what a page *offers* — its links, its forms, the resources it
/// describes — and every method here is in service of that.
///
/// What it does not attempt, so that nobody is surprised later: no implied end
/// tags (`<li>` without `</li>` nests the way it is written, not the way a
/// browser would guess), no error recovery beyond ignoring an end tag that
/// closes nothing, no namespaces, and `<script>` and `<style>` are skipped
/// whole because their content is not markup and must not be read as any. That
/// is enough for HTML a program wrote — which, in a framework that renders with
/// `web.ui`, is all of it.
public sealed interface Document {

    record Text(String text) implements Document {}

    record Element(String name, Map<String, String> attributes, List<Document> content) implements Document {

        public Element {
            attributes = Map.copyOf(attributes);
            content = List.copyOf(content);
        }

        public Optional<String> attribute(String name) {
            return Optional.ofNullable(attributes.get(name));
        }

        public String attribute(String name, String fallback) {
            return attributes.getOrDefault(name, fallback);
        }

        public boolean has(String name) {
            return attributes.containsKey(name);
        }

        /// The elements under this one with that tag name, however deep, in the
        /// order they appear.
        public List<Element> find(String tag) {
            var found = new ArrayList<Element>();
            walk(this, element -> {
                if (element.name().equals(tag)) found.add(element);
            });
            return found;
        }

        /// Every element under this one, deepest last, including this one.
        public List<Element> elements() {
            var found = new ArrayList<Element>();
            walk(this, found::add);
            return found;
        }
    }

    /// The text of this and everything under it, in document order, with the
    /// whitespace collapsed — which is what a person sees, and therefore what a
    /// link is called.
    default String text() {
        var text = new StringBuilder();
        gather(this, text);
        return text.toString().replaceAll("\\s+", " ").strip();
    }

    /// Reads a page.
    static Element read(String html) {
        return Markup.read(html);
    }

    private static void gather(Document document, StringBuilder into) {
        switch (document) {
            case Text(var text) -> into.append(text);
            case Element(var ignored, var attributes, var content) -> content.forEach(child -> gather(child, into));
        }
    }

    private static void walk(Element element, java.util.function.Consumer<Element> visit) {
        visit.accept(element);
        for (var child : element.content()) {
            if (child instanceof Element nested) walk(nested, visit);
        }
    }

    /// The elements that never have children, whatever the markup says. A
    /// reader that pushes `<input>` onto a stack swallows the rest of the form.
    Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr", "img",
            "input", "link", "meta", "param", "source", "track", "wbr");
}
