package markdown;

import java.util.Arrays;

/// The content of one leaf block, as the spans of source it is made of.
///
/// A paragraph inside a block quote is not one run of characters: `> foo` and
/// `> bar` contribute `foo` and `bar`, and the `> ` between them belongs to the
/// quote rather than to the paragraph. Copying the two together into a string
/// would be the easy answer and would double what a document costs, so the
/// content is kept as the spans it came from and read through this.
///
/// It behaves as the concatenation of those spans, joined by newlines, and
/// [#source] maps any position in that concatenation back to where it came
/// from — which is how an inline node ends up with a span that points at the
/// document rather than at a copy of it.
final class Segments implements CharSequence {

    private final CharSequence source;
    private int[] spans = new int[16];
    private int count;
    private int length = -1;

    Segments(CharSequence source) {
        this.source = source;
    }

    void add(int start, int end) {
        if (count * 2 == spans.length) spans = Arrays.copyOf(spans, spans.length * 2);
        spans[count * 2] = start;
        spans[count * 2 + 1] = end;
        count++;
        length = -1;
    }

    boolean empty() {
        return count == 0;
    }

    int lines() {
        return count;
    }

    int lineStart(int line) {
        return spans[line * 2];
    }

    int lineEnd(int line) {
        return spans[line * 2 + 1];
    }

    /// Drops the first `lines` lines — how a paragraph gives up the link
    /// reference definitions written at the top of it.
    void skip(int lines) {
        System.arraycopy(spans, lines * 2, spans, 0, (count - lines) * 2);
        count -= lines;
        length = -1;
    }

    @Override
    public int length() {
        if (length >= 0) return length;
        var total = 0;
        for (var line = 0; line < count; line++) total += spans[line * 2 + 1] - spans[line * 2] + 1;
        return length = Math.max(0, total - 1);
    }

    @Override
    public char charAt(int index) {
        var at = index;
        for (var line = 0; line < count; line++) {
            var width = spans[line * 2 + 1] - spans[line * 2];
            if (at < width) return source.charAt(spans[line * 2] + at);
            if (at == width) return '\n';
            at -= width + 1;
        }
        throw new IndexOutOfBoundsException(index);
    }

    /// Where a position in this content is in the source. A newline between two
    /// lines answers with the end of the line before it, since that is where it
    /// was written.
    int source(int index) {
        var at = index;
        for (var line = 0; line < count; line++) {
            var width = spans[line * 2 + 1] - spans[line * 2];
            if (at <= width) return spans[line * 2] + at;
            at -= width + 1;
        }
        return count == 0 ? 0 : spans[(count - 1) * 2 + 1];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        var text = new StringBuilder(end - start);
        for (var at = start; at < end; at++) text.append(charAt(at));
        return text;
    }

    @Override
    public String toString() {
        return subSequence(0, length()).toString();
    }
}
