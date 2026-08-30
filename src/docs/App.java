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
import symbols.Catalog;
import symbols.CatalogFactory;
import symbols.Index;

/// `tuul docs` as an application: which messages it understands, and how the
/// effects those messages ask for are carried out.
///
/// Compiling or indexing can fail, so each symbol operation is an effect. A
/// broken project produces an `error` message instead of a stack trace. Search
/// makes the project, selected dependency, and lightweight JDK generations
/// current. The writers are bound here, at the edge, and nowhere else.
public final class App {

    /// How many matches a search answers with. Enough to choose from, few
    /// enough to read.
    private static final int MATCHES = 20;

    private App() {}

    public static Application<State> of(State initial, Writer out, Writer err) {
        return of(initial, out, err, CatalogFactory.system());
    }

    /// Creates the docs application with the catalog factory that runs symbol
    /// queries. The factory receives the paths carried by each query effect.
    public static Application<State> of(State initial, Writer out, Writer err, CatalogFactory catalogs) {
        return Application.of(initial)
                .on("docs.query", App::query)
                .on("docs.result", App::result)
                .on("docs.found", App::found)
                .on("docs.roots", App::rooted)
                .on("docs.missing", App::missing)
                .on("error", App::failed)
                .effect("symbols.lookup", (effect, emit) -> look(effect, emit, catalogs))
                .effect("symbols.search", (effect, emit) -> search(effect, emit, catalogs))
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
                .with("all", asking.all())
                .with("index", asking.index().toString())
                .with("members", message.flag("members") || message.flag("recursive"))
                .with("recursive", message.flag("recursive")));
    }

    private static Step<State> result(State state, Message message) {
        return Step.of(state, Effect.of("docs.print")
                .with("symbol", message.body())
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

    private static Step<State> missing(State state, Message message) {
        return Step.of(state.failed(), report("unknown symbol: " + message.string("symbol", "")));
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
    /// Each JSON result includes its origin and source location.
    private static void search(Effect effect, Effect.Emitter emit, CatalogFactory catalogs) throws IOException {
        var wanted = effect.string("search", "");
        try (var index = catalog(effect, catalogs)) {
            var found = index.search(wanted, MATCHES).stream()
                    .map(match -> (Json) Json.Object.of()
                            .with("symbol", match.symbol())
                            .with("kind", match.kind())
                            .with("doc", match.doc())
                            .with("origin", match.origin())
                            .with("source", match.source()))
                    .toList();
            emit.emit(Message.of("docs.found").with("search", wanted).with("matches", Json.Array.of(found)));
        }
    }

    private static void look(Effect effect, Effect.Emitter emit, CatalogFactory catalogs) throws IOException {
        var symbol = effect.string("symbol", "");
        if (symbol.isEmpty()) {
            try (var index = catalog(effect, catalogs)) {
                emit.emit(Message.of("docs.roots", Docs.describe(index.roots())));
            }
            return;
        }
        try (var index = catalog(effect, catalogs)) {
            var requested = document(symbol);
            if (requested.isPresent()) {
                var name = requested.get();
                var exact = index.document(name.packageName(), name.kind(), name.slug());
                if (exact.isPresent()) {
                    emit.emit(Message.of("docs.result", exact.get().describe()));
                    return;
                }
                var documents = name.slug().isEmpty()
                        ? index.documents(name.packageName(), name.kind()) : List.<symbols.Document>of();
                if (!documents.isEmpty()) {
                    emit.emit(Message.of("docs.result", Json.Object.of()
                            .with("package", name.packageName()).with("kind", name.kind())
                            .with("documents", Json.Array.of(documents.stream()
                                    .map(document -> (Json) document.describe().without("doc").without("source"))
                                    .toList()))));
                    return;
                }
                emit.emit(Message.of("docs.missing").with("symbol", symbol));
                return;
            }
            emit.emit(index.lookup(symbol)
                    .map(type -> Message.of("docs.result", described(index, type, effect)))
                    .orElseGet(() -> Message.of("docs.missing").with("symbol", symbol)));
        }
    }

    /// One symbol, and what it holds when the caller asked for that.
    private static Json.Object described(Catalog index, symbols.TypeInfo type, Effect effect) {
        var documents = type.kind() == symbols.TypeInfo.Kind.PACKAGE
                ? index.documents(type.name()) : List.<symbols.Document>of();
        var description = Docs.describe(type, effect.flag("all"), documents);
        if (!effect.flag("members")) return description;
        return description.with("members", Json.Array.of(
                members(index, description, effect.flag("all"), effect.flag("recursive"), new LinkedHashSet<>())));
    }

    /// Every symbol a package or a type holds, described in the same way it is.
    ///
    /// Reading a package took a question per type in it, which is the dominant
    /// cost of this tool for anybody getting oriented — a person or an agent.
    /// One question now answers for all of them.
    ///
    /// A subpackage is skipped unless `recursive` is set, because `web` holds
    /// eight of them and a reader who asked about `web` asked about `web`. The
    /// `seen` set makes a name appear once however many ways it is reached.
    private static List<Json> members(Catalog index, Json.Object description, boolean all, boolean recursive,
            Set<String> seen) {
        var described = new ArrayList<Json>();
        for (var name : strings(description.list("nested"))) {
            if (!seen.add(name)) continue;
            var found = index.lookup(name);
            if (found.isEmpty()) continue;
            var member = Docs.describe(found.get(), all);
            var group = member.string("kind", "").equals("package") || member.string("kind", "").equals("module");
            if (group && !recursive) continue;
            described.add(member);
            if (group) described.addAll(members(index, member, all, true, seen));
        }
        return List.copyOf(described);
    }

    private static void print(Effect effect, Writer out) throws IOException {
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
        if (description.get("package") instanceof Json.Str && description.get("documents") instanceof Json.Array
                && description.get("doc") == null) {
            if (effect.flag("json")) {
                description.write(out);
                out.write("\n");
            } else {
                for (var value : description.list("documents")) {
                    if (!(value instanceof Json.Object document)) continue;
                    out.write(document.string("title", "") + "\t" + document.string("slug", "") + "\n");
                }
            }
            out.flush();
            return;
        }
        if (description.get("package") instanceof Json.Str && description.get("title") instanceof Json.Str) {
            if (effect.flag("json")) {
                description.write(out);
                out.write("\n");
            } else {
                out.write(description.string("doc", ""));
                if (!description.string("doc", "").endsWith("\n")) out.write("\n");
            }
            out.flush();
            return;
        }
        var sections = sections(effect.list("sections"));
        var members = description.list("members");
        var one = description.without("members");
        if (!effect.flag("json")) {
            Docs.text(one, sections, out);
            for (var member : members) {
                if (!(member instanceof Json.Object held)) continue;
                out.write("\n");
                Docs.text(held, sections, out);
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

    private record DocumentName(String packageName, String kind, String slug) {}

    private static java.util.Optional<DocumentName> document(String symbol) {
        var parts = symbol.split("/", -1);
        if (parts.length < 2 || parts.length > 3 || parts[0].isBlank()) return java.util.Optional.empty();
        var kind = parts[1];
        if (!List.of("tutorial", "howto", "reference", "guide").contains(kind)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new DocumentName(parts[0], kind, parts.length == 3 ? parts[2] : ""));
    }

    private static void report(Effect effect, Writer err) throws IOException {
        err.write(effect.string("line", "") + "\n");
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
