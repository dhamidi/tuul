package browser;

import application.Effect;
import application.Message;
import application.Step;
import java.util.List;
import json.Json;
import json.Pointer;
import symbols.Docs;
import symbols.Catalog;

/// What the browser knows while it answers one request, and how it comes to
/// know it.
///
/// A page is an `application`: the request is a message, an update says what
/// state it leads to and what has to happen for it, and the effect that does
/// the happening is the only thing here that touches the index. That is what
/// keeps the updates worth testing — they are arithmetic on values, and a test
/// of one needs no server, no index and no disk.
public final class Symbols {

    /// The effect a page asks for when it needs a symbol, and the messages that
    /// come back. Naming them once is what keeps the update and the handler
    /// talking about the same thing.
    public static final String LOOK = "symbols.look";

    public static final String SEARCH = "symbols.search";

    public static final String FOUND = "symbols.found";

    public static final String MISSING = "symbols.missing";

    public static final String MATCHED = "symbols.matched";

    private Symbols() {}

    /// What the browser knows about one symbol: what was asked for, what the
    /// index said, and what went wrong if anything did.
    ///
    /// The description is a [Json.Object] rather than a `TypeInfo` because a
    /// message is JSON — that is the architecture's rule, not a preference —
    /// and it is the same object `tuul docs --json` prints, so the page and the
    /// command can never describe a type differently.
    public record Symbol(String name, Json.Object description, String problem) {

        public static Symbol nothing() {
            return new Symbol("", Json.Object.of(), "");
        }

        public boolean found() {
            return !description.fields().isEmpty();
        }

        public Symbol describing(Json.Object description) {
            return new Symbol(name, description, "");
        }

        public Symbol asking(String name) {
            return new Symbol(name, description, problem);
        }

        public Symbol failed(String problem) {
            return new Symbol(name, Json.Object.of(), problem);
        }
    }

    /// What the browser knows about a search: what was typed, what matched, and
    /// what went wrong.
    public record Found(String query, List<Json> matches, String problem) {

        public static Found nothing() {
            return new Found("", List.of(), "");
        }

        public boolean asked() {
            return !query.isBlank();
        }

        public Found matching(List<Json> matches) {
            return new Found(query, matches, "");
        }

        public Found asking(String query) {
            return new Found(query, List.of(), problem);
        }

        public Found failed(String problem) {
            return new Found(query, List.of(), problem);
        }
    }

    /// A request for one symbol. The name arrives as a path variable, which
    /// [web.Requests] has already merged into the parameters.
    public static Step<Symbol> asked(Symbol state, Message message) {
        var name = parameter(message, "name");
        if (name.isBlank()) return Step.of(state.failed("no symbol was named"));
        return Step.of(state.asking(name), Effect.of(LOOK).with("symbol", name));
    }

    public static Step<Symbol> found(Symbol state, Message message) {
        return Step.of(state.describing(message.body()));
    }

    public static Step<Symbol> missing(Symbol state, Message message) {
        return Step.of(state.failed("no symbol called " + message.string("symbol", state.name())));
    }

    public static Step<Symbol> failed(Symbol state, Message message) {
        return Step.of(state.failed(message.string("reason", "something went wrong")));
    }

    /// A search. An empty query is not a failure — it is the front page before
    /// anybody has typed anything — so it asks the index nothing.
    public static Step<Found> searched(Found state, Message message) {
        var query = parameter(message, "q");
        if (query.isBlank()) return Step.of(Found.nothing());
        return Step.of(state.asking(query), Effect.of(SEARCH).with("query", query));
    }

    public static Step<Found> matched(Found state, Message message) {
        return Step.of(state.matching(message.body().find("/matches").map(Json::list).orElse(List.of())));
    }

    public static Step<Found> unsearched(Found state, Message message) {
        return Step.of(state.failed(message.string("reason", "the index could not be searched")));
    }

    /// Looks a symbol up. The only place in the browser that reads the index,
    /// which is why it is the only place that can be slow.
    public static Effect.Handler looking(Catalog index) {
        return (effect, emit) -> {
            var name = effect.string("symbol", "");
            if (!index.ready()) {
                emit.emit(Message.error("documentation is being indexed"));
                return;
            }
            emit.emit(index.lookup(name)
                    .map(type -> Message.of(FOUND, Docs.describe(type, false,
                            type.kind() == symbols.TypeInfo.Kind.PACKAGE ? index.documents(type.name()) : List.of())))
                    .orElseGet(() -> Message.of(MISSING).with("symbol", name)));
        };
    }

    /// Searches the index, and answers each symbol once.
    ///
    /// Three overloads of `of` are three members and one place: a result names
    /// a symbol and links to where it is written, and the index knows nothing
    /// about parameters, so three rows would render as the same line three
    /// times pointing at the same anchor.
    public static Effect.Handler searching(Catalog index, int limit) {
        return (effect, emit) -> {
            var query = effect.string("query", "");
            if (query.isEmpty()) {
                emit.emit(Message.of(MATCHED).with("matches", Json.Array.of(List.of())));
                return;
            }
            if (!index.ready()) {
                emit.emit(Message.error("documentation is being indexed"));
                return;
            }
            var seen = new java.util.LinkedHashSet<String>();
            var matches = index.search(query, limit).stream()
                    .filter(match -> seen.add(match.symbol()))
                    .map(match -> (Json) Json.Object.of()
                            .with("symbol", match.symbol())
                            .with("kind", match.kind())
                            .with("modifiers", match.modifiers())
                            .with("doc", match.doc()))
                    .toList();
            emit.emit(Message.of(MATCHED).with("matches", Json.Array.of(matches)));
        };
    }

    private static String parameter(Message message, String name) {
        return message.body().find(Pointer.ofTokens("params", name)).map(value -> value.string("")).orElse("");
    }
}
