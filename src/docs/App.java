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

    private App() {}

    public static Application<State> of(State initial, Writer out, Writer err) {
        return Application.of(initial)
                .on("docs.query", App::query)
                .on("docs.result", App::result)
                .on("docs.missing", App::missing)
                .on("error", App::failed)
                .effect("symbols.lookup", App::look)
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
        return Step.of(asking, Effect.of("symbols.lookup")
                .with("symbol", message.string("symbol", ""))
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

    private static Step<State> missing(State state, Message message) {
        return Step.of(state.failed(), report("unknown symbol: " + message.string("symbol", "")));
    }

    private static Step<State> failed(State state, Message message) {
        return Step.of(state.failed(), report("error: " + message.string("reason", "unknown")));
    }

    private static Effect report(String line) {
        return Effect.of("docs.report").with("line", line);
    }

    private static void look(Effect effect, Effect.Emitter emit) throws IOException {
        var symbol = effect.string("symbol", "");
        if (symbol.isEmpty()) {
            emit.emit(Message.error("no symbol given"));
            return;
        }
        emit.emit(Index.of(paths(effect.list("sourcePath")), paths(effect.list("vendorPath")))
                .lookup(symbol)
                .map(type -> Message.of("docs.result", Docs.describe(type, effect.flag("all"))))
                .orElseGet(() -> Message.of("docs.missing").with("symbol", symbol)));
    }

    private static void print(Effect effect, Writer out) throws IOException {
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
