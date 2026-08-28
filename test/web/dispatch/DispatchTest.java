package web.dispatch;

import harness.Check;
import java.util.List;
import java.util.Map;

/// A router is two claims about one definition: that it recognises the URLs it
/// builds, and that it builds the URLs it recognises. Most of these tests are
/// about keeping those two honest; the rest are about the answers a router
/// gives when a request is nearly right, which is where routers are usually
/// careless.
public final class DispatchTest {

    private DispatchTest() {}

    public static void run() {
        recognising();
        constructing();
        roundTrip();
        methods();
        ordering();
        refusing();
        encoding();
        mounting();
    }

    /// A mounted table keeps both directions honest under its prefix, and keeps
    /// its names.
    private static void mounting() {
        var feature = Router.of()
                .get("cable.updates", "/updates")
                .get("cable.one", "/updates/{id}")
                .post("cable.send", "/updates");
        var host = Router.of().get("home", "/").get("search", "/search");

        var mounted = host.mount("/live", feature);
        Check.equal("a mounted route is recognised under its prefix", "cable.updates",
                nameOf(mounted.recognise("GET", "/live/updates")));
        Check.equal("and its path is built from the name it always had", "/live/updates",
                mounted.path("cable.updates"));
        Check.equal("variables survive the move", "/live/updates/7",
                mounted.path("cable.one", Map.of("id", "7")));
        Check.equal("and are read back out again", "7",
                valueOf(mounted.recognise("GET", "/live/updates/7"), "id"));
        Check.equal("the method is not changed by the move", "cable.send",
                nameOf(mounted.recognise("POST", "/live/updates")));
        Check.that("the host keeps its own routes", mounted.route("home").isPresent());
        Check.equal("which are not moved", "/", mounted.path("home"));
        Check.that("and the unprefixed path of a mounted route is gone",
                mounted.recognise("GET", "/updates") instanceof Recognised.NotFound);

        Check.equal("mounting at the root leaves paths alone", "/updates",
                Router.of().mount("", feature).path("cable.updates"));
        Check.equal("and so does mounting at a bare slash", "/updates",
                Router.of().mount("/", feature).path("cable.updates"));

        // A prefix and a root template must not make `//`, which is a
        // different path to everything that reads one.
        var rooted = Router.of().get("blog.home", "/").get("blog.post", "/{slug}");
        Check.equal("a mounted root is the prefix itself", "/blog",
                Router.of().mount("/blog", rooted).path("blog.home"));
        Check.equal("even when the prefix was written with a trailing slash", "/blog",
                Router.of().mount("/blog/", rooted).path("blog.home"));
        Check.equal("and what is under it has one separator", "/blog/hello",
                Router.of().mount("/blog", rooted).path("blog.post", Map.of("slug", "hello")));
        Check.equal("which is the path that is recognised", "blog.home",
                nameOf(Router.of().mount("/blog", rooted).recognise("GET", "/blog")));

        // The collision is the reason mount does not rename: two answers to
        // path("home") is worse than none, so it is refused rather than
        // resolved to whichever arrived first.
        var clash = Router.of().get("home", "/");
        var collision = refused(() -> Router.of().get("home", "/").mount("/blog", clash));
        Check.that("mounting a name the host already has is refused: " + collision,
                collision.contains("home"));
        Check.that("and the message says which mount found it: " + collision,
                collision.contains("/blog"));
        Check.that("a prefix that is not a path is refused",
                refused(() -> Router.of().mount("blog", clash)).contains("starts with /"));

        // Two features that happen to agree on a name are the same mistake.
        Check.that("two mounted tables cannot share a name",
                !refused(() -> Router.of().mount("/a", clash).mount("/b", clash)).isEmpty());
    }

    /// The message from a route table that refused something, or empty if it
    /// did not refuse.
    private static String refused(Runnable mistake) {
        try {
            mistake.run();
            return "";
        } catch (DispatchException refused) {
            return refused.getMessage();
        }
    }

    private static String nameOf(Recognised recognised) {
        return recognised instanceof Recognised.Match match ? match.route().name() : String.valueOf(recognised);
    }

    /// What a recognition found, as something a check can compare. A wrong
    /// outcome then reads as one failed check naming what came instead, rather
    /// than a cast that throws and takes every test after it down with it.
    private static String routeOf(Recognised found) {
        return found instanceof Recognised.Match match ? match.route().name() : String.valueOf(found);
    }

    private static String methodOf(Recognised found) {
        return found instanceof Recognised.Match match ? match.route().method() : String.valueOf(found);
    }

