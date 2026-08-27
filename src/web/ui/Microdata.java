package web.ui;

/// Microdata: what a page is *about*, as opposed to how it looks.
///
/// `itemscope`, `itemtype` and `itemprop` are in the HTML specification, every
/// agent that reads pages already understands them, and they say the one thing
/// a hypermedia test needs to ask — which resource is this, and what are its
/// attributes. [web.hyperspec] reads exactly these.
///
/// `data-` attributes would have been the other candidate and are the wrong
/// one: in this framework they are how Stimulus finds its controllers, so a
/// test reading them would be testing the client's behaviour rather than the
/// resource the page describes.
///
/// ```
/// li(Microdata.scope(), Microdata.type("/Note"),
///    span(Microdata.of("title"), text(note.title())),
///    span(Microdata.of("author"), text(note.author())))
/// ```
public final class Microdata {

    private Microdata() {}

    /// A resource begins here. On its own it describes an item with no type,
    /// which is legitimate; with [#type] it says what the item is.
    ///
    /// These are two calls rather than one because an attribute is one
    /// attribute — an element takes them as ordinary nodes, and a helper
    /// returning two of them could not be passed alongside the children.
    public static Attribute scope() {
        return Attributes.flag("itemscope");
    }

    public static Attribute type(String type) {
        return Attributes.attribute("itemtype", type);
    }

    /// One attribute of the item that encloses it. A nested [#item] starts a
    /// resource of its own, so its properties stay with it.
    public static Attribute of(String name) {
        return Attributes.attribute("itemprop", name);
    }
}
