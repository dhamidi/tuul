package markdown;

import java.util.Arrays;

/// The document, as one array of integers.
///
/// Every node is eight ints — kind, parent, first child, last child, next
/// sibling, start, end, number — laid end to end in a single `int[]` that grows
/// by doubling. A node is 32 bytes and a document of N nodes is 32N bytes plus
/// one copy of the source, and there is nothing else: no per-node object, no
/// header, no reference, no map on the side.
///
/// Text is never copied. A node's start and end are offsets into the source,
/// so a paragraph of a thousand characters costs the same 32 bytes as an empty
/// one, and reading it is a `subSequence` rather than a `substring`.
///
/// Links are indices, so a parent, a first child and a next sibling are all one
/// array read. That is what makes [Cursor] able to walk without allocating —
/// the price is that a reader moves a cursor instead of holding a node, and the
/// whole of this class exists to make that price worth paying.
final class Nodes {

    /// kind, parent, first, last, next, start, end, number.
    static final int WIDTH = 8;

    private static final int KIND = 0;
    private static final int PARENT = 1;
    private static final int FIRST = 2;
    private static final int LAST = 3;
    private static final int NEXT = 4;
    private static final int START = 5;
    private static final int END = 6;
    private static final int NUMBER = 7;

    static final int NONE = -1;

    private static final Kind[] KINDS = Kind.values();

    private int[] cells = new int[WIDTH * 64];
    private int size;

    /// Adds a node under `parent`, at the end of its children. The end is set
    /// when it closes; a node that never closes ends where it started, which is
    /// what an empty one means anyway.
    int open(Kind kind, int parent, int start) {
        var node = allocate(kind, parent, start);
        if (parent != NONE) append(parent, node);
        return node;
    }

    /// Adds a node immediately after one of its siblings rather than at the
    /// end. Emphasis needs it: the node that wraps a run of inlines has to sit
    /// where the delimiter that opened it was, not where the parser happens to
    /// have got to.
    int insertAfter(int sibling, Kind kind, int start) {
        var parent = parent(sibling);
        var node = allocate(kind, parent, start);
        cells[node * WIDTH + NEXT] = cells[sibling * WIDTH + NEXT];
        cells[sibling * WIDTH + NEXT] = node;
        if (cells[parent * WIDTH + LAST] == sibling) cells[parent * WIDTH + LAST] = node;
        return node;
    }

    private int allocate(Kind kind, int parent, int start) {
        var node = size++;
        if (size * WIDTH > cells.length) cells = Arrays.copyOf(cells, cells.length * 2);
        var at = node * WIDTH;
        cells[at + KIND] = kind.ordinal();
        cells[at + PARENT] = parent;
        cells[at + FIRST] = NONE;
        cells[at + LAST] = NONE;
        cells[at + NEXT] = NONE;
        cells[at + START] = start;
        cells[at + END] = start;
        cells[at + NUMBER] = 0;
        return node;
    }

    private void append(int parent, int child) {
        var last = cells[parent * WIDTH + LAST];
        if (last == NONE) cells[parent * WIDTH + FIRST] = child;
        else cells[last * WIDTH + NEXT] = child;
        cells[parent * WIDTH + LAST] = child;
        cells[child * WIDTH + PARENT] = parent;
        cells[child * WIDTH + NEXT] = NONE;
    }

    /// Moves the run of siblings from `first` to `last` under `parent`, which
    /// has to be their previous sibling. This is how emphasis closes: the
    /// delimiter that opened it becomes the emphasis node and swallows what
    /// came after it, so nothing is allocated twice and the array stays in the
    /// order the document was written.
    void adopt(int parent, int first, int last) {
        var above = cells[parent * WIDTH + PARENT];
        if (above != NONE && cells[above * WIDTH + LAST] == last) cells[above * WIDTH + LAST] = parent;
        cells[parent * WIDTH + NEXT] = cells[last * WIDTH + NEXT];
        cells[parent * WIDTH + FIRST] = first;
        cells[parent * WIDTH + LAST] = last;
        cells[last * WIDTH + NEXT] = NONE;
        for (var child = first; child != NONE; child = cells[child * WIDTH + NEXT]) {
            cells[child * WIDTH + PARENT] = parent;
        }
    }

    /// Takes a node out of its parent's children. Used when a construct turns
    /// out not to be one — a `[` that never found its `]`.
    void unlink(int node) {
        var parent = cells[node * WIDTH + PARENT];
        if (parent == NONE) return;
        var previous = NONE;
        for (var child = cells[parent * WIDTH + FIRST]; child != node; child = cells[child * WIDTH + NEXT]) {
            previous = child;
        }
        var next = cells[node * WIDTH + NEXT];
        if (previous == NONE) cells[parent * WIDTH + FIRST] = next;
        else cells[previous * WIDTH + NEXT] = next;
        if (cells[parent * WIDTH + LAST] == node) cells[parent * WIDTH + LAST] = previous;
        cells[node * WIDTH + PARENT] = NONE;
        cells[node * WIDTH + NEXT] = NONE;
    }

    int size() {
        return size;
    }

    int bytes() {
        return cells.length * Integer.BYTES;
    }

    Kind kind(int node) {
        return KINDS[cells[node * WIDTH + KIND]];
    }

    void kind(int node, Kind kind) {
        cells[node * WIDTH + KIND] = kind.ordinal();
    }

    int parent(int node) {
        return cells[node * WIDTH + PARENT];
    }

    int first(int node) {
        return cells[node * WIDTH + FIRST];
    }

    int last(int node) {
        return cells[node * WIDTH + LAST];
    }

    int next(int node) {
        return cells[node * WIDTH + NEXT];
    }

    int start(int node) {
        return cells[node * WIDTH + START];
    }

    void start(int node, int start) {
        cells[node * WIDTH + START] = start;
    }

    int end(int node) {
        return cells[node * WIDTH + END];
    }

    void end(int node, int end) {
        cells[node * WIDTH + END] = end;
    }

    int number(int node) {
        return cells[node * WIDTH + NUMBER];
    }

    void number(int node, int number) {
        cells[node * WIDTH + NUMBER] = number;
    }
}
