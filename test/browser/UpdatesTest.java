package browser;

import application.Effect;
import application.Message;
import browser.Symbols.Found;
import browser.Symbols.Symbol;
import harness.Check;
import java.util.List;
import java.util.Map;
import json.Json;
import symbols.Catalog;

/// The updates, which are arithmetic on values.
///
/// Not one of these needs a server, an index or a disk, and that is the whole
/// return on writing the browser as an `application`: what the page decides is
/// separable from what makes it slow, so the deciding can be checked at the
/// speed of a method call. The two effect handlers at the end are the
/// exception — they are the parts that do touch the index, which is why they
/// are the only parts here that are given one.
public final class UpdatesTest {

    private UpdatesTest() {}

    public static void run(Catalog index) {
        asking();
        answering();
        searching();
        values();
        handlers(index);
    }

    /// Asking for a symbol: what the page decides before anything is looked up.
    private static void asking() {
        var asked = Symbols.asked(Symbol.nothing(), request(Routes.SYMBOL, Map.of("name", "json.Json")));
        Check.equal("asking for a symbol asks the index for it", 1, asked.effects().size());
        Check.equal("by the name the effect is registered under", Symbols.LOOK, asked.effects().getFirst().type());
        Check.equal("and carries the symbol it wants", "json.Json", asked.effects().getFirst().string("symbol", ""));
        Check.equal("and remembers what was asked for", "json.Json", asked.state().name());
        Check.that("with nothing to apologise for yet", asked.state().problem().isEmpty());
        Check.that("and nothing found yet either", !asked.state().found());

        var unnamed = Symbols.asked(Symbol.nothing(), request(Routes.SYMBOL, Map.of()));
        Check.that("a request naming no symbol asks the index nothing", unnamed.effects().isEmpty());
        Check.equal("and says why", "no symbol was named", unnamed.state().problem());

        var blank = Symbols.asked(Symbol.nothing(), request(Routes.SYMBOL, Map.of("name", "   ")));
        Check.that("a name of nothing but space is no name", blank.effects().isEmpty());

        var paramless = Symbols.asked(Symbol.nothing(), Message.of(Routes.SYMBOL.name()));
        Check.that("nor is a message carrying no parameters at all", paramless.effects().isEmpty());
    }

    /// What the index answered, and what the page makes of it.
    private static void answering() {
        var described = Json.Object.of().with("class", "json.Json").with("kind", "interface");
        var found = Symbols.found(Symbol.nothing().asking("json.Json"), Message.of(Symbols.FOUND, described));
        Check.equal("a description that arrives is kept", "json.Json", found.state().description().string("class", ""));
        Check.that("and the page knows it has one", found.state().found());
        Check.that("the message type is not part of the description",
                found.state().description().get("type") == null);
        Check.that("finding something asks for nothing further", found.effects().isEmpty());

        // A described symbol may itself be called `type` — javac has a `type`
        // in every description it writes about one. This used to be impossible
        // to say: the envelope took the word.
        var shadowing = Json.Object.of().with("class", "json.Json").with("type", "interface");
        var kept = Symbols.found(Symbol.nothing(), Message.of(Symbols.FOUND, shadowing));
        Check.equal("a description with its own type keeps it",
                "interface", kept.state().description().string("type", ""));

        var recovered = Symbols.found(Symbol.nothing().failed("something earlier"),
                Message.of(Symbols.FOUND, described));
        Check.that("and a description clears whatever went wrong before it",
                recovered.state().problem().isEmpty());

        var missing = Symbols.missing(Symbol.nothing().asking("nothing.At.All"),
                Message.of(Symbols.MISSING).with("symbol", "nothing.At.All"));
        Check.equal("a symbol nobody has says which one",
                "no symbol called nothing.At.All", missing.state().problem());
        Check.that("and describes nothing", !missing.state().found());

        var unnamedMiss = Symbols.missing(Symbol.nothing().asking("json.Json"), Message.of(Symbols.MISSING));
        Check.equal("falling back to the name the page was asked for",
                "no symbol called json.Json", unnamedMiss.state().problem());

        var failed = Symbols.failed(Symbol.nothing(), Message.error("the index is a directory"));
        Check.equal("a failure is reported in the index's own words",
                "the index is a directory", failed.state().problem());

        var silent = Symbols.failed(Symbol.nothing(), Message.of(Message.HANDLE_ERROR));
        Check.equal("and has something to say even when nothing was said",
                "something went wrong", silent.state().problem());
    }

