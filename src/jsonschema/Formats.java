package jsonschema;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.List;
import json.Json;

/// The `format` keyword, in both of the ways the specification defines it.
///
/// The default vocabulary is **format-annotation**: `format` records what the
/// author said and asserts nothing. A schema that says `"format": "email"`
/// gets an annotation and any string passes. The specification made this the
/// default on purpose, because implementations disagreed about what an email
/// address is and schemas silently meant different things on different stacks.
///
/// The other vocabulary is **format-assertion**, and this store knows it too. A
/// caller whose meta-schema names it gets a `format` that fails a string it does
/// not recognise. A caller who keeps the standard meta-schema calls
/// [Store#asserting()], which swaps the handler under the format-annotation URI
/// for the asserting one.
///
/// A format name the store does not know never fails an instance, in either
/// vocabulary. The specification requires that, and it is what makes
/// [Store#format(Format)] safe to use for a name of your own.
public final class Formats {

    static final URI ANNOTATION = URI.create("https://json-schema.org/draft/2020-12/vocab/format-annotation");
    static final URI ASSERTION = URI.create("https://json-schema.org/draft/2020-12/vocab/format-assertion");

    private static final String LABEL = "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";

    private Formats() {}

    /// The vocabulary that only annotates. This is the default.
    public static Vocabulary annotation() {
        return of(ANNOTATION, false);
    }

    /// The vocabulary the specification defines for assertion, under its own URI.
    public static Vocabulary assertion() {
        return of(ASSERTION, true);
    }

    /// The asserting handler, registered under the format-annotation URI, for a
    /// caller who wants assertion without writing a meta-schema.
    public static Vocabulary asserting() {
        return of(ANNOTATION, true);
    }

    private static Vocabulary of(URI uri, boolean assertion) {
        return Vocabulary.of(uri, Keyword.of("format", (value, instance, context) -> {
            if (!(value instanceof Json.Str(var name))) return;
            context.annotate(Json.of(name));
            if (!assertion || !(instance instanceof Json.Str(var text))) return;
            var format = context.store().format(name);
            if (format == null || format.matches(text)) return;
            context.error("the string is not a valid " + name);
        }));
    }

    /// The formats this package implements. A caller adds their own with
    /// [Store#format(Format)], and one of theirs with the same name wins.
    public static List<Format> all() {
        return List.of(
                Format.of("date-time", Formats::dateTime),
                Format.of("date", Formats::date),
                Format.of("time", Formats::time),
                Format.of("duration", text -> Patterns.of(
                        "^P(?!$)(\\d+Y)?(\\d+M)?(\\d+W)?(\\d+D)?(T(?!$)(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?$")
                        .matcher(text).matches()),
                Format.of("email", Formats::email),
                Format.of("hostname", text -> Patterns.of("^" + LABEL + "(\\." + LABEL + ")*\\.?$")
                        .matcher(text).matches()),
                Format.of("ipv4", text -> Patterns.of("^" + OCTET + "(\\." + OCTET + "){3}$")
                        .matcher(text).matches()),
                Format.of("ipv6", Formats::ipv6),
                Format.of("uri", Formats::uri),
                Format.of("uri-reference", Formats::uriReference),
                Format.of("uuid", text -> Patterns.of(
                        "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
                        .matcher(text).matches()),
                Format.of("regex", Patterns::valid),
                Format.of("json-pointer", text -> Patterns.of("^(/([^~]|~[01])*)*$").matcher(text).matches()),
                Format.of("relative-json-pointer",
                        text -> Patterns.of("^(0|[1-9][0-9]*)(#|(/([^~]|~[01])*)*)$").matcher(text).matches()));
    }

    private static boolean dateTime(String text) {
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (RuntimeException broken) {
            return false;
        }
    }

    private static boolean date(String text) {
        if (text.length() != 10) return false;
        try {
            LocalDate.parse(text);
            return true;
        } catch (RuntimeException broken) {
            return false;
        }
    }

    /// RFC 3339 asks a time to carry an offset, so `10:05:08` alone is not one.
    private static boolean time(String text) {
        try {
            OffsetTime.parse(text);
            return true;
        } catch (RuntimeException broken) {
            return false;
        }
    }

    /// One `@`, something before it, and a hostname after it.
    ///
    /// RFC 5321 allows far more than this, including quoted local parts and
    /// address literals. A stricter test rejects addresses that work, and a
    /// looser one accepts anything, so this sits where most schemas mean it to.
    private static boolean email(String text) {
        var at = text.lastIndexOf('@');
        if (at <= 0 || at == text.length() - 1) return false;
        var local = text.substring(0, at);
        var host = text.substring(at + 1);
        if (local.contains(" ") || local.startsWith(".") || local.endsWith(".") || local.contains("..")) return false;
        return Patterns.of("^" + LABEL + "(\\." + LABEL + ")*$").matcher(host).matches();
    }

    /// Eight groups of hexadecimal, with one `::` allowed to stand for the zero
    /// groups, and an IPv4 address allowed in the last two groups.
    private static boolean ipv6(String text) {
        var doubled = text.indexOf("::");
        if (doubled >= 0 && text.indexOf("::", doubled + 1) >= 0) return false;
        var body = text;
        var tail = 0;
        var dot = body.lastIndexOf('.');
        if (dot >= 0) {
            var colon = body.lastIndexOf(':');
            if (colon < 0) return false;
            var four = body.substring(colon + 1);
            if (!Patterns.of("^" + OCTET + "(\\." + OCTET + "){3}$").matcher(four).matches()) return false;
            body = body.substring(0, colon);
            tail = 2;
            if (doubled < 0 && !body.contains(":")) return false;
        }
        var groups = 0;
        for (var part : body.split(":", -1)) {
            if (part.isEmpty()) continue;
            if (!Patterns.of("^[0-9A-Fa-f]{1,4}$").matcher(part).matches()) return false;
            groups++;
        }
        var total = groups + tail;
        if (doubled >= 0) return total <= 7;
        if (tail > 0) return total == 8 && !body.endsWith(":");
        return total == 8 && !body.startsWith(":") && !body.endsWith(":") && !body.contains("::");
    }

    private static boolean uri(String text) {
        try {
            return new java.net.URI(text).isAbsolute();
        } catch (java.net.URISyntaxException broken) {
            return false;
        }
    }

    private static boolean uriReference(String text) {
        try {
            new java.net.URI(text);
            return true;
        } catch (java.net.URISyntaxException broken) {
            return false;
        }
    }
}
