package web.ui;

import java.util.ArrayList;
import java.util.Locale;

/// The markup [Turbo](https://turbo.hotwired.dev) reads.
///
/// Turbo works by recognising elements the browser does not know: a frame it
/// replaces in place, and a stream it applies to a page that is already open.
/// Both are ordinary custom elements, so they need no support from this library
/// beyond writing them correctly — and that is the point of putting them here,
/// because writing them correctly means remembering things like the `<template>`
/// that has to wrap the content of every stream.
public final class Turbo {

    /// What a response made of streams has to say it is. Send anything else and
    /// Turbo will treat the body as a page.
    public static final String STREAM_TYPE = "text/vnd.turbo-stream.html";

    /// What a stream does to its target.
    public enum Action {

        APPEND, PREPEND, REPLACE, UPDATE, REMOVE, BEFORE, AFTER, REFRESH;

        public String attribute() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private Turbo() {}

    /// A frame, holding markup that Turbo will replace on its own when a
    /// response contains a frame with the same id.
    public static Html frame(String id, Node... content) {
        var nodes = new ArrayList<Node>();
        nodes.add(Attributes.id(id));
        nodes.addAll(java.util.List.of(content));
        return Html.element("turbo-frame", nodes.toArray(Node[]::new));
    }

    /// A frame that fetches its own contents. Add [#lazy()] and it waits until
    /// it is visible.
    public static Html frame(String id, String src) {
        return Html.element("turbo-frame", Attributes.id(id), Attributes.src(src));
    }

    /// Wait until the frame is scrolled into view before loading it.
    public static Attribute lazy() {
        return Attributes.loading("lazy");
    }

    /// Several streams, which is what a Turbo Stream response body is. Send it
    /// as [#STREAM_TYPE].
    public static Html streams(Html... streams) {
        return Html.fragment(streams);
    }

    /// One stream, aimed at the element with this id.
    public static Html stream(Action action, String target, Html... content) {
        return element(action, "target", target, content);
    }

    /// One stream, aimed at every element matching this CSS selector.
    public static Html streamAll(Action action, String selector, Html... content) {
        return element(action, "targets", selector, content);
    }

    public static Html append(String target, Html... content) {
        return stream(Action.APPEND, target, content);
    }

    public static Html prepend(String target, Html... content) {
        return stream(Action.PREPEND, target, content);
    }

    /// Replaces the target itself.
    public static Html replace(String target, Html... content) {
        return stream(Action.REPLACE, target, content);
    }

    /// Replaces what is inside the target, and leaves the target.
    public static Html update(String target, Html... content) {
        return stream(Action.UPDATE, target, content);
    }

    public static Html before(String target, Html... content) {
        return stream(Action.BEFORE, target, content);
    }

    public static Html after(String target, Html... content) {
        return stream(Action.AFTER, target, content);
    }

    /// Removes the target. Nothing to send with it, so nothing is sent.
    public static Html remove(String target) {
        return stream(Action.REMOVE, target);
    }

    /// Asks the page to refresh itself, morphing rather than reloading.
    public static Html refresh() {
        return Html.element("turbo-stream", Attribute.of("action", Action.REFRESH.attribute()));
    }

    /// Which frame a link or a form should drive, rather than the one it is in.
    public static Attribute targetFrame(String id) {
        return Attributes.data("turbo-frame", id);
    }

    /// The method a link should use — Turbo turns a link into a form to do it.
    public static Attribute method(String method) {
        return Attributes.data("turbo-method", method);
    }

    /// Ask first.
    public static Attribute confirm(String question) {
        return Attributes.data("turbo-confirm", question);
    }

    /// Keep this element across a page navigation, by id.
    public static Attribute permanent() {
        return Attributes.flag("data-turbo-permanent");
    }

    /// Opt this element, and everything inside it, out of Turbo Drive.
    public static Attribute disabled() {
        return Attributes.data("turbo", "false");
    }

    /// The content of a stream goes inside a `<template>`, always. Turbo reads
    /// the template rather than the element, and markup that skips it is
    /// silently ignored — which is exactly the kind of thing a caller should not
    /// have to know.
    private static Html element(Action action, String attribute, String value, Html... content) {
        var nodes = new ArrayList<Node>();
        nodes.add(Attribute.of("action", action.attribute()));
        nodes.add(Attribute.of(attribute, value));
        if (content.length > 0) nodes.add(Tags.template(content));
        return Html.element("turbo-stream", nodes.toArray(Node[]::new));
    }
}
