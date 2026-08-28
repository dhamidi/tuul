package markdown;

/// A place in a document, and the way to move from it.
///
/// The shape is [Tree-sitter's](https://tree-sitter.github.io) tree cursor,
/// because it is the same trade — a flat tree walked by a cursor — and it has
/// been used enough to have found the right primitives. Movement is three
/// booleans: [#firstChild], [#nextSibling], [#parent], each answering whether
/// it moved. Depth-first traversal is then a loop rather than a recursion: try
/// a child, else a sibling, else climb until a sibling exists or you are back
/// at the root.
///
/// ```
/// var cursor = document.cursor();
/// while (cursor.firstChild() || cursor.nextSibling() || cursor.climb()) {
///     System.out.println(cursor.kind() + ": " + cursor.text());
/// }
/// ```
///
/// A cursor is mutable and reusable, and moving it writes one field. That is
/// the whole reason the DOM is an array: walking a document of a hundred
/// thousand nodes allocates nothing. Like [sqlite3.Rows], it is a position
/// rather than a value — what a cursor is on is read out during the step, and
/// then the cursor moves. [#copy] forks one and [#reset] re-roots one, both
/// cheaply, which is what a walk that has to remember where it was should use.
///
/// [Document#walk] is this cursor as a [java.util.stream.Stream], for a caller
/// who would rather compose than loop.
public final class Cursor {

    private final Document document;
    private final Nodes nodes;
    private int node;

    Cursor(Document document, int node) {
        this.document = document;
        this.nodes = document.nodes();
        this.node = node;
    }

    public Document document() {
        return document;
    }

    /// Where this cursor is, as an index into the node array. Two cursors on
    /// the same index are on the same node, and an index outlives the cursor
    /// that produced it — which is what [Step] carries.
    public int index() {
        return node;
    }

    public Kind kind() {
        return nodes.kind(node);
    }

    public boolean is(Kind kind) {
        return kind() == kind;
    }

    /// A heading's level, an ordered list's first number, a bullet list's -1.
    public int number() {
        return nodes.number(node);
    }

    /// Where this node starts in the source. Answerable at any moment, which is
    /// half of what a cursor over a flat tree is for.
    public int start() {
        return nodes.start(node);
    }

    public int end() {
        return nodes.end(node);
    }

    /// The source this node covers, without copying it.
    public CharSequence text() {
        return document.text(node);
    }

    /// The same, copied — for a caller that wants to keep it after the cursor
    /// has moved on.
    public String string() {
        return text().toString();
    }

    public boolean hasChildren() {
        return nodes.first(node) != Nodes.NONE;
    }

    /// Moves to the first child, and says whether there was one. A cursor that
    /// cannot move does not move, so a failed step leaves it where it was.
    public boolean firstChild() {
        return moveTo(nodes.first(node));
    }

    public boolean lastChild() {
        return moveTo(nodes.last(node));
    }

    public boolean nextSibling() {
        return moveTo(nodes.next(node));
    }

    public boolean parent() {
        return moveTo(nodes.parent(node));
    }

    /// Climbs until there is a next sibling, and takes it. The third move of a
    /// depth-first loop: a child, else a sibling, else this.
    public boolean climb() {
        while (parent()) {
            if (nextSibling()) return true;
        }
        return false;
    }

    /// The first child of this node with that kind, if it has one. Link
    /// destinations, titles and info strings are children, so this is how they
    /// are read.
    public boolean child(Kind kind) {
        var child = nodes.first(node);
        while (child != Nodes.NONE) {
            if (nodes.kind(child) == kind) return moveTo(child);
            child = nodes.next(child);
        }
        return false;
    }

    /// A second cursor on the same document, here. Forking a walk costs one
    /// small object rather than a copy of anything.
    public Cursor copy() {
        return new Cursor(document, node);
    }

    /// Puts this cursor on another node, so one cursor can walk many subtrees.
    /// Tree-sitter calls it resetting, and it is why a walk needs no allocation
    /// even when it starts over.
    public Cursor reset(int node) {
        this.node = node;
        return this;
    }

    public Cursor at(int node) {
        return new Cursor(document, node);
    }

    private boolean moveTo(int target) {
        if (target == Nodes.NONE) return false;
        node = target;
        return true;
    }

    @Override
    public String toString() {
        return kind() + "@" + node;
    }
}
