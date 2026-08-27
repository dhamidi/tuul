package peg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/// What a parse produces. A tree by default: the elements that matched, under
/// the labels the grammar gave them.
///
/// A grammar that wants something other than a tree maps a rule to a value, and
/// that value sits in the tree where the match was — so a grammar can be part
/// tree and part values without being two grammars.
public sealed interface Tree<T> {

    /// One element of the input, as it was.
    record Leaf<T>(T value) implements Tree<T> {}

    /// A labelled match, and what it was made of.
    record Node<T>(String label, List<Tree<T>> children) implements Tree<T> {

        public Node {
            children = List.copyOf(children);
        }
    }

    /// What a rule was mapped to. The elements it matched are gone: this is the
    /// point of mapping.
    record Value<T>(Object value) implements Tree<T> {}

    /// Every element that matched, in the order it arrived.
    default List<T> leaves() {
        var leaves = new ArrayList<T>();
        walk(this, tree -> {
            if (tree instanceof Leaf<T>(var value)) leaves.add(value);
        });
        return leaves;
    }

    /// Every mapped value, in the order the rules that made them matched.
    default List<Object> values() {
        var values = new ArrayList<Object>();
        walk(this, tree -> {
            if (tree instanceof Value<T>(var value)) values.add(value);
        });
        return values;
    }

    /// Every node under this one carrying the label, however deep.
    default List<Tree<T>> nodes(String label) {
        var nodes = new ArrayList<Tree<T>>();
        walk(this, tree -> {
            if (tree instanceof Node<T> node && node.label().equals(label)) nodes.add(node);
        });
        return nodes;
    }

    default Optional<Tree<T>> node(String label) {
        return nodes(label).stream().findFirst();
    }

    /// The matched elements written out — the whole point when the elements are
    /// characters, and a readable summary when they are not.
    default String text() {
        return leaves().stream().map(String::valueOf).collect(Collectors.joining());
    }

    private static <T> void walk(Tree<T> tree, java.util.function.Consumer<Tree<T>> visit) {
        visit.accept(tree);
        if (tree instanceof Node<T>(var ignored, var children)) children.forEach(child -> walk(child, visit));
    }
}
