package json;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Deque;

/// Writes JSON straight to a [Writer]. Nothing is buffered: every call emits
/// its characters immediately, so a document larger than memory is fine.
///
/// Structure is the caller's job — `beginObject().name("a").value(1).endObject()`
/// — the writer only tracks enough state to place commas and colons.
public final class JsonWriter implements Closeable, Flushable {

    private final Writer out;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private boolean afterName;

    public JsonWriter(Writer out) {
        this.out = out;
    }

    public JsonWriter beginObject() throws IOException {
        separate();
        out.write('{');
        frames.push(new Frame());
        return this;
    }

    public JsonWriter endObject() throws IOException {
        frames.pop();
        out.write('}');
        return this;
    }

    public JsonWriter beginArray() throws IOException {
        separate();
        out.write('[');
        frames.push(new Frame());
        return this;
    }

    public JsonWriter endArray() throws IOException {
        frames.pop();
        out.write(']');
        return this;
    }

    public JsonWriter name(String name) throws IOException {
        separate();
        string(name);
        out.write(':');
        afterName = true;
        return this;
    }

    public JsonWriter value(String value) throws IOException {
        if (value == null) return nullValue();
        separate();
        string(value);
        return this;
    }

    public JsonWriter value(double value) throws IOException {
        separate();
        number(value);
        return this;
    }

    public JsonWriter value(boolean value) throws IOException {
        separate();
        out.write(value ? "true" : "false");
        return this;
    }

    public JsonWriter nullValue() throws IOException {
        separate();
        out.write("null");
        return this;
    }

    /// Writes an in-memory value. Prefer the structural calls above when the
    /// document is produced incrementally.
    public JsonWriter value(Json value) throws IOException {
        switch (value) {
            case Json.Null _ -> nullValue();
            case Json.Bool(var flag) -> value(flag);
            case Json.Num(var number) -> value(number);
            case Json.Str(var text) -> value(text);
            case Json.Array(var items) -> {
                beginArray();
                for (var item : items) value(item);
                endArray();
            }
            case Json.Object(var fields) -> {
                beginObject();
                for (var field : fields.entrySet()) name(field.getKey()).value(field.getValue());
                endObject();
            }
        }
        return this;
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    private void separate() throws IOException {
        if (afterName) {
            afterName = false;
            return;
        }
        var frame = frames.peek();
        if (frame == null) return;
        if (frame.count > 0) out.write(',');
        frame.count++;
    }

    private void number(double value) throws IOException {
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new JsonException("not a JSON number: " + value);
        if (value == Math.rint(value) && Math.abs(value) < 1e15) out.write(Long.toString((long) value));
        else out.write(Double.toString(value));
    }

    private void string(String value) throws IOException {
        out.write('"');
        for (var i = 0; i < value.length(); i++) escape(value.charAt(i));
        out.write('"');
    }

    private void escape(char c) throws IOException {
        switch (c) {
            case '"' -> out.write("\\\"");
            case '\\' -> out.write("\\\\");
            case '\n' -> out.write("\\n");
            case '\r' -> out.write("\\r");
            case '\t' -> out.write("\\t");
            case '\b' -> out.write("\\b");
            case '\f' -> out.write("\\f");
            default -> out.write(c < 0x20 ? "\\u%04x".formatted((int) c) : String.valueOf(c));
        }
    }

    private static final class Frame {
        private int count;
    }
}
