package symbols;

import java.io.IOException;
import java.io.Writer;
import java.text.BreakIterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import json.Json;

/// Renders a symbol, for a reader or for a program.
///
/// Both renderings come from the same JSON description, so `tuul docs X` and
/// `tuul docs X --json` can never disagree about what X is. Text shows the
/// first sentence of a doc comment, because a screen is narrow; `--json`
/// carries the whole thing, because an agent is not.
public final class Docs {

    private static final int WIDTH = 78;

    public static final List<String> SECTIONS =
            List.of("doc", "source", "extends", "implements", "permits", "nested", "methods", "fields");

    private Docs() {}

    public static Json.Object describe(TypeInfo type, boolean all) {
        return Json.Object.of()
                .with("class", type.name())
                .with("kind", type.kind().keyword())
                .with("doc", type.doc())
                .with("modifiers", Json.Array.strings(type.modifiers()))
                .with("typeParameters", Json.Array.strings(type.typeParameters()))
                .with("extends", type.superclass())
                .with("implements", Json.Array.strings(type.interfaces()))
                .with("permits", Json.Array.strings(type.permits()))
                .with("nested", Json.Array.strings(type.nested()))
                .with("source", type.source())
                .with("line", Json.of(type.line()))
                .with("tags", tags(type.tags()))
                .with("methods", methods(type, all))
                .with("fields", fields(type, all));
    }

    /// Keeps only the named sections — `--methods`, `--implements` — or
    /// everything when none are named. Asking for the doc includes its block
    /// tags: `@param` and `@return` are part of the comment, not a section of
    /// their own.
    public static Json.Object select(Json.Object description, Set<String> sections) {
        if (sections.isEmpty()) return description;
        var selected = Json.Object.of();
        for (var section : SECTIONS) {
            if (!sections.contains(section)) continue;
            selected = selected.with(section, description.get(section));
            if (section.equals("doc")) selected = selected.with("tags", description.get("tags"));
        }
        return selected;
    }

    public static void text(Json.Object description, Set<String> sections, Writer out) throws IOException {
        if (!sections.isEmpty()) {
            for (var section : SECTIONS) {
                if (sections.contains(section)) lines(description, section, out);
            }
            return;
        }
        out.write(head(description));
        out.write("\n");
        wrap(summary(description.string("doc", "")), "  ", out);
        line(description, "extends", "  extends ", out);
        line(description, "implements", "  implements ", out);
        line(description, "permits", "  permits ", out);
        where(description, out);
        if (grouping(description)) contents(description, out);
        else line(description, "nested", "  declares ", out);
        members(description, "methods", out);
        members(description, "fields", out);
    }

    /// A type *declares* the types written in its body; a package or a module
    /// *contains* what happens to be filed under it. Not the same relationship,
    /// and they should not read as though they were.
    private static boolean grouping(Json.Object description) {
        var kind = description.string("kind", "class");
        return kind.equals("package") || kind.equals("module");
    }

    /// What a package or a module holds, one per line and written out in full.
    ///
    /// Members are listed this way and this is the same question. Shortening is
    /// wrong here for a reason that only shows up on a package: it exists to
    /// take `java.util.List` down to `List` inside a signature, and applied to a
    /// list of package names it takes `java.util.concurrent` down to
    /// `concurrent`, which names nothing a reader could look up.
    private static void contents(Json.Object description, Writer out) throws IOException {
        var held = strings(description, "nested");
        if (held.isEmpty()) return;
        out.write("\n");
        for (var name : held) out.write("  " + name + "\n");
    }

    /// Where the declaration is written, when it is known. A project's source
    /// is a file somebody can open; the other two name an entry inside an
    /// archive, which is still the answer to *where does this come from*.
    private static void where(Json.Object description, Writer out) throws IOException {
        var source = description.string("source", "");
        if (source.isEmpty()) return;
        var line = number(description, "line");
        out.write("  at " + source + (line > 0 ? ":" + line : "") + "\n");
    }

    private static long number(Json.Object description, String name) {
        return description.get(name) instanceof Json.Num(var value) ? (long) value : 0;
    }

    private static String head(Json.Object description) {
        var modifiers = strings(description, "modifiers");
        var parameters = strings(description, "typeParameters");
        var head = String.join(" ", modifiers)
                + (modifiers.isEmpty() ? "" : " ")
                + description.string("kind", "class") + " "
                + description.string("class", "?");
        return parameters.isEmpty() ? head : head + "<" + Signatures.shorten(String.join(", ", parameters)) + ">";
    }

    private static void line(Json.Object description, String section, String prefix, Writer out) throws IOException {
        var values = strings(description, section);
        if (values.isEmpty()) return;
        out.write(prefix + Signatures.shorten(String.join(", ", values)) + "\n");
    }

    private static void members(Json.Object description, String section, Writer out) throws IOException {
        var members = description.list(section);
        if (members.isEmpty()) return;
        out.write("\n");
        for (var member : members) {
            if (!(member instanceof Json.Object entry)) continue;
            out.write("  " + Signatures.shorten(entry.string("signature", "")) + "\n");
            var doc = summary(entry.string("doc", ""));
            if (doc.isEmpty()) continue;
            wrap(doc, "      ", out);
            out.write("\n");
        }
    }

