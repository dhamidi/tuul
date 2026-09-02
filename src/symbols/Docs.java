package symbols;

import java.io.IOException;
import java.io.Writer;
import java.text.BreakIterator;
import java.util.ArrayList;
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

    /// Describes one symbol and lists its package documents.
    ///
    /// Each item under `documents` holds `symbol`, the name to pass to
    /// [Queries#any] for the document, its `title`, and its level-two
    /// `sections`. Read a body with [Catalog#document].
    ///
    /// A package without a doc comment takes its `doc` from the first
    /// paragraph of its README, when it has one.
    public static Json.Object describe(TypeInfo type, boolean all, List<Document> documents) {
        var description = describe(type, all);
        if (type.kind() != TypeInfo.Kind.PACKAGE || documents.isEmpty()) return description;
        if (type.doc().isEmpty()) {
            description = documents.stream()
                    .filter(document -> document.kind().equals(Document.README))
                    .findFirst()
                    .map(readme -> describe(type, all).with("doc", readme.summary()))
                    .orElse(description);
        }
        return description.with("documents", Json.Array.of(documents.stream()
                .map(document -> (Json) Json.Object.of()
                        .with("symbol", document.symbol())
                        .with("kind", document.kind())
                        .with("slug", document.slug())
                        .with("title", document.title())
                        .with("sections", Json.Array.of(document.sections().stream()
                                .map(section -> (Json) Json.Object.of()
                                        .with("title", section.title())
                                        .with("anchor", section.anchor()))
                                .toList())))
                .toList()));
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
        if (!description.string("member", "").isEmpty()) {
            member(description, out);
            return;
        }
        out.write(head(description));
        out.write("\n");
        wrap(summary(description.string("doc", "")), "  ", out);
        line(description, "extends", "  extends ", out);
        line(description, "implements", "  implements ", out);
        line(description, "permits", "  permits ", out);
        where(description, out);
        if (grouping(description)) {
            documents(description, out);
            contents(description, out);
        }
        else line(description, "nested", "  declares ", out);
        members(description, "methods", out);
        members(description, "fields", out);
    }

    /// One member asked for by name: the type and member on the first line,
    /// then every overload with its line number and its whole comment.
    private static void member(Json.Object description, Writer out) throws IOException {
        out.write(description.string("class", "?") + "#" + description.string("member", "") + "\n");
        where(description, out);
        for (var section : List.of("methods", "fields")) {
            for (var value : description.list(section)) {
                if (!(value instanceof Json.Object entry)) continue;
                out.write("\n  " + Signatures.shorten(entry.string("signature", "")));
                var line = number(entry, "line");
                out.write((line > 0 ? "  :" + line : "") + "\n");
                wrap(entry.string("doc", ""), "      ", out);
                for (var tag : entry.list("tags")) {
                    if (tag instanceof Json.Object held) fold(line(held), "      ", "          ", out);
                }
            }
        }
    }

    /// Prints package documents in full, each after a line that names it and
    /// its file. Pass the `documents` list of a description made with
    /// `--documents`. An item without a body is skipped.
    public static void documents(List<Json> documents, Writer out) throws IOException {
        for (var value : documents) {
            if (!(value instanceof Json.Object document) || !(document.get("doc") instanceof Json.Str(var body))) {
                continue;
            }
            out.write("\n--- " + document.string("symbol", "") + " (" + document.string("source", "") + ") ---\n");
            out.write(body);
            if (!body.endsWith("\n")) out.write("\n");
        }
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

    /// The documents of a package, one per line: the name to pass to
    /// `tuul docs`, then the title.
    private static void documents(Json.Object description, Writer out) throws IOException {
        var documents = description.list("documents");
        if (documents.isEmpty()) return;
        out.write("\n");
        for (var value : documents) {
            if (!(value instanceof Json.Object document)) continue;
            out.write("  " + document.string("symbol", "") + "  " + document.string("title", "") + "\n");
        }
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

    /// Prints the `groups` of a search: the prefix, then the group's matches
    /// as [#matches] prints them, with a blank line between groups.
    public static void groups(List<Json> groups, Writer out) throws IOException {
        var first = true;
        for (var value : groups) {
            if (!(value instanceof Json.Object group)) continue;
            if (!first) out.write("\n");
            first = false;
            out.write(group.string("prefix", "") + "\n");
            matches(group.list("matches"), out);
        }
    }

    /// Prints the matches of one group: the symbol, its origin in brackets
    /// unless it is the project, and the first sentence of its comment.
    public static void matches(List<Json> matches, Writer out) throws IOException {
        for (var match : matches) {
            if (!(match instanceof Json.Object found)) continue;
            var origin = found.string("origin", "");
            out.write("  " + found.string("symbol", "") + (origin.isEmpty() || origin.equals("project")
                    ? "" : "  [" + origin + "]") + "\n");
            wrap(summary(found.string("doc", "")), "      ", out);
        }
    }

    /// What there is, before anything has been named: each root and what it
    /// holds, one name per line.
    ///
    /// Unshortened, because a listing is a set of names to look up and
    /// `java.util.concurrent` shortened to `concurrent` names nothing anybody
    /// can ask about. That is the same reason a package page lists what it
    /// holds in full.
    public static void roots(List<Catalog.Root> roots, Writer out) throws IOException {
        var first = true;
        for (var root : roots) {
            if (!first) out.write("\n");
            first = false;
            out.write(root.label() + "\n");
            for (var name : root.contents()) out.write("  " + name + "\n");
        }
    }

    /// The same listing as a message, which is what the browser renders and
    /// `--json` prints.
    public static Json.Object describe(List<Catalog.Root> roots) {
        var described = new ArrayList<Json>();
        for (var root : roots) {
            described.add(Json.Object.of()
                    .with("root", root.name())
                    .with("label", root.label())
                    .with("contains", Json.Array.strings(root.contents())));
        }
        return Json.Object.of().with("roots", Json.Array.of(described));
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
