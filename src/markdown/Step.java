package markdown;

/// One move of a walk: a node, and whether the walk is going into it or coming
/// back out.
///
/// A leaf produces both — entering and leaving — so a consumer that opens a tag
/// on the way in and closes it on the way out needs no special case for a node
/// with no children. That is what makes a renderer a fold over a stream rather
/// than a recursion.
public record Step(int node, Kind kind, boolean entering) {

    public boolean leaving() {
        return !entering;
    }
}