    /// One value per line, with no header — what `--implements` and `--methods`
    /// print on their own. `--doc` prints the whole comment, not a summary of
    /// it: asking for the doc is asking for all of it.
    private static void lines(Json.Object description, String section, Writer out) throws IOException {
        if (section.equals("doc")) {
            wrap(description.string("doc", ""), "", out);
            block(description, out);
            return;
        }
        if (section.equals("source")) {
            var source = description.string("source", "");
            if (source.isEmpty()) return;
            var line = number(description, "line");
            out.write(source + (line > 0 ? ":" + line : "") + "\n");
            return;
        }
        switch (description.get(section)) {
            case Json.Str(var text) when !text.isEmpty() -> out.write(Signatures.shorten(text) + "\n");
            case Json.Array(var items) -> {
                for (var item : items) out.write(Signatures.shorten(signature(item)) + "\n");
            }
            case null, default -> {}
        }
    }

    /// `@param`, `@return`, `@throws` — one per line, wrapped under itself.
    private static void block(Json.Object description, Writer out) throws IOException {
        var tags = description.list("tags");
        if (tags.isEmpty()) return;
        out.write("\n");
        for (var tag : tags) {
            if (tag instanceof Json.Object entry) fold(line(entry), "", "    ", out);
        }
    }

    private static String line(Json.Object tag) {
        var head = ("@" + tag.string("tag", "") + " " + tag.string("name", "")).strip();
        var text = tag.string("text", "");
        return text.isEmpty() ? head : head + " " + text;
    }

    /// What a search found: the symbol, and the first thing it says about
    /// itself. A list of names with no summaries is a list nobody can choose
    /// from.
    public static void matches(List<Json> matches, Writer out) throws IOException {
        for (var match : matches) {
            if (!(match instanceof Json.Object found)) continue;
            out.write(found.string("symbol", "") + "\n");
            wrap(summary(found.string("doc", "")), "      ", out);
        }
    }

    /// The first sentence, the way javadoc means it.
    private static String summary(String doc) {
        if (doc.isEmpty()) return doc;
        var sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(doc);
        var end = sentences.next();
        return end == BreakIterator.DONE ? doc : doc.substring(0, end).trim();
    }

    /// Keeps the author's line breaks — a doc comment with an example in it
    /// only reads as one with the breaks intact — and folds the lines that are
    /// too long to fit.
    private static void wrap(String text, String indent, Writer out) throws IOException {
        if (text.isEmpty()) return;
        for (var line : text.split("\n", -1)) {
            if (line.isBlank()) out.write("\n");
            else fold(line, indent, indent, out);
        }
    }

    private static void fold(String text, String first, String rest, Writer out) throws IOException {
        var indent = first;
        var line = new StringBuilder();
        for (var word : text.stripTrailing().split(" ")) {
            if (!line.isEmpty() && indent.length() + line.length() + 1 + word.length() > WIDTH) {
                out.write(indent + line + "\n");
                line.setLength(0);
                indent = rest;
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.write(indent + line + "\n");
    }

    private static String signature(Json item) {
        return switch (item) {
            case Json.Str(var text) -> text;
            case Json.Object entry -> entry.string("signature", "");
            default -> "";
        };
    }

    private static List<String> strings(Json.Object description, String name) {
        var value = description.get(name);
        if (value instanceof Json.Str(var text)) return text.isEmpty() ? List.of() : List.of(text);
        return description.list(name).stream().map(Docs::signature).filter(text -> !text.isEmpty()).toList();
    }

    private static Json tags(List<TypeInfo.Tag> tags) {
        return Json.Array.of(tags.stream()
                .map(tag -> (Json) Json.Object.of()
                        .with("tag", tag.tag())
                        .with("name", tag.name())
                        .with("text", tag.text()))
                .toList());
    }

    private static Json methods(TypeInfo type, boolean all) {
        return Json.Array.of(type.methods().stream()
                .filter(method -> all || method.api())
                .map(method -> (Json) Json.Object.of()
                        .with("name", method.name())
                        .with("returns", method.returns())
                        .with("parameters", Json.Array.strings(method.parameters().stream().map(TypeInfo.Parameter::text).toList()))
                        .with("modifiers", Json.Array.strings(method.modifiers()))
                        .with("signature", method.signature())
                        .with("line", Json.of(method.line()))
                        .with("doc", method.doc())
                        .with("tags", tags(method.tags())))
                .toList());
    }

    private static Json fields(TypeInfo type, boolean all) {
        return Json.Array.of(type.fields().stream()
                .filter(field -> all || field.api())
                .map(field -> (Json) Json.Object.of()
                        .with("name", field.name())
                        .with("type", field.type())
                        .with("modifiers", Json.Array.strings(field.modifiers()))
                        .with("signature", field.signature())
                        .with("line", Json.of(field.line()))
                        .with("doc", field.doc())
                        .with("tags", tags(field.tags())))
                .toList());
    }
}
