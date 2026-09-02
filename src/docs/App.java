package docs;

import application.Application;
import application.Effect;
import application.Message;
import application.Step;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import json.Json;
import symbols.Catalog;
import symbols.CatalogFactory;
import symbols.Docs;
import symbols.Index;
import symbols.Queries;

/// `tuul docs` as an application: the messages it understands, and the
/// effects that answer them.
///
/// Send `docs.query` with a `symbol` or a `search`, and the flags of the
/// command line, to get an answer printed on `out`. The answer comes from
/// [Queries], the same class `tuul browse` asks, so the two describe a
/// symbol the same way. The catalog is opened and read inside an effect.
/// When it reports a problem, such as a project that does not compile, the
/// answer is printed and the problem follows on `err` as a `warning:` line.
/// The exit status is 1 only when there is no answer.
public final class App {

    private static final Duration INDEXING_NOTICE = Duration.ofSeconds(5);

    private App() {}

    public static Application<State> of(State initial, Writer out, Writer err) {
        return of(initial, out, err, CatalogFactory.system());
    }

    /// Creates the docs application with the catalog factory that runs symbol
    /// queries. The factory receives the paths carried by each query effect.
    public static Application<State> of(State initial, Writer out, Writer err, CatalogFactory catalogs) {
        return of(initial, out, err, catalogs, App::after);
    }

    static Application<State> of(State initial, Writer out, Writer err, CatalogFactory catalogs,
            Inactivity inactivity) {
        return Application.of(initial)
                .on("docs.query", App::query)
                .on("docs.result", App::result)
                .on("docs.found", App::found)
                .on("docs.roots", App::rooted)
                .on("docs.code", App::coded)
                .on("docs.missing", App::missing)
                .on("docs.problem", App::problem)
                .on(Message.HANDLE_ERROR, App::failed)
                .effect("symbols.lookup", (effect, emit) -> waiting(err, inactivity,
                        () -> look(effect, emit, catalogs)))
                .effect("symbols.search", (effect, emit) -> waiting(err, inactivity,
                        () -> search(effect, emit, catalogs)))
                .effect("docs.print", (effect, _) -> print(effect, out))
                .effect("docs.report", (effect, _) -> report(effect, err));
    }

    @FunctionalInterface
    interface Inactivity {
        Pending after(Duration duration, Output output) throws IOException;
    }

    @FunctionalInterface
    interface Pending extends AutoCloseable {
        @Override
        void close() throws IOException;
    }

