package web.ui;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/// A named piece of a design — a card, a badge, a stack of things — and itself
/// a piece of markup.
///
/// A component is an ordinary class holding the props it was given and the
/// children it was given, and it *is* [Html]: anywhere a [Node] goes, a
/// component goes. `card(props, heading(...), prose(...))` therefore composes
/// exactly the way the tag helpers do, and nothing has to unwrap a component
/// before putting it somewhere.
///
/// That is why [Html] permits this one non-sealed case, and it is the only one.
/// The rest of the hierarchy is closed because the broken cases of *markup* are
/// worth making impossible — a name that is not a name, a void element with
/// children, a script that can end itself. A component is not a new kind of
/// markup, it is a new name for some, so it opens the door to classes in other
/// packages while every one of them still has to produce [Html] in the end. An
/// application's own components implement this and are indistinguishable from
/// the ones shipped in [Ui].
///
/// [#render()] is called when the component is written rather than when it is
/// constructed, so a streamed page stays streamed: a card deep in a document
/// does not exist as markup until the writer reaches it.
public non-sealed interface Component extends Html {

    /// The markup this component stands for. Called at write time.
    Html render();

    @Override
    default void write(Writer out) throws IOException {
        render().write(out);
    }

    /// The content a component was handed, with the component's own class names
    /// folded into whatever the caller passed.
    ///
    /// A component owns the outside of what it renders and a caller owns the
    /// rest, so both want to put something on the root element — the component
    /// its design, the caller a `data-` attribute or a microdata property. Two
    /// `class` attributes on one element is invalid HTML and a browser keeps
    /// the first, so they are merged here rather than written twice.
    static Node[] rooted(String own, Node[] content) {
        var nodes = new ArrayList<Node>();
        var classes = new ArrayList<String>(List.of(own.split(" ")));
        for (var node : content) {
            if (node instanceof Attribute attribute && attribute.name().equals("class")) {
                attribute.value().ifPresent(value -> classes.addAll(List.of(value.split(" "))));
                continue;
            }
            nodes.add(node);
        }
        nodes.addFirst(Attributes.classes(classes.toArray(new String[0])));
        return nodes.toArray(new Node[0]);
    }
}
