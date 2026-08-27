package peg;

import java.util.List;

/// How a parse turned out.
///
/// A failure carries the furthest position anything reached and what would have
/// let it go further — not the position where the last alternative gave up.
/// With ordered choice those are rarely the same place, and only the furthest
/// one is worth telling anybody about.
public sealed interface Parse<T> {

    record Ok<T>(Tree<T> tree, int position) implements Parse<T> {}

    record Failure<T>(int position, List<String> expected) implements Parse<T> {

        public Failure {
            expected = List.copyOf(expected);
        }

        public String message() {
            return "expected " + String.join(" or ", expected) + " at " + position;
        }
    }
}
