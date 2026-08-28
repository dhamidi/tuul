package markdown;

import java.util.Spliterator;
import java.util.function.Consumer;

/// A depth-first walk of a document, as a [Spliterator], so that
/// [Document#walk] is lazy rather than a list pretending to be a stream.
///
/// It owns one [Cursor] and moves it. Nothing is allocated per node except the
/// [Step] handed out, which is a record of an index, a kind and a direction —
/// small, immutable, and independent of the cursor that produced it. Yielding
/// the cursor itself would have been cheaper still and wrong: a stream's
/// elements are meant to be values, and `walk().toList()` would then be a list
/// of N references to one cursor showing its last position.
///
/// Every node is entered and left, including a node with no children, so a
/// consumer that opens something on the way in and closes it on the way out
/// needs no special case. That is why the element is an event rather than a
/// plain handle: rendering HTML has to know when an element ends, and a walk
/// that only announced arrivals would make the renderer keep a stack that the
/// walk is already keeping.
///
/// **Sequential only.** [#trySplit] answers null and always will: the walk is a
/// moving cursor, and there is no way to hand half of it to another thread
/// without giving that thread its own. A parallel walk of a document is not a
/// thing anybody has needed, and pretending otherwise would cost the
/// allocation-free property that is the point of the array.
final class Walk implements Spliterator<Step> {

    private final Cursor cursor;
    private final int root;
    private boolean entering = true;
    private boolean done;

    Walk(Document document, int root) {
        this.cursor = new Cursor(document, root);
        this.root = root;
    }

    @Override
    public boolean tryAdvance(Consumer<? super Step> action) {
        if (done) return false;
        action.accept(new Step(cursor.index(), cursor.kind(), entering));
        advance();
        return true;
    }

    /// The state machine: going in, take a child if there is one and leave
    /// otherwise; coming out, take a sibling if there is one and climb
    /// otherwise. The walk ends when it leaves the node it started on.
    private void advance() {
        if (entering) {
            if (cursor.firstChild()) return;
            entering = false;
            return;
        }
        if (cursor.index() == root) {
            done = true;
            return;
        }
        if (cursor.nextSibling()) {
            entering = true;
            return;
        }
        cursor.parent();
    }

    @Override
    public Spliterator<Step> trySplit() {
        return null;
    }

    @Override
    public long estimateSize() {
        return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
        return ORDERED | NONNULL | IMMUTABLE;
    }
}
