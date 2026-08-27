package docs;

import application.Application;
import application.Effect;
import application.Message;
import application.Step;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import json.Json;
import symbols.Docs;
import symbols.Index;

/// `tuul docs` as an application: which messages it understands, and how the
/// effects those messages ask for are carried out.
///
/// Compiling a source tree is slow and can fail, so it is an effect — which
/// means a broken project produces an `error` message, not a stack trace. The
/// writers are bound here, at the edge, and nowhere else.
public final class App {

    /// How many matches a search answers with. Enough to choose from, few
    /// enough to read.
    private static final int MATCHES = 20;

    private App() {}

    public static Application<State> of(State initial, Writer out, Writer err) {
        return Application.of(initial)
                .on("docs.query", App::query)
                .on("docs.result", App::result)
                .on("docs.found", App::found)
                .on("docs.missing", App::missing)
                .on("error", App::failed)
                .effect("symbols.lookup", App::look)
                .effect("symbols.search", App::search)
                .effect("docs.print", (effect, _) -> print(effect, out))
                .effect("docs.report", (effect, _) -> report(effect, err));
    }

    private static Step<State> query(State state, Message message) {
        var asking = state.asking(
                given(message.list("sourcePath"), state.sourcePath()),
                given(message.list("vendorPath"), state.vendorPath()),
                message.flag("json"),
                sections(message.list("sections")),
                message.flag("all"));
        var search = message.string("search", "");
        return Step.of(asking, Effect.of(search.isEmpty() ? "symbols.lookup" : "symbols.search")
                .with("symbol", message.string("symbol", ""))
                .with("search", search)
                .with("sourcePath", directories(asking.sourcePath()))
                .with("vendorPath", directories(asking.vendorPath()))
                .with("all", asking.all()));
    }

    private static Step<State> result(State state, Message message) {
        return Step.of(state, Effect.of("docs.print")
                .with("symbol", message.body().without("type"))
                .with("json", state.json())
                .with("sections", Json.Array.strings(List.copyOf(state.sections()))));
    }

    /// A search answers with a list rather than a symbol, so it prints as one.
    private static Step<State> found(State state, Message message) {
        var matches = message.list("matches");
        if (matches.isEmpty()) return Step.of(state.failed(), report("nothing matches " + message.string("search", "")));
        return Step.of(state, Effect.of("docs.print")
                .with("matches", Json.Array.of(matches))
                .with("json", state.json()));
    }

    private static Step<State> missing(State state, Message message) {
        return Step.of(state.failed(), report("unknown symbol: " + message.string("symbol", "")));
    }

    private static Step<State> failed(State state, Message message) {
        return Step.of(state.failed(), report("error: " + message.string("reason", "unknown")));
    }

    private static Effect report(String line) {
        return Effect.of("docs.report").with("line", line);
    }

    /// Searching needs the project indexed, so this is where a first search
    /// pays for one — and every search after it does not.
    private static void search(Effect effect, Effect.Emitter emit) throws IOException {
        var wanted = effect.string("search", "");
        try (var index = Index.of(paths(effect.list("sourcePath")), paths(effect.list("vendorPath")))) {
            var found = index.search(wanted, MATCHES).stream()
                    .map(match -> (Json) Json.Object.of()
                            .with("symbol", match.symbol())
                            .with("kind", match.kind())
                            .with("doc", match.doc()))
                    .toList();
            emit.emit(Message.of("docs.found").with("search", wanted).with("matches", Json.Array.of(found)));
        }
    }

    private static void look(Effect effect, Effect.Emitter emit) throws IOException {
        var symbol = effect.string("symbol", "");
        if (symbol.isEmpty()) {
            emit.emit(Message.error("no symbol given"));
            return;
        }
        try (var index = Index.of(paths(effect.list("sourcePath")), paths(effect.list("vendorPath")))) {
            emit.emit(index.lookup(symbol)
                    .map(type -> Message.of("docs.result", Docs.describe(type, effect.flag("all"))))
                    .orElseGet(() -> Message.of("docs.missing").with("symbol", symbol)));
        }
    }

    private static void print(Effect effect, Writer out) throws IOException {
        if (effect.get("matches") instanceof Json.Array(var matches)) {
            if (effect.flag("json")) {
                Json.Object.of().with("matches", Json.Array.of(matches)).write(out);
                out.write("\n");
            } else {
                Docs.matches(matches, out);
            }
            out.flush();
            return;
        }
        if (!(effect.get("symbol") instanceof Json.Object description)) return;
        var sections = sections(effect.list("sections"));
        if (!effect.flag("json")) {
            Docs.text(description, sections, out);
        } else {
            Docs.select(description, sections).write(out);
            out.write("\n");
        }
        out.flush();
    }

    private static void report(Effect effect, Writer err) throws IOException {
        err.write(effect.string("line", "") + "\n");
        err.flush();
    }

    private static List<Path> given(List<Json> values, List<Path> fallback) {
        var given = paths(values);
        return given.isEmpty() ? fallback : given;
    }

    private static Json directories(List<Path> paths) {
        return Json.Array.strings(paths.stream().map(Path::toString).toList());
    }

    private static List<Path> paths(List<Json> values) {
        return strings(values).stream().map(Path::of).toList();
    }

    private static Set<String> sections(List<Json> values) {
        return new LinkedHashSet<>(strings(values));
    }

    private static List<String> strings(List<Json> values) {
        var strings = new ArrayList<String>();
        for (var value : values) {
            if (value instanceof Json.Str(var text)) strings.add(text);
        }
        return strings;
    }
}
