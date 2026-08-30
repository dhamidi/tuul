package markdown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// The visible headings in one parsed Markdown document.
///
/// Use [#of] to make navigation that points at headings written by
/// [Markdown#renderAnchored(Document,java.io.Writer)]. Each repeated heading
/// gets a different identifier. The first identifier has no numeric suffix.
public final class Outline {

    private Outline() {}

    /// One heading, with its level, visible text, and fragment identifier.
    public record Heading(int level, String title, String id) {}

    /// Returns all headings in source order.
    ///
    /// The title contains visible text. It excludes Markdown punctuation and
    /// link destinations. The identifier contains lowercase letters and
    /// digits. A hyphen replaces each other character sequence.
    public static List<Heading> of(Document document) {
        var identifiers = identifiers(document);
        var headings = new ArrayList<Heading>();
        document.walk()
                .filter(Step::entering)
                .filter(step -> step.kind() == Kind.HEADING)
                .forEach(step -> headings.add(new Heading(
                        document.at(step.node()).number(), title(document, step.node()), identifiers.get(step.node()))));
        return List.copyOf(headings);
    }

    static Map<Integer, String> identifiers(Document document) {
        var identifiers = new LinkedHashMap<Integer, String>();
        var used = new HashMap<String, Integer>();
        document.walk()
                .filter(Step::entering)
                .filter(step -> step.kind() == Kind.HEADING)
                .forEach(step -> {
                    var base = identifier(title(document, step.node()));
                    var occurrence = used.merge(base, 1, Integer::sum);
                    identifiers.put(step.node(), occurrence == 1 ? base : base + "-" + occurrence);
                });
        return Map.copyOf(identifiers);
    }

    private static String title(Document document, int heading) {
        var title = new StringBuilder();
        document.walk(heading)
                .filter(Step::entering)
                .forEach(step -> append(document.at(step.node()), title));
        return title.toString().replaceAll("\\s+", " ").strip();
    }

    private static void append(Cursor cursor, StringBuilder title) {
        switch (cursor.kind()) {
            case TEXT, CODE_SPAN -> title.append(cursor.text());
            case ESCAPE -> title.append(cursor.text().subSequence(1, 2));
            case ENTITY -> title.append(Entities.decode(cursor.text()));
            case SOFT_BREAK, HARD_BREAK -> title.append(' ');
            default -> { }
        }
    }

    private static String identifier(String title) {
        var id = new StringBuilder();
        var separator = false;
        for (var codePoint : title.toLowerCase(Locale.ROOT).codePoints().toArray()) {
            if (Character.isLetterOrDigit(codePoint)) {
                if (separator && !id.isEmpty()) id.append('-');
                id.appendCodePoint(codePoint);
                separator = false;
            } else {
                separator = true;
            }
        }
        return id.isEmpty() ? "section" : id.toString();
    }
}
