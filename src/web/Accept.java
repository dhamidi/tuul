package web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/// What a client said it would take, in the order it meant.
///
/// `Accept: text/html, application/xml;q=0.9, */*;q=0.8` is three media ranges
/// with three qualities, and answering it correctly is not a matter of looking
/// for a substring. A range may be a wildcard, a quality of zero means *not
/// this*, and the range that decides a type's quality is the most specific one
/// that matches it — `text/*;q=0.2` does not lower `text/html;q=1` just by
/// being present.
///
/// Getting this wrong is quiet: a handler that checks `contains("html")` works
/// for years and then answers a Turbo Stream request with a full page, or an
/// `Accept: */*;q=0` with a body it explicitly refused.
public record Accept(List<Range> ranges) {

    /// One entry: a media range and the quality attached to it.
    public record Range(String type, String subtype, double quality) {

        /// How closely this range names a thing. A range that names both halves
        /// beats one that names the type, which beats the one that names
        /// nothing — which is the rule that decides whose quality applies.
        public int specificity() {
            if (type.equals("*")) return 0;
            return subtype.equals("*") ? 1 : 2;
        }

        public boolean matches(String media) {
            var slash = media.indexOf('/');
            var wanted = slash < 0 ? media : media.substring(0, slash);
            var wantedSub = slash < 0 ? "" : media.substring(slash + 1);
            if (!type.equals("*") && !type.equalsIgnoreCase(wanted)) return false;
            return subtype.equals("*") || subtype.equalsIgnoreCase(wantedSub);
        }
    }

    /// Everything, which is what a request with no `Accept` means. A client
    /// that did not say has not refused anything.
    public static final Accept ANYTHING = new Accept(List.of(new Range("*", "*", 1)));

    public Accept {
        ranges = List.copyOf(ranges);
    }

    public static Accept of(Request request) {
        return parse(request.header("Accept").orElse(""));
    }

    public static Accept parse(String header) {
        if (header == null || header.isBlank()) return ANYTHING;
        var ranges = new ArrayList<Range>();
        for (var entry : split(header)) {
            var range = range(entry);
            if (range != null) ranges.add(range);
        }
        return ranges.isEmpty() ? ANYTHING : new Accept(ranges);
    }

    /// What this client thinks of that type: 1 by default, 0 for something it
    /// has refused, and whatever the most specific matching range said
    /// otherwise.
    public double quality(String media) {
        return ranges.stream()
                .filter(range -> range.matches(media))
                .max((a, b) -> Integer.compare(a.specificity(), b.specificity()))
                .map(Range::quality)
                .orElse(0.0);
    }

    public boolean accepts(String media) {
        return quality(media) > 0;
    }

    /// The best of what this server can produce, or nothing when the client
    /// will take none of it.
    ///
    /// Ties go to the earlier offer, because the order a server lists what it
    /// can produce is the order it prefers them — a client that says it has no
    /// preference should not get whichever one a hash map happened to yield.
    public Optional<String> best(List<String> offers) {
        String best = null;
        var quality = 0.0;
        for (var offer : offers) {
            var q = quality(offer);
            if (q > quality) {
                quality = q;
                best = offer;
            }
        }
        return Optional.ofNullable(best);
    }

    public Optional<String> best(String... offers) {
        return best(List.of(offers));
    }

    /// Commas separate entries, except inside a quoted parameter — where a
    /// comma is a comma. Rare, and the reason a split on `,` is not enough.
    private static List<String> split(String header) {
        var entries = new ArrayList<String>();
        var entry = new StringBuilder();
        var quoted = false;
        for (var character : header.toCharArray()) {
            if (character == '"') quoted = !quoted;
            if (character == ',' && !quoted) {
                entries.add(entry.toString());
                entry.setLength(0);
                continue;
            }
            entry.append(character);
        }
        entries.add(entry.toString());
        return entries;
    }

    private static Range range(String entry) {
        var parts = entry.split(";");
        var media = parts[0].strip().toLowerCase(Locale.ROOT);
        if (media.isEmpty()) return null;
        var slash = media.indexOf('/');
        var type = slash < 0 ? media : media.substring(0, slash);
        var subtype = slash < 0 ? "*" : media.substring(slash + 1).strip();
        return new Range(type, subtype.isEmpty() ? "*" : subtype, quality(parts));
    }

    /// The `q` parameter, and only the first one — anything after it is an
    /// accept extension rather than part of the range.
    private static double quality(String[] parts) {
        for (var i = 1; i < parts.length; i++) {
            var parameter = parts[i].strip();
            if (!parameter.regionMatches(true, 0, "q=", 0, 2)) continue;
            try {
                return Math.clamp(Double.parseDouble(parameter.substring(2).strip()), 0, 1);
            } catch (NumberFormatException malformed) {
                return 1;
            }
        }
        return 1;
    }
}
