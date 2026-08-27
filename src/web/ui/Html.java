package web.ui;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/// A piece of markup, which knows how to write itself.
///
/// Rendering goes straight into a [Writer]: an outer component writes its
/// opening tag before its children exist as characters, so a page reaches the
/// browser as it is built rather than after it is finished. Nothing here
/// accumulates a String on the way — [#markup()] exists for tests and for the
/// small things, and says so.
///
/// A component is a method that returns one of these. That is the whole
/// abstraction: `Html card(User user)` composes by being called, and there is no
/// registry, no lifecycle and no base class to extend.
///
/// The point of building markup out of values rather than out of string
/// concatenation is that the broken cases cannot be written down. Text is
/// escaped for the place it lands in, a name that is not a name is refused, a
/// void element cannot have children, and the text inside a `<script>` cannot
/// end the script.
public sealed interface Html extends Node {

    /// Elements that are their whole tag. HTML gives them no closing tag, so
    /// children would have nowhere to go.
    Set<String> VOID = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");

    void write(Writer out) throws IOException;

    /// The markup as a String. For a test, or for something small enough that
    /// holding all of it is not the problem this library is about.
    default String markup() {
        var out = new StringWriter();
        try {
            write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /// Text, escaped where it lands.
    record Text(String value) implements Html {

        public Text {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public void write(Writer out) throws IOException {
            Escape.text(value, out);
        }
    }

    /// Markup written through untouched.
    ///
    /// The name is the point. Everything else in this library is safe by
    /// construction, and this is the one door out of that — so it is spelled in
    /// a way that a reader of the call site cannot skim past, and a reviewer can
    /// grep for.
    record Unsafe(String html) implements Html {

        public Unsafe {
            Objects.requireNonNull(html, "html");
        }

        @Override
        public void write(Writer out) throws IOException {
            out.write(html);
        }
    }

    /// An element and what is inside it.
    record Element(String name, List<Attribute> attributes, List<Html> children) implements Html {

        public Element {
            Escape.element(name);
            attributes = List.copyOf(attributes);
            children = List.copyOf(children);
            if (VOID.contains(name) && !children.isEmpty()) {
                throw new HtmlException("<" + name + "> is a void element and cannot contain anything");
            }
        }

        @Override
        public void write(Writer out) throws IOException {
            out.write('<');
            out.write(name);
            for (var attribute : attributes) attribute.write(out);
            out.write('>');
            if (VOID.contains(name)) return;
            for (var child : children) child.write(out);
            out.write("</");
            out.write(name);
            out.write('>');
        }
    }

    /// A `<script>` or a `<style>`: an element whose content is text rather than
    /// markup, and is written exactly as given.
    record RawText(String name, List<Attribute> attributes, String content) implements Html {

        public RawText {
            Escape.element(name);
            attributes = List.copyOf(attributes);
            Objects.requireNonNull(content, "content");
        }

        @Override
        public void write(Writer out) throws IOException {
            out.write('<');
            out.write(name);
            for (var attribute : attributes) attribute.write(out);
            out.write('>');
            Escape.raw(name, content, out);
            out.write("</");
            out.write(name);
            out.write('>');
        }
    }

    /// Several pieces of markup and no element around them — a list of rows, a
    /// response made of Turbo Streams, a component that returns two things.
    record Fragment(List<Html> children) implements Html {

        public Fragment {
            children = List.copyOf(children);
        }

        @Override
        public void write(Writer out) throws IOException {
            for (var child : children) child.write(out);
        }
    }

    /// Markup decided at the moment it is written.
    ///
    /// This is what keeps a large page from being a large object: a thousand
    /// rows can be written straight from a cursor without a thousand records
    /// existing at once.
    record Deferred(Body body) implements Html {

        public Deferred {
            Objects.requireNonNull(body, "body");
        }

        @Override
        public void write(Writer out) throws IOException {
            body.write(out);
        }
    }

    /// What a [Deferred] does when the time comes.
    @FunctionalInterface
    interface Body {
        void write(Writer out) throws IOException;
    }

    static Html text(String value) {
        return new Text(value);
    }

    /// Markup that has already been made safe by something else. Say why at the
    /// call site.
    static Html unsafe(String html) {
        return new Unsafe(html);
    }

    static Html fragment(Html... children) {
        return new Fragment(List.of(children));
    }

    static Html fragment(List<Html> children) {
        return new Fragment(children);
    }

    /// Nothing at all, for the branch of a conditional that renders nothing.
    static Html nothing() {
        return new Fragment(List.of());
    }

    static Html deferred(Body body) {
        return new Deferred(body);
    }

    /// One piece of markup per item, written as the items arrive rather than
    /// collected first.
    static <T> Html each(Iterable<T> items, Function<? super T, Html> render) {
        return new Deferred(out -> {
            for (var item : items) render.apply(item).write(out);
        });
    }

    /// Any element by name, with its attributes and children mixed together in
    /// the order they read best. [Tags] has the ones with names.
    static Html element(String name, Node... content) {
        var attributes = new ArrayList<Attribute>();
        var children = new ArrayList<Html>();
        for (var node : content) {
            switch (node) {
                case Attribute attribute -> attributes.add(attribute);
                case Html child -> children.add(child);
            }
        }
        return new Element(name, attributes, children);
    }

    /// An element whose content is text rather than markup.
    static Html rawText(String name, String content, Attribute... attributes) {
        return new RawText(name, List.of(attributes), content);
    }
}
