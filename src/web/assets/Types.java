package web.assets;

import java.util.Locale;
import java.util.Map;

/// What to call a file when sending it.
///
/// A table rather than [java.nio.file.Files#probeContentType], which asks the
/// operating system and gets a different answer on each one — on a stock Linux
/// it answers `null` for a stylesheet, and a browser will not apply a stylesheet
/// it was handed as `application/octet-stream`. A module is worse: the wrong
/// type is a hard error, not a shrug.
final class Types {

    private static final String FALLBACK = "application/octet-stream";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("map", "application/json"),
            Map.entry("json", "application/json"),
            Map.entry("html", "text/html"),
            Map.entry("txt", "text/plain"),
            Map.entry("xml", "application/xml"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"));

    private Types() {}

    /// The type of a file, by its extension, with a charset on the ones that are
    /// text — everything this serves is written as UTF-8, so saying otherwise
    /// would be a lie a browser acts on.
    static String of(String name) {
        var type = TYPES.getOrDefault(extension(name), FALLBACK);
        return text(type) ? type + "; charset=utf-8" : type;
    }

    /// Whether a type is one this pipeline may rewrite. Only stylesheets are,
    /// today; the question is asked here so the answer lives with the types.
    static boolean stylesheet(String name) {
        return extension(name).equals("css");
    }

    private static boolean text(String type) {
        return type.startsWith("text/") || type.equals("application/json") || type.equals("application/xml");
    }

    private static String extension(String name) {
        var dot = name.lastIndexOf('.');
        var slash = name.lastIndexOf('/');
        return dot < 0 || dot < slash ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
