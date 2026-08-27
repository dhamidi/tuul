package web.assets;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Rewriting the references inside a stylesheet.
///
/// A digested name is a promise that the content behind it never changes. A
/// stylesheet that says `url(logo.png)` breaks that promise on behalf of the
/// image: the stylesheet is cached forever, and forever it asks for a name that
/// somebody else is free to replace. Rewriting the reference to the image's own
/// digested name is what makes the promise true, and skipping it is why a cached
/// page shows last month's logo.
///
/// A reference that is already a URL — a scheme, a protocol-relative `//`, a
/// `data:` payload, or a site-absolute `/path` that belongs to some other mount
/// — is left exactly as written. Guessing what those point at would be worse
/// than doing nothing.
final class Stylesheets {

    private static final Pattern URL = Pattern.compile("url\\(\\s*(['\"]?)([^'\"()]*)\\1\\s*\\)");
    private static final Pattern IMPORT = Pattern.compile("@import\\s+(['\"])([^'\"]*)\\1");
    private static final Pattern ELSEWHERE = Pattern.compile("^(?:[a-zA-Z][a-zA-Z0-9+.\\-]*:|//|/|#).*");

    private Stylesheets() {}

    /// `from` is the logical name of the stylesheet being rewritten, because a
    /// reference is relative to the file that makes it. `resolve` answers with
    /// the URL of an asset, or nothing when there is no such asset — a reference
    /// to something that is not in the load path is left alone rather than
    /// broken, since it may well be served by something else.
    static String rewrite(String css, String from, Function<String, Optional<String>> resolve) {
        var directory = directory(from);
        var once = replace(URL, css, directory, resolve, "url(\"%s\")");
        return replace(IMPORT, once, directory, resolve, "@import \"%s\"");
    }

    private static String replace(
            Pattern pattern, String css, String directory, Function<String, Optional<String>> resolve, String form) {
        var matcher = pattern.matcher(css);
        var rewritten = new StringBuilder();
        while (matcher.find()) {
            var replacement = url(matcher.group(2), directory, resolve).map(form::formatted).orElse(matcher.group());
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(rewritten).toString();
    }

    /// A reference keeps whatever came after it. A fragment is not decoration:
    /// `icons.svg#home` picks a symbol out of a sprite, and dropping it serves
    /// the sheet instead of the icon.
    private static Optional<String> url(String reference, String directory, Function<String, Optional<String>> resolve) {
        if (reference.isEmpty() || ELSEWHERE.matcher(reference).matches()) return Optional.empty();
        var cut = cut(reference);
        var target = reference.substring(0, cut);
        var suffix = reference.substring(cut);
        return resolve.apply(normalize(directory, target)).map(url -> url + suffix);
    }

    private static int cut(String reference) {
        var question = reference.indexOf('?');
        var hash = reference.indexOf('#');
        if (question < 0 && hash < 0) return reference.length();
        if (question < 0) return hash;
        return hash < 0 ? question : Math.min(question, hash);
    }

    private static String directory(String logical) {
        var slash = logical.lastIndexOf('/');
        return slash < 0 ? "" : logical.substring(0, slash);
    }

    /// Logical names are separated by `/` whatever the file system thinks, so
    /// this walks the segments itself rather than going through
    /// [java.nio.file.Path], which would answer with backslashes on Windows.
    private static String normalize(String directory, String reference) {
        var segments = new ArrayDeque<String>();
        for (var segment : (directory.isEmpty() ? reference : directory + "/" + reference).split("/")) {
            switch (segment) {
                case "", "." -> {
                    // nothing to descend into
                }
                case ".." -> segments.pollLast();
                default -> segments.addLast(segment);
            }
        }
        return String.join("/", segments);
    }
}
