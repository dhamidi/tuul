package symbols;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import json.Json;
import markdown.Markdown;
import markdown.Outline;

/// One Markdown document that belongs to a project package: a Diataxis
/// document, or the package's `README.md`.
///
/// The filename supplies the kind, slug, and order. The first line supplies
/// the title when it starts with `# `. The body stays as Markdown source.
public record Document(String packageName, String kind, String slug, String title, String body, String source) {

    /// The kind a package's `README.md` is filed under. A README is the long
    /// overview of a package, so it comes first in a listing and has no slug.
    public static final String README = "readme";

    /// Every kind a document can be, in the order a package lists them. A name
    /// such as `web/tutorial/first` is a document name only when its second
    /// segment is one of these.
    public static final List<String> KINDS = List.of(README, "tutorial", "howto", "reference", "guide");

    private static final Pattern NAME =
            Pattern.compile("^(tutorial|howto|reference|guide|explanation)(?:-(.+))?\\.md$");

    private static final String README_FILE = "README.md";

    private static final Pattern ORDERED = Pattern.compile("^\\d+-(.+)$");

    /// Reads identity from a lowercase Markdown filename. Returns empty for a
    /// filename that is not a package document.
    public static Optional<Name> name(Path path) {
        var filename = path.getFileName().toString();
        if (filename.equals(README_FILE)) return Optional.of(new Name(README, "", filename));
        var matched = NAME.matcher(filename);
        if (!matched.matches()) return Optional.empty();

        var kind = matched.group(1).equals("explanation") ? "guide" : matched.group(1);
        var slug = matched.group(2) == null ? "" : matched.group(2);
        var ordered = ORDERED.matcher(slug);
        if (ordered.matches()) slug = ordered.group(1);
        return Optional.of(new Name(kind, slug, filename));
    }

    /// The normalized identity from one filename. `filename` preserves the
    /// source order and permits sibling-link lookup without parsing Markdown.
    public record Name(String kind, String slug, String filename) {}

    /// Returns the title from line 1. A missing heading returns a title made
    /// from the slug, or the kind name for an intro document.
    public static String title(String body, String kind, String slug) {
        var end = body.indexOf('\n');
        var first = (end < 0 ? body : body.substring(0, end)).stripTrailing();
        if (first.startsWith("# ") && !first.substring(2).isBlank()) return first.substring(2).strip();
        if (!slug.isEmpty()) return slug.replace('-', ' ');
        return switch (kind) {
            case "howto" -> "How-to";
            case README -> "README";
            default -> kind.substring(0, 1).toUpperCase(Locale.ROOT) + kind.substring(1);
        };
    }

    /// Returns the Markdown after the first-line title.
    ///
    /// The body is unchanged when line 1 is not an ATX level-one heading.
    public String content() {
        return content(body);
    }

    /// Returns `body` after its first-line ATX level-one heading.
    public static String content(String body) {
        if (!body.startsWith("# ")) return body;
        var end = body.indexOf('\n');
        return end < 0 ? "" : body.substring(end + 1);
    }

    /// Returns the level-two headings that navigate this document.
    ///
    /// This method parses Markdown when a description needs an outline. The
    /// index does not call it while it discovers or stores documents.
    public List<Section> sections() {
        return Outline.of(Markdown.parse(content())).stream()
                .filter(heading -> heading.level() == 2)
                .map(heading -> new Section(heading.title(), heading.id()))
                .toList();
    }

    /// One visible section and the fragment that identifies it.
    public record Section(String title, String anchor) {}

    /// Returns the object that `tuul docs --json` and the browser serve.
    /// `symbol` is the name that asks for this document again.
    public Json.Object describe() {
        return Json.Object.of()
                .with("symbol", symbol())
                .with("kind", kind)
                .with("package", packageName)
                .with("slug", slug)
                .with("title", title)
                .with("doc", body)
                .with("source", source);
    }

    /// Returns the indexed name and URL path without the `/symbols/` prefix.
    public String symbol() {
        return packageName + "/" + kind + (slug.isEmpty() ? "" : "/" + slug);
    }

    /// The Markdown after the title, from the first paragraph and no further.
    /// A package without a `package-info.java` says what it is for here.
    public String summary() {
        var text = content().strip();
        var end = text.indexOf("\n\n");
        return end < 0 ? text : text.substring(0, end).strip();
    }

    /// Reads a document name written as `package/kind` or `package/kind/slug`.
    /// Any other text, including a type name, returns empty.
    public static Optional<Reference> reference(String symbol) {
        var parts = symbol.split("/", -1);
        if (parts.length < 2 || parts.length > 3 || parts[0].isBlank()) return Optional.empty();
        if (!KINDS.contains(parts[1])) return Optional.empty();
        return Optional.of(new Reference(parts[0], parts[1], parts.length == 3 ? parts[2] : ""));
    }

    /// A document named from outside: the package, the kind, and the slug,
    /// which is empty for the introduction of a kind.
    public record Reference(String packageName, String kind, String slug) {}
}
