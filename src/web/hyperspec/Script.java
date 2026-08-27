package web.hyperspec;

import java.util.List;

/// A parsed spec, as data.
///
/// Tcl's shape and nothing else: a script is commands, a command is words, and
/// a word is the pieces it was written from — text, a variable to look up, and
/// a script whose result stands where it was written. Substitution is a
/// property of the word rather than a pass over the source, which is why
/// `{braces}` can hold a body that is parsed later and `"quotes"` cannot.
///
/// Every command carries the line it was written on. A spec that fails has to
/// say where, and a line number is the only answer anybody wants.
public record Script(List<Command> commands) {

    public Script {
        commands = List.copyOf(commands);
    }

    /// A command, and where to point when it goes wrong.
    public record Command(int line, List<Word> words) {

        public Command {
            words = List.copyOf(words);
        }

        public boolean isEmpty() {
            return words.isEmpty();
        }

        /// The command's name as written, before any substitution — enough to
        /// name it in a failure, and what [Hyperspec] dispatches on.
        public String name() {
            if (words.isEmpty()) return "";
            var first = words.getFirst();
            return first.parts().size() == 1 && first.parts().getFirst() instanceof Part.Text(var text) ? text : "";
        }
    }

    /// One word. `body` is the text between braces when the word was braced,
    /// and empty otherwise: a braced word is the only kind a command can parse
    /// as a script of its own, which is how `client name { ... }` works.
    public record Word(List<Part> parts, String body, boolean braced, int line) {

        public Word {
            parts = List.copyOf(parts);
        }
    }

    /// A piece of a word.
    public sealed interface Part {

        /// Text as it was written, with escapes already resolved.
        record Text(String text) implements Part {}

        /// `$name`, looked up when the word is evaluated.
        record Variable(String name) implements Part {}

        /// `[script]`, run when the word is evaluated, its result standing here.
        record Substitution(Script script) implements Part {}
    }
}
