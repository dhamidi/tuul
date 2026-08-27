package web.hyperspec;

import java.util.List;
import peg.Tree;

/// Reading a parse tree by the labels a grammar put on it.
///
/// [Tree#nodes] walks the whole subtree, which is the wrong question here: a
/// word's parts are its own and a nested script's are not, and an element's
/// attributes are its own and its children's are not. These look at direct
/// children only.
final class Trees {

    private Trees() {}

    static List<Tree<Character>> children(Tree<Character> tree, String label) {
        if (!(tree instanceof Tree.Node<Character>(var ignored, var children))) return List.of();
        return children.stream()
                .filter(child -> child instanceof Tree.Node<Character> node && node.label().equals(label))
                .toList();
    }

    static Tree<Character> only(Tree<Character> tree, String label) {
        var found = children(tree, label);
        if (found.isEmpty()) throw new IllegalStateException("no " + label + " under " + tree);
        return found.getFirst();
    }

    static String text(Tree<Character> tree, String label) {
        var found = children(tree, label);
        return found.isEmpty() ? "" : found.getFirst().text();
    }
}
