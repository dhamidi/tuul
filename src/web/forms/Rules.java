package web.forms;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import json.Json;

/// The rules worth having ready, and a way of writing more.
///
/// Each one carries the message it fails with, because a rule that says
/// `false` leaves rendering to invent an explanation, and the invented ones are
/// always worse than the one the person who wrote the rule would have given.
public final class Rules {

    private Rules() {}

    /// At least this many characters, counted the way a person counts them —
    /// by code point, so an emoji is one character rather than two.
    public static Rule least(int characters) {
        return text("must be at least " + characters + " characters",
                value -> value.codePointCount(0, value.length()) >= characters);
    }

    public static Rule most(int characters) {
        return text("must be at most " + characters + " characters",
                value -> value.codePointCount(0, value.length()) <= characters);
    }

    /// Matches, in full — an unanchored pattern that matches part of a value is
    /// almost never what the person writing it meant.
    public static Rule matching(String pattern, String message) {
        var compiled = Pattern.compile(pattern);
        return text(message, value -> compiled.matcher(value).matches());
    }

    /// Something that looks like an address. Deliberately not RFC 5322: the
    /// only test that matters is whether mail arrives, so anything stricter
    /// than "one @ with something either side" turns real addresses away for
    /// nothing.
    public static Rule email() {
        return matching("[^@\\s]+@[^@\\s]+\\.[^@\\s]+", "must be an email address");
    }

    public static Rule from(long least) {
        return number("must be at least " + least, value -> value >= least);
    }

    public static Rule upTo(long most) {
        return number("must be at most " + most, value -> value <= most);
    }

    public static Rule between(long least, long most) {
        return number("must be between " + least + " and " + most, value -> value >= least && value <= most);
    }

    /// One of these, and nothing else. A select element already offers only
    /// what it lists, which is exactly why this is needed: a form is what
    /// arrives, not what was rendered.
    public static Rule oneOf(String... allowed) {
        var values = List.of(allowed);
        return text("is not one of " + String.join(", ", values), values::contains);
    }

    /// A rule over the text of a value, whatever kind it is — a number renders
    /// as its digits here, which is what a length check would want anyway.
    public static Rule text(String message, java.util.function.Predicate<String> ok) {
        return value -> ok.test(plain(value)) ? Optional.empty() : Optional.of(message);
    }

    /// A rule over a number. A value that is not one passes, because saying so
    /// is coercion's job and it has already said it.
    public static Rule number(String message, java.util.function.LongPredicate ok) {
        return value -> !(value instanceof Json.Num(var number)) || ok.test((long) number)
                ? Optional.empty()
                : Optional.of(message);
    }

    /// The value as text, which is what most rules want to look at.
    static String plain(Json value) {
        return switch (value) {
            case Json.Str(var text) -> text;
            case Json.Num(var number) -> number == Math.rint(number) ? String.valueOf((long) number) : String.valueOf(number);
            case Json.Bool(var flag) -> String.valueOf(flag);
            case null, default -> "";
        };
    }
}