    private static Map<String, String> variablesOf(Recognised found) {
        return found instanceof Recognised.Match match ? match.variables() : Map.of();
    }

    private static String valueOf(Recognised found, String name) {
        return found instanceof Recognised.Match match ? match.value(name) : String.valueOf(found);
    }

    private static List<String> allowedBy(Recognised found) {
        return found instanceof Recognised.NotAllowed refused ? refused.allowed() : List.of();
    }

    private static String pathOf(Recognised found) {
        return switch (found) {
            case Recognised.NotFound(var ignored, var path) -> path;
            case Recognised.NotAllowed(var ignored, var path, var allowed) -> path;
            case Recognised.Match match -> String.valueOf(match);
        };
    }

    /// The table an application would write.
    private static Router routes() {
        return Router.of()
                .get("symbols", "/symbols")
                .get("symbol", "/symbols/{name}")
                .get("member", "/symbols/{name}/members/{member}")
                .delete("symbol", "/symbols/{name}")
                .post("index", "/index");
    }

    private static void recognising() {
        var routes = routes();

        var found = routes.recognise("GET", "/symbols/json.Json");
        Check.equal("a path that is a route is recognised as that route", "symbol", routeOf(found));
        Check.equal("and says what the path was carrying", Map.of("name", "json.Json"), variablesOf(found));
        Check.equal("which can be read by name", "json.Json", valueOf(found, "name"));
        var symbol = new Recognised.Match(routes.route("symbol").orElseThrow(), Map.of("name", "json.Json"));
        Check.throwing("but not by a name the route has not got", () -> symbol.value("nope"));

        var several = routes.recognise("GET", "/symbols/json.Json/members/text");
        Check.equal("every variable in the path comes back",
                Map.of("name", "json.Json", "member", "text"),
                variablesOf(several));

        Check.equal("a route with no variables needs none", "symbols", routeOf(routes.recognise("GET", "/symbols")));

        var missing = routes.recognise("GET", "/nothing/here");
        Check.that("a path that is no route at all is not found", missing instanceof Recognised.NotFound);
        Check.equal("and the answer says what was asked for", "/nothing/here", pathOf(missing));

        Check.that("a value cannot swallow the separator that ends it",
                routes.recognise("GET", "/symbols/json.Json/members") instanceof Recognised.NotFound);
        Check.that("a query is not part of a route, and has to be off the path before asking",
                routes.recognise("GET", "/symbols?q=json") instanceof Recognised.NotFound);
    }

    private static void constructing() {
        var routes = routes();

        Check.equal("a URL is built from the name and the values",
                "/symbols/json.Json",
                routes.path("symbol", Map.of("name", "json.Json")));
        Check.equal("a route with no variables needs no values", "/symbols", routes.path("symbols"));
        Check.equal("and every variable is filled",
                "/symbols/json.Json/members/text",
                routes.path("member", Map.of("name", "json.Json", "member", "text")));

        Check.throwing("a name that is not a route is refused", () -> routes.path("nope"));
        Check.throwing("and so is a value that was not given",
                () -> routes.path("member", Map.of("name", "json.Json")));
        Check.throwing("a null value counts as not given",
                () -> routes.path("symbol", java.util.Collections.singletonMap("name", null)));

        try {
            routes.path("member", Map.of("name", "json.Json"));
        } catch (DispatchException e) {
            Check.that("and the failure names the variable and the route: " + e.getMessage(),
                    e.getMessage().contains("member") && e.getMessage().contains("/symbols/{name}/members/{member}"));
        }
    }

    /// The claim worth testing above all the others: what a router builds, it
    /// recognises, and what it recognises gives back what built it.
    private static void roundTrip() {
        var routes = routes();
        var values = Map.of("name", "java.lang.String", "member", "substring");

        for (var route : List.of("symbol", "member", "symbols")) {
            var path = routes.path(route, values);
            var found = routes.recognise("GET", path);
            Check.equal(route + " builds a path its own router recognises: " + path, route, routeOf(found));
            for (var variable : variablesOf(found).entrySet()) {
                Check.equal("and " + route + " gives back the " + variable.getKey() + " it was built with",
                        values.get(variable.getKey()),
                        variable.getValue());
            }
        }
    }

