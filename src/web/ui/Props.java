package web.ui;

import java.util.List;
import java.util.Set;
import json.Json;

/// What a component was configured with.
///
/// Props are a JSON object because everything else that crosses a boundary in
/// this architecture is one: a message is a JSON object, a form captures into
/// one, and a page's state is one. A component configured the same way can be
/// handed its props from any of them without a translation step.
///
/// **A component refuses a prop it does not understand.** `Props.of("varaint",
/// "solid")` is a typo, and a design system that ignores it renders something
/// subtly wrong that nobody notices until they look closely at the screen. The
/// alternative — failing where the mistake is written, naming what was
/// accepted — costs one line per component and finds it immediately.
public record Props(Json.Object values) {

    public static final Props NONE = new Props(Json.Object.of());

    public Props {
        java.util.Objects.requireNonNull(values, "values");
    }

    public static Props of(Json.Object values) {
        return new Props(values);
    }

    /// Props at a call site, which is where most of them are written: `of("tone",
    /// "muted", "gap", "lg")`.
    public static Props of(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new HtmlException("props come in pairs, and this one has " + pairs.length + " parts");
        }
        var values = Json.Object.of();
        for (var i = 0; i < pairs.length; i += 2) values = values.with(pairs[i], pairs[i + 1]);
        return new Props(values);
    }

    /// Props that are just present — `of().on("divided")` rather than
    /// `of("divided", "true")`, since a flag's value is that it was mentioned.
    public Props on(String... flags) {
        var next = values;
        for (var flag : flags) next = next.with(flag, true);
        return new Props(next);
    }

    /// Declares what this component understands, and refuses anything else.
    /// Called by a component on the props it was handed.
    public Props only(String... known) {
        var accepted = Set.of(known);
        for (var name : values.fields().keySet()) {
            if (accepted.contains(name)) continue;
            throw new HtmlException(
                    "no such prop: " + name + " — this component takes " + List.of(known));
        }
        return this;
    }

    public String text(String name, String fallback) {
        return values.string(name, fallback);
    }

    public boolean flag(String name) {
        return values.get(name) instanceof Json.Str(var text) ? text.equals("true") : values.flag(name);
    }

    public int number(String name, int fallback) {
        return switch (values.get(name)) {
            case Json.Num(var value) -> (int) value;
            case Json.Str(var text) -> parse(name, text, fallback);
            case null, default -> fallback;
        };
    }

    /// One of a fixed set — a tone, a size, a level. An unknown one is refused
    /// for the same reason an unknown prop is: `tone="danger"` where the set
    /// says `error` renders as if nothing was asked for.
    public String one(String name, String fallback, String... allowed) {
        var chosen = values.string(name, fallback);
        if (List.of(allowed).contains(chosen)) return chosen;
        throw new HtmlException(name + " is one of " + List.of(allowed) + ", not " + chosen);
    }

    private static int parse(String name, String text, int fallback) {
        try {
            return text.isBlank() ? fallback : Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new HtmlException(name + " is a number, not " + text);
        }
    }
}
