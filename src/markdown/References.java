package markdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Link reference definitions, and the references still waiting for one.
///
/// A reference may name a definition written further down — usually at the
/// bottom of the document — which leaves a parser two ways to go. It can hold
/// the whole document and resolve everything at the end, or it can write the
/// reference down as unresolved and patch it when the definition arrives. This
/// does the second, and that is the difference between a parser that answers
/// questions about a document it has finished reading and one that hands out
/// finished nodes while the rest of the input is still coming.
///
/// Patching is writing two ints. A waiting node stops being a
/// [Kind#REFERENCE] and becomes a [Kind#LINK] or a [Kind#IMAGE], and its
/// number becomes the definition it points at. Nothing is rebuilt and nothing
/// moves, because the array holds indices rather than references — which is the
/// same property that lets emphasis close by adopting its siblings.
///
/// The waiting set holds only what nobody has defined yet, keyed by label, and
/// a label is forgotten the moment it is defined. A document that defines
/// everything before using it waits on nothing; one that puts every definition
/// at the bottom waits on one entry per label, however many times each is used.
final class References {

    /// What a waiting node's number means until it is patched: an image
    /// reference has to be told apart from a link one, and its number is free
    /// until it holds a definition.
    private static final int LINK = 0;

    static final int IMAGE = 1;

    /// Whether an unresolved reference was written as an image. Only [Html]
    /// asks, because `![alt][x]` that nobody defined renders as `![alt]` and
    /// `[text][x]` renders as `[text]`.
    static boolean image(Nodes nodes, int node) {
        return nodes.kind(node) == Kind.REFERENCE && nodes.number(node) == IMAGE;
    }

    private final Nodes nodes;
    private final Map<String, Integer> definitions = new LinkedHashMap<>();
    private final Map<String, List<Integer>> waiting = new LinkedHashMap<>();

    References(Nodes nodes) {
        this.nodes = nodes;
    }

    /// Records a definition and patches everything that was waiting on it.
    ///
    /// The first definition of a label wins, which is what CommonMark says, so
    /// a second one changes nothing — including for references that arrived in
    /// between, since they were patched by the first.
    void define(String label, int node) {
        var name = Labels.normalize(label);
        if (definitions.putIfAbsent(name, node) != null) return;
        var patient = waiting.remove(name);
        if (patient == null) return;
        for (var reference : patient) resolve(reference, node);
    }

    /// The definition of a label, or [Nodes#NONE] if nobody has written one
    /// *yet* — a caller cannot tell "not defined" from "not defined so far",
    /// which is why the answer is a node to wait on rather than a verdict.
    int definition(String label) {
        return definitions.getOrDefault(Labels.normalize(label), Nodes.NONE);
    }

    /// Marks a node as a reference nobody has defined, and remembers to come
    /// back to it.
    void await(String label, int node, boolean image) {
        nodes.kind(node, Kind.REFERENCE);
        nodes.number(node, image ? IMAGE : LINK);
        waiting.computeIfAbsent(Labels.normalize(label), key -> new ArrayList<>()).add(node);
    }

    /// Points a node at its definition, whether that was known when the
    /// reference was read or arrived afterwards.
    void resolve(int node, int definition) {
        var image = nodes.kind(node) == Kind.IMAGE || nodes.number(node) == IMAGE;
        nodes.kind(node, image ? Kind.IMAGE : Kind.LINK);
        nodes.number(node, definition);
    }

    /// What every label was defined as. A [Document] keeps this so that a
    /// reader can ask about a label without walking the tree looking for one.
    Map<String, Integer> definitions() {
        return definitions;
    }

    /// How many references are still waiting. Only a test has any business
    /// asking, and it asks in order to prove that the answer reaches zero
    /// before the document ends.
    int unresolved() {
        return waiting.values().stream().mapToInt(List::size).sum();
    }
}