    @FunctionalInterface
    interface Output {
        void write() throws IOException;
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
                .with("all", asking.all())
                .with("index", asking.index().toString())
                .with("members", message.flag("members") || message.flag("recursive"))
                .with("recursive", message.flag("recursive"))
                .with("documents", message.flag("documents"))
                .with("code", message.flag("code")));
    }

    private static Step<State> result(State state, Message message) {
        return Step.of(state, Effect.of("docs.print")
                .with("symbol", message.body())
                .with("json", state.json())
                .with("sections", Json.Array.strings(List.copyOf(state.sections()))));
    }

    /// A search answers with groups, so it prints as groups. No group at
    /// all is a failure. When the groups hold only some of the words, a line
    /// on `err` says so after the results.
    private static Step<State> found(State state, Message message) {
        var groups = message.list("groups");
        var query = message.string("query", "");
        if (groups.isEmpty()) return Step.of(state.failed(), report("nothing matches " + query));
        var effects = new ArrayList<Effect>();
        effects.add(Effect.of("docs.print").with("results", message.body()).with("json", state.json()));
        if (!message.flag("every")) {
            effects.add(report("nothing holds every word of \"" + query + "\"; these hold some of them"));
        }
        return Step.of(state, effects.toArray(new Effect[0]));
    }

    /// Nobody named anything, so the answer is what there is to name.
    ///
    /// `tuul docs` was an error — *no symbol given* — which is a strange thing
    /// to say to somebody who has not been told what the symbols are. The
    /// question with no name is the first one a reader has.
    private static Step<State> rooted(State state, Message message) {
        return Step.of(state, Effect.of("docs.print")
                .with("roots", message.body())
                .with("json", state.json()));
    }

    /// Source code prints as it was written, whatever else was asked: JSON
    /// around a file is a file nobody can compile.
    private static Step<State> coded(State state, Message message) {
        return Step.of(state, Effect.of("docs.print").with("text", message.string("text", "")));
    }

    private static Step<State> missing(State state, Message message) {
        return Step.of(state.failed(), report(message.string("reason", "unknown symbol: " + message.string("symbol", ""))));
    }

    /// A problem is not a failure. The answer was printed from the last good
    /// index, so this prints a `warning:` line on `err` and leaves the exit
    /// status alone.
    private static Step<State> problem(State state, Message message) {
        return Step.of(state, report("warning: " + message.string("reason", "")));
    }

    private static Step<State> failed(State state, Message message) {
        return Step.of(state.failed(), report("error: " + message.string("reason", "unknown")));
    }

    /// Back from the message it travelled in. A root is JSON on the way here
    /// like everything else, and [Docs] prints the records rather than the
    /// object, because the same records are what a caller in Java would hold.
    private static List<Catalog.Root> roots(Json.Object listing) {
        var roots = new ArrayList<Catalog.Root>();
        for (var value : listing.list("roots")) {
            if (!(value instanceof Json.Object root)) continue;
            var contents = new ArrayList<String>();
            for (var name : root.list("contains")) {
                if (name instanceof Json.Str(var text)) contents.add(text);
            }
            roots.add(new Catalog.Root(root.string("root", ""), root.string("label", ""), List.copyOf(contents)));
        }
        return List.copyOf(roots);
    }

    private static Effect report(String line) {
        return Effect.of("docs.report").with("line", line);
    }

    /// Runs a complete selected-set search. The first call can build project,
    /// dependency, and JDK-name generations. Later calls reuse their stamps.
    private static void search(Effect effect, Effect.Emitter emit, CatalogFactory catalogs) throws IOException {
        var wanted = effect.string("search", "");
        try (var index = catalog(effect, catalogs)) {
            emit.emit(Message.of("docs.found", Queries.search(index, wanted, Queries.MATCHES)));
            problems(index, emit);
        }
    }

    private static void look(Effect effect, Effect.Emitter emit, CatalogFactory catalogs) throws IOException {
        var symbol = effect.string("symbol", "");
        try (var index = catalog(effect, catalogs)) {
            if (symbol.isEmpty()) {
                emit.emit(Message.of("docs.roots", Queries.roots(index)));
                problems(index, emit);
                return;
            }
            var asking = new Queries.Asking(effect.flag("all"), effect.flag("members"), effect.flag("recursive"),
                    effect.flag("documents"));
            var answer = Queries.answer(index, symbol, asking);
            if (answer.isEmpty()) {
                emit.emit(Message.of("docs.missing").with("symbol", symbol));
            } else if (!effect.flag("code")) {
                emit.emit(Message.of("docs.result", answer.get()));
            } else {
                emit.emit(Queries.source(index, answer.get())
                        .map(text -> Message.of("docs.code").with("text", text))
                        .orElseGet(() -> Message.of("docs.missing").with("symbol", symbol)
                                .with("reason", "no source for " + symbol + ": "
                                        + (answer.get().string("source", "").isEmpty()
                                                ? "the index knows none" : answer.get().string("source", "")))));
            }
            problems(index, emit);
        }
    }

    private static void problems(Catalog index, Effect.Emitter emit) {
        for (var problem : index.problems()) emit.emit(Message.of("docs.problem").with("reason", problem));
    }

    private static void print(Effect effect, Writer out) throws IOException {
        if (effect.get("text") instanceof Json.Str(var text)) {
            out.write(text);
            if (!text.isEmpty() && !text.endsWith("\n")) out.write("\n");
            out.flush();
            return;
        }
        if (effect.get("roots") instanceof Json.Object listing) {
            if (effect.flag("json")) {
                listing.write(out);
                out.write("\n");
            } else {
                Docs.roots(roots(listing), out);
            }
            out.flush();
            return;
        }
        if (effect.get("results") instanceof Json.Object results) {
            if (effect.flag("json")) {
                results.write(out);
                out.write("\n");
            } else {
                Docs.groups(results.list("groups"), out);
            }
            out.flush();
            return;
        }
        if (!(effect.get("symbol") instanceof Json.Object description)) return;
        if (document(description)) {
            printDocument(description, effect.flag("json"), out);
            out.flush();
            return;
        }
        var sections = sections(effect.list("sections"));
        var members = description.list("members");
        var one = description.without("members");
        if (!effect.flag("json")) {
            Docs.text(one, sections, out);
            Docs.documents(one.list("documents"), out);
            for (var member : members) {
                if (!(member instanceof Json.Object held)) continue;
                out.write("\n");
                Docs.text(held, sections, out);
                Docs.documents(held.list("documents"), out);
            }
        } else {
            var selected = Docs.select(one, sections);
            if (!members.isEmpty()) selected = selected.with("members", Json.Array.of(members.stream()
                    .filter(Json.Object.class::isInstance)
                    .map(member -> (Json) Docs.select((Json.Object) member, sections))
                    .toList()));
            selected.write(out);
            out.write("\n");
        }
        out.flush();
    }

    /// Whether a description is a document or a kind's document list, which
    /// carry `package` and `title`, rather than a symbol, which carries
    /// `class`.
    private static boolean document(Json.Object description) {
        return description.get("package") instanceof Json.Str && description.get("title") instanceof Json.Str;
    }

    /// A document prints as the Markdown it was written in. A kind with no
    /// introduction prints one line per document it has, the name that asks
    /// for the document and then its title.
    private static void printDocument(Json.Object description, boolean json, Writer out) throws IOException {
        if (json) {
            (description.get("doc") instanceof Json.Str ? description.without("documents") : description)
                    .without("links").write(out);
            out.write("\n");
            return;
        }
        if (description.get("doc") instanceof Json.Str(var body)) {
            out.write(body);
            if (!body.endsWith("\n")) out.write("\n");
            return;
        }
        for (var value : description.list("documents")) {
            if (!(value instanceof Json.Object document)) continue;
            out.write(document.string("symbol", "") + "  " + document.string("title", "") + "\n");
        }
    }

    private static void report(Effect effect, Writer err) throws IOException {
        err.write(effect.string("line", "") + "\n");
        err.flush();
    }

    private static void waiting(Writer err, Inactivity inactivity, Output query) throws IOException {
        try (var pending = inactivity.after(INDEXING_NOTICE, () -> status(err))) {
            query.write();
        }
    }

    private static Pending after(Duration duration, Output output) {
        var timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("docs-inactivity", 0).factory());
        var active = new AtomicBoolean(true);
        var failed = new AtomicReference<IOException>();
        var gate = new Object();
        var pending = timer.schedule(() -> {
            synchronized (gate) {
                if (!active.get()) return;
                try {
                    output.write();
                } catch (IOException exception) {
                    failed.set(exception);
                }
            }
        }, duration.toMillis(), TimeUnit.MILLISECONDS);
        return () -> {
            synchronized (gate) {
                active.set(false);
                pending.cancel(false);
            }
            timer.shutdownNow();
            if (failed.get() != null) throw failed.get();
        };
    }

    private static void status(Writer err) throws IOException {
        err.write("indexing...\n");
        err.flush();
    }

    /// The index this question is answered from. Everything it needs travels in
    /// the effect, so nothing here reads a field of the state.
    private static Catalog catalog(Effect effect, CatalogFactory catalogs) throws IOException {
        return catalogs.open(paths(effect.list("sourcePath")), paths(effect.list("vendorPath")),
                Path.of(effect.string("index", Index.INDEX.toString())));
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