    /// Searching, where an empty question is not a failure.
    private static void searching() {
        var typed = Symbols.searched(Found.nothing(), request(Routes.SEARCH, Map.of("q", "json")));
        Check.equal("a search that was typed asks the index once", 1, typed.effects().size());
        Check.equal("by the name the effect is registered under", Symbols.SEARCH, typed.effects().getFirst().type());
        Check.equal("and carries the question", "json", typed.effects().getFirst().string("query", ""));
        Check.equal("which the box still holds", "json", typed.state().query());
        Check.that("so the page knows it was asked something", typed.state().asked());

        var empty = Symbols.searched(Found.nothing(), request(Routes.HOME, Map.of()));
        Check.that("an empty search is the front page, not a failure", empty.effects().isEmpty());
        Check.that("and has nothing to say about itself", empty.state().problem().isEmpty());
        Check.that("and was not asked anything", !empty.state().asked());

        var cleared = Symbols.searched(Found.nothing().asking("json").matching(List.of(Json.of("x"))),
                request(Routes.SEARCH, Map.of("q", "")));
        Check.that("clearing the box goes back to the front page rather than keeping the last answer",
                cleared.state().matches().isEmpty());
        Check.equal("with nothing left in it", "", cleared.state().query());

        var matched = Symbols.matched(Found.nothing().asking("json"),
                Message.of(Symbols.MATCHED).with("groups", Json.Array.of(List.of(Json.Object.of()
                        .with("prefix", "json").with("matches", Json.Array.of(List.of(match("json.Json"))))))));
        Check.equal("what matched is what the index said", 1, matched.state().matches().size());
        Check.equal("and the question is still the one that was asked", "json", matched.state().query());

        var recovered = Symbols.matched(Found.nothing().asking("json").failed("earlier"),
                Message.of(Symbols.MATCHED).with("groups", Json.Array.of(List.of())));
        Check.that("an answer clears whatever went wrong before it", recovered.state().problem().isEmpty());

        var unsearched = Symbols.unsearched(Found.nothing().asking("json"), Message.error("fts5 said no"));
        Check.equal("a search that could not run says so", "fts5 said no", unsearched.state().problem());
        Check.that("and offers nothing", unsearched.state().matches().isEmpty());
        Check.equal("a failure with no reason still has one", "the index could not be searched",
                Symbols.unsearched(Found.nothing(), Message.of(Message.HANDLE_ERROR)).state().problem());
    }

    /// The two records, which are the state — everything above is a way of
    /// getting from one of these to another.
    private static void values() {
        Check.that("a symbol nobody asked for has not been found", !Symbol.nothing().found());
        Check.that("a description of nothing is not a description",
                !Symbol.nothing().describing(Json.Object.of()).found());
        Check.equal("asking keeps what was already described", "json.Json",
                Symbol.nothing().describing(Json.Object.of().with("class", "json.Json")).asking("other")
                        .description().string("class", ""));
        Check.that("failing throws the description away", !Symbol.nothing()
                .describing(Json.Object.of().with("class", "json.Json")).failed("gone").found());

        Check.that("a search nobody typed was not asked", !Found.nothing().asked());
        Check.that("nor was one holding only space", !Found.nothing().asking("   ").asked());
        Check.that("but one holding a word was", Found.nothing().asking("j").asked());
        Check.that("asking again clears the last answer",
                Found.nothing().matching(List.of(match("a"))).asking("b").matches().isEmpty());
        Check.equal("failing keeps the question, so the box still holds it", "b",
                Found.nothing().asking("b").failed("no").query());
    }

    /// The two handlers that read the index. They are effects rather than
    /// updates precisely because they are the slow half, and this is the only
    /// place in this file that needs an index at all.
    private static void handlers(Catalog index) {
        var found = emitted(Symbols.looking(index), Effect.of(Symbols.LOOK).with("symbol", "json.Json"));
        Check.equal("looking up a symbol answers with what it is", Symbols.FOUND, found.type());
        Check.equal("described the way tuul docs describes it", "json.Json", found.string("class", ""));

        var absent = emitted(Symbols.looking(index), Effect.of(Symbols.LOOK).with("symbol", "nothing.At.All"));
        Check.equal("and a symbol nobody has answers that it is missing", Symbols.MISSING, absent.type());
        Check.equal("naming the one that was wanted", "nothing.At.All", absent.string("symbol", ""));

        var matches = emitted(Symbols.searching(index, 25), Effect.of(Symbols.SEARCH).with("query", "write"));
        Check.equal("searching answers with what matched", Symbols.MATCHED, matches.type());
        Check.that("which is something", !names(matches).isEmpty());
        Check.equal("and each symbol once, since overloads are one place",
                names(matches).size(), (int) names(matches).stream().distinct().count());
        Check.that("grouped under the name the results share",
                matches.list("groups").getFirst() instanceof Json.Object group
                        && group.string("prefix", "").startsWith("json"));
        Check.that("and every word was held", matches.flag("every"));

        var nothing = emitted(Symbols.searching(index, 25), Effect.of(Symbols.SEARCH));
        Check.that("a search for nothing asks the index nothing", nothing.list("groups").isEmpty());

        var limited = emitted(Symbols.searching(index, 1), Effect.of(Symbols.SEARCH).with("query", "write"));
        Check.that("a page of results is a page", names(limited).size() <= 1);
    }

    private static List<String> names(Message matched) {
        return matched.list("groups").stream()
                .filter(Json.Object.class::isInstance)
                .flatMap(group -> ((Json.Object) group).list("matches").stream())
                .map(match -> match instanceof Json.Object entry ? entry.string("symbol", "") : "")
                .toList();
    }

    /// Runs an effect handler and answers with the one message it emitted.
    private static Message emitted(Effect.Handler handler, Effect effect) {
        var answers = new java.util.ArrayList<Message>();
        try {
            handler.run(effect, answers::add);
        } catch (Exception e) {
            throw new IllegalStateException("the handler failed: " + e, e);
        }
        Check.equal("an effect answers exactly once: " + effect.type(), 1, answers.size());
        return answers.getFirst();
    }

    private static Json match(String symbol) {
        return Json.Object.of().with("symbol", symbol).with("kind", "CLASS").with("doc", "");
    }

    /// A request as [web.Requests] hands one to an update: the parameters
    /// merged under `params`, whatever they came from.
    private static Message request(web.RouteRef route, Map<String, String> params) {
        var values = Json.Object.of();
        for (var entry : params.entrySet()) values = values.with(entry.getKey(), entry.getValue());
        return Message.of(route.name(), Json.Object.of().with("params", values));
    }
}