    private static void methods() {
        var routes = routes();

        Check.equal("HEAD is answered by the GET route, without the table saying so twice",
                "GET",
                methodOf(routes.recognise("HEAD", "/symbols/json.Json")));

        Check.equal("a method the route table spells differently still arrives",
                "symbols",
                routeOf(routes.recognise("get", "/symbols")));

        var wrong = routes.recognise("PUT", "/symbols/json.Json");
        Check.that("a path that is a route but not with this method is not a 404",
                wrong instanceof Recognised.NotAllowed);
        Check.equal("and the answer carries what is allowed, HEAD included",
                List.of("DELETE", "GET", "HEAD"),
                allowedBy(wrong));

        var post = routes.recognise("GET", "/index");
        Check.that("a POST-only route does not answer GET", post instanceof Recognised.NotAllowed);
        Check.equal("and offers only what it has", List.of("POST"), allowedBy(post));
    }

    /// More than one route can match. The rule is fixed text first, then fewer
    /// variables, then whoever was written first — so a table can be reordered
    /// without changing what it does.
    private static void ordering() {
        var literalLast = Router.of().get("symbol", "/symbols/{name}").get("new", "/symbols/new");
        var literalFirst = Router.of().get("new", "/symbols/new").get("symbol", "/symbols/{name}");

        for (var routes : List.of(literalLast, literalFirst)) {
            Check.equal("fixed text wins over a variable, whichever was written first",
                    "new",
                    routeOf(routes.recognise("GET", "/symbols/new")));
        }
        Check.equal("and the table reads most specific first",
                "new",
                literalLast.routes().getFirst().name());

        var first = Router.of().get("left", "/a/{x}").get("right", "/{y}/b");
        var other = Router.of().get("right", "/{y}/b").get("left", "/a/{x}");
        Check.equal("two routes that are equally specific go in the order they were written",
                "left",
                routeOf(first.recognise("GET", "/a/b")));
        Check.equal("which is the only thing that can settle it",
                "right",
                routeOf(other.recognise("GET", "/a/b")));

        var falling = Router.of().post("create", "/symbols/new").get("symbol", "/symbols/{name}");
        var through = falling.recognise("GET", "/symbols/new");
        Check.equal("a more specific route that refuses the method does not stop a less specific one",
                "symbol",
                routeOf(through));
        Check.equal("which then reads the path as what it is", "new", valueOf(through, "name"));
    }

    /// A route table is refused where it is written, not where it is used.
    private static void refusing() {
        Check.throwing("a query cannot be recognised, so a route may not be built on one",
                () -> Router.of().get("search", "/search{?q}"));
        Check.throwing("nor can a reserved expansion", () -> Router.of().get("proxy", "/proxy/{+url}"));
        Check.throwing("nor an explode", () -> Router.of().get("many", "/things/{ids*}"));
        Check.throwing("nor a prefix, which throws away what it would give back",
                () -> Router.of().get("short", "/things/{id:4}"));
        Check.throwing("a template that is not a template is refused too",
                () -> Router.of().get("broken", "/things/{id"));
        Check.throwing("a route needs a name", () -> Router.of().get("", "/things"));
        Check.throwing("and a method", () -> Router.of().route("things", " ", "/things"));

        try {
            Router.of().get("search", "/search{?q}");
        } catch (DispatchException e) {
            Check.that("and the refusal names the route and the template: " + e.getMessage(),
                    e.getMessage().contains("search") && e.getMessage().contains("/search{?q}"));
        }

        Check.throwing("one name cannot mean two URLs",
                () -> Router.of().get("symbol", "/symbols/{name}").get("symbol", "/types/{name}"));
        Check.equal("but one name can mean one URL under several methods",
                "symbol",
                routeOf(Router.of().get("symbol", "/symbols/{name}").delete("symbol", "/symbols/{name}")
                        .recognise("DELETE", "/symbols/x")));
        Check.throwing("and the same route twice is a copy that was not finished",
                () -> Router.of().get("symbol", "/symbols/{name}").get("symbol", "/symbols/{name}"));
    }

    /// The encoding is what makes recognition honest: a value cannot contain
    /// the separator that would end it, because building the URL encoded it.
    private static void encoding() {
        var routes = routes();

        Check.equal("a value with a slash in it is encoded on the way out",
                "/symbols/a%2Fb",
                routes.path("symbol", Map.of("name", "a/b")));
        Check.equal("and decoded on the way back in",
                "a/b",
                valueOf(routes.recognise("GET", "/symbols/a%2Fb"), "name"));
        Check.equal("a space survives the round trip",
                "a b",
                valueOf(routes.recognise("GET", routes.path("symbol", Map.of("name", "a b"))), "name"));
        Check.equal("so does a name that is already full of percent signs",
                "50%",
                valueOf(routes.recognise("GET", routes.path("symbol", Map.of("name", "50%"))), "name"));
        Check.equal("an empty value is a value", "", valueOf(routes.recognise("GET", "/symbols/"), "name"));
    }
}
