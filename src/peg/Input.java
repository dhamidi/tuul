package peg;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/// What a parse walks over: a stream of elements, pulled one at a time and
/// remembered only as far as the parse has actually looked.
///
/// A parser has to be able to go back — an ordered choice that fails halfway
/// through its first alternative starts the second one where the first began —
/// so what has been seen is kept. What has *not* been seen is not read, which
/// is the difference between parsing a stream and parsing a buffer that
/// happened to arrive from one.
///
/// A position is an index. Positions are ordinary ints rather than objects, so
/// backtracking costs nothing.
public final class Input<T> {

    private final Iterator<T> source;
    private final List<T> seen = new ArrayList<>();

    private Input(Iterator<T> source) {
        this.source = source;
    }

    public static <T> Input<T> of(Iterator<T> source) {
        return new Input<>(source);
    }

    public static <T> Input<T> of(Iterable<T> source) {
        return new Input<>(source.iterator());
    }

    /// A stream of characters, read as the parse asks for them. Boxing a char
    /// per element is the price of one parser that works over anything.
    public static Input<Character> characters(Reader reader) {
        return new Input<>(new Characters(reader));
    }

    public static Input<Character> characters(String text) {
        return of(text.chars().mapToObj(character -> (char) character).toList());
    }

    /// Whether there is an element at this position, reading far enough to find
    /// out and no further.
    public boolean has(int position) {
        while (seen.size() <= position && source.hasNext()) seen.add(source.next());
        return position < seen.size();
    }

    public T at(int position) {
        if (!has(position)) throw new NoSuchElementException("nothing at " + position);
        return seen.get(position);
    }

    /// How much has been read. Only an error message should care.
    public int read() {
        return seen.size();
    }

    private static final class Characters implements Iterator<Character> {

        private final Reader reader;
        private int next = -2;

        private Characters(Reader reader) {
            this.reader = reader;
        }

        @Override
        public boolean hasNext() {
            return peek() >= 0;
        }

        @Override
        public Character next() {
            var character = peek();
            if (character < 0) throw new NoSuchElementException("end of input");
            next = -2;
            return (char) character;
        }

        private int peek() {
            if (next != -2) return next;
            try {
                next = reader.read();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return next;
        }
    }
}
