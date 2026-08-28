package markdown;

/// What a node is.
///
/// The set is closed and small on purpose: a kind is a byte in the node array,
/// and every one of them is something CommonMark names. Anything an extension
/// would add belongs in a later kind rather than in a flag on an existing one.
public enum Kind {

    /// The whole document. Node 0, always, and the only node without a parent.
    DOCUMENT(false),

    // Leaf blocks.

    PARAGRAPH(true),

    /// `# heading` or the underlined kind. Its level is the node's number.
    HEADING(true),

    /// `---`. Nothing inside it.
    BREAK(false),

    /// Indented or fenced. Its content is verbatim, so its text is the span and
    /// it has no children; a fenced block's info string is a [#INFO] child.
    CODE(true),

    /// A run of raw HTML lines, passed through as written.
    HTML_BLOCK(true),

    /// The info string of a fenced code block — `java` in ```` ```java ````.
    INFO(false),

    /// A link reference definition. It renders as nothing, and is kept because
    /// a document that contains one contains one: a reader of the DOM can see
    /// what was written, which is the difference between a parser and a
    /// renderer that forgot.
    DEFINITION(true),

    // Container blocks.

    QUOTE(true),

    /// A bullet or ordered list. Its number is the start of an ordered list and
    /// -1 for a bullet one; whether it is loose is a flag.
    LIST(true),

    ITEM(true),

    // Inlines.

    /// A run of literal characters. Its text is exactly the source span, so
    /// escapes and entities are separate nodes rather than a decoded copy.
    TEXT(false),

    /// A backslash escape — `\*`. The span covers both characters and the
    /// renderer writes the second.
    ESCAPE(false),

    /// `&amp;` or `&#42;`. The span covers the whole reference.
    ENTITY(false),

    /// A newline inside a paragraph. Renders as a newline.
    SOFT_BREAK(false),

    /// Two spaces or a backslash before a newline. Renders as `<br />`.
    HARD_BREAK(false),

    /// `` `code` ``. Verbatim, like [#CODE], with the backticks outside the
    /// span.
    CODE_SPAN(false),

    EMPHASIS(true),

    STRONG(true),

    /// An inline or reference link. Its children are its content; where it
    /// points is a [#DESTINATION] child, or a [#LABEL] child when the
    /// destination is written somewhere else in the document.
    LINK(true),

    IMAGE(true),

    /// `<https://example.com>`. One child holds the text, which is also the
    /// destination.
    AUTOLINK(true),

    /// A tag, a comment, a processing instruction — raw, as written.
    HTML_INLINE(false),

    /// Where a link points, as it was written: still escaped, still with its
    /// angle brackets if it had them. Whoever writes it out unescapes it,
    /// because that is a rendering decision and this is what the document said.
    DESTINATION(false),

    /// A link's title, without its quotes.
    TITLE(false),

    /// The label of a reference link, which is looked up when it is needed
    /// rather than when it is read — see [Document#definition].
    LABEL(false);

    private final boolean container;

    Kind(boolean container) {
        this.container = container;
    }

    /// Whether this kind may hold children. A leaf's text is its own — a
    /// [#TEXT] node is the characters it covers. A container's span is the
    /// source it encloses, and what it means is in its children.
    public boolean container() {
        return container;
    }

    public boolean inline() {
        return ordinal() >= TEXT.ordinal();
    }
}
