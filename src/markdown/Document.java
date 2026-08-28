package markdown;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// A parsed document: the source it was parsed from, and the nodes that
/// describe it.
///
/// The document owns one copy of the source and one array of nodes. Every piece
/// of text in the tree is a span into that source rather than a string of its
/// own, so the whole cost of parsing a megabyte is the megabyte plus 32 bytes a
/// node — see [Nodes] for what a node is made of.
///
/// It is read with a [Cursor], which walks by index and allocates nothing, or
/// with [#walk], which is the same walk as a lazy stream of enter and leave
/// events.
public final class Document {

    private final CharSequence source;
    private final Nodes nodes;
    private final Map<String, Integer> definitions;

    Document(CharSequence source, Nodes nodes, Map<String, Integer> definitions) {
        this.source = source;
        this.nodes = nodes;
        this.definitions = Map.copyOf(definitions);
    }

    /// The text this document was parsed from, unchanged. Every node's span
    /// indexes into it.
    public CharSequence source() {
        return source;
    }

    /// How many nodes there are, including the document itself.
    public int size() {
        return nodes.size();
    }

    /// What the node array costs, in bytes. The source is on top of this, once.
    public int bytes() {
        return nodes.bytes();
    }

    /// A cursor on the document node, which is node 0 and the only one without
    /// a parent.
    public Cursor cursor() {
        return new Cursor(this, 0);
    }

    public Cursor at(int node) {
        return new Cursor(this, node);
    }

    /// The source a node covers, without copying it — a [CharSequence] view,
    /// not a `substring`.
    public CharSequence text(int node) {
        return source.subSequence(nodes.start(node), nodes.end(node));
    }

    /// Every node, depth first, entered and left. Lazy, and sequential only —
    /// see [Walk] for why.
    public Stream<Step> walk() {
        return walk(0);
    }

    /// The same, over one subtree.
    public Stream<Step> walk(int root) {
        return StreamSupport.stream(new Walk(this, root), false);
    }

    /// What a label was defined as, if anything defined it.
    ///
    /// A link does not need this: a reference that resolved carries the
    /// definition it was patched with, and one that did not is a
    /// [Kind#REFERENCE] rather than a link. This is for a reader who wants to
    /// ask about a label without walking the tree looking for it.
    public Optional<Cursor> definition(String label) {
        return Optional.ofNullable(definitions.get(Labels.normalize(label))).map(this::at);
    }

    public Map<String, Integer> definitions() {
        return definitions;
    }

    Nodes nodes() {
        return nodes;
    }
}
