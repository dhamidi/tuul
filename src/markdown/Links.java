package markdown;

/// Where a label points, according to somebody who is not the document.
///
/// A reference nobody defined renders as the text that was typed, which is
/// right: `[the docs]` in a document that never says what `the docs` is has to
/// come out as `[the docs]`. But a caller sometimes *does* know — the label is
/// a name in some other scheme that the caller has an index of — and until now
/// it had no way to say so short of rewriting the source before parsing it.
///
/// This is that way, and it is deliberately the whole of it. The parser is not
/// involved: a reference is still written down as unresolved, still waits for a
/// definition, and still loses to one that arrives — a document that defines a
/// label means what it says, and nothing outside it gets a vote. Only what is
/// left over at rendering time is offered here.
///
/// Which keeps this ignorant of what a label *is*. `[java.util.List#of()]` is
/// javadoc's syntax for a Java symbol, and resolving it needs an index of Java
/// symbols and a URL space to point into; neither is markdown's business, and a
/// markdown library that knew what a method signature looked like would be
/// carrying a language around. It asks a question and takes an answer.
@FunctionalInterface
public interface Links {

    /// Nobody knows anything: what a document renders with unless a caller says
    /// otherwise, and what leaves every reference as text.
    Links NONE = label -> null;

    /// Where `label` points, or `null` for "not mine, leave it as text".
    ///
    /// The label arrives as it was written with its whitespace collapsed — case
    /// intact, because that is not a case-insensitive question outside
    /// CommonMark — and the answer is a destination, escaped and written the way
    /// any other link's is. Answering `null` is not a failure and costs the
    /// reader nothing: the reference renders exactly as it does today.
    String destination(String label);

    /// Rewrites a destination that the document defined. The default preserves
    /// it. A document browser can map a source filename to its page URL.
    default String defined(String destination) {
        return destination;
    }
}
