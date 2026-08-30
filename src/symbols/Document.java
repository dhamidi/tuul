package symbols;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import json.Json;

/// One Diataxis document that belongs to a project package.
///
/// The filename supplies the kind, slug, and order. The first line supplies
/// the title when it starts with `# `. The body stays as Markdown source.
public record Document(String packageName, String kind, String slug, String title, String body, String source) {

    private static final Pattern NAME =
            Pattern.compile("^(tutorial|howto|reference|guide|explanation)(?:-(.+))?\\.md$");

    private static final Pattern ORDERED = Pattern.compile("^\\d+-(.+)$");

    /// Reads identity from a lowercase Markdown filename. Returns empty for a
    /// filename that is not a package document.
    public static Optional<Name> name(Path path) {
        var filename = path.getFileName().toString();
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
            default -> kind.substring(0, 1).toUpperCase(Locale.ROOT) + kind.substring(1);
        };
    }

    /// Returns the object that `tuul docs --json` and the browser serve.
    public Json.Object describe() {
        return Json.Object.of()
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
}
