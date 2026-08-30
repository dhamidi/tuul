package web.dispatch;

import harness.Check;
import java.util.List;
import web.IDParameter;
import web.Parameter;
import web.Responses;
import web.RouteRef;
import web.Router;
import web.StringParameter;
import web.serve.Memory;

/// Routing has one definition for dispatch and links. These checks keep both
/// directions together and exercise typed path parameters at that boundary.
public final class DispatchTest {

    private static final StringParameter NAME = new StringParameter("name");
    private static final StringParameter MEMBER = new StringParameter("member");
    private static final IDParameter ID = new IDParameter("id");

    private static final RouteRef SYMBOLS = RouteRef.of("symbols", "/symbols");
    private static final RouteRef SYMBOL = RouteRef.of("symbol", "/symbols/{name}", NAME);
    private static final RouteRef MEMBER_ROUTE = RouteRef.of(
            "member", "/symbols/{name}/members/{member}", NAME, MEMBER);
    private static final RouteRef INDEX = RouteRef.of("index", "/index");

    private DispatchTest() {}

    public static void run() throws Exception {
        recognising();
        constructing();
        dispatching();
        typed();
        methods();
        ordering();
        mounting();
        refusing();
        encoding();
    }

    private static Router routes() {
        return Router.of().get(SYMBOLS).get(SYMBOL).get(MEMBER_ROUTE).delete(SYMBOL).post(INDEX);
    }

    private static void recognising() {
        var routes = routes();
        var found = routes.recognise("GET", "/symbols/json.Json");
        Check.equal("a path is recognised as its route", "symbol", nameOf(found));
        Check.equal("raw values survive recognition", "json.Json", valueOf(found, "name"));
        Check.equal("a route with no variables needs none", "symbols",
                nameOf(routes.recognise("GET", "/symbols")));
        Check.that("a missing path is not found",
                routes.recognise("GET", "/nothing") instanceof Recognised.NotFound);
        Check.that("a value cannot swallow its separator",
                routes.recognise("GET", "/symbols/x/members") instanceof Recognised.NotFound);
    }

    private static void constructing() {
        var routes = routes();
        Check.equal("a reference builds its path", "/symbols/json.Json",
                routes.path(SYMBOL.with(NAME, "json.Json")));
        Check.equal("a route with no values builds directly", "/symbols", routes.path(SYMBOLS));
        Check.equal("each typed binding fills one variable", "/symbols/json.Json/members/text",
                routes.path(MEMBER_ROUTE.with(NAME, "json.Json").with(MEMBER, "text")));
        Check.throwing("an unbound value is refused", () -> routes.path(SYMBOL));
        Check.throwing("an unknown reference is refused",
                () -> routes.path(RouteRef.of("unknown", "/unknown")));
    }

    private static void dispatching() throws Exception {
        var post = RouteRef.of("post", "/posts/{id}", ID);
        var router = Router.of().get(post, (request, response) ->
                Responses.text("post=" + ID.get(request), response));
        Check.equal("the router calls the matching handler", "post=7",
                Memory.handle(router, Memory.get("/posts/7")).text());
        Check.equal("the same router returns 404", 404,
                Memory.handle(router, Memory.get("/elsewhere")).status());

        var orphan = Router.of().get(post).otherwise((request, response) ->
                Responses.text("missing " + Router.route(request).orElseThrow().name(), response));
        Check.equal("a defined route without a handler reaches otherwise", "missing post",
                Memory.handle(orphan, Memory.get("/posts/7")).text());
    }

    private static void typed() throws Exception {
        var post = RouteRef.of("post", "/posts/{id}", ID);
        var router = Router.of().get(post, (request, response) -> Responses.text(ID.get(request).toString(), response));
        Check.equal("an ID is parsed before the handler", "42",
                Memory.handle(router, Memory.get("/posts/42")).text());
        Check.equal("an invalid ID does not match the route", 404,
                Memory.handle(router, Memory.get("/posts/nope")).status());
        Check.equal("a non-positive ID does not match the route", 404,
                Memory.handle(router, Memory.get("/posts/0")).status());

        Parameter<Slug> slug = new SlugParameter("slug");
        var article = RouteRef.of("article", "/articles/{slug}", slug);
        var custom = Router.of().get(article, (request, response) ->
                Responses.text(slug.get(request).value(), response));
        Check.equal("an application parameter parses its own type", "hello-world",
                Memory.handle(custom, Memory.get("/articles/hello-world")).text());
        Check.equal("its refusal constrains the route", 404,
                Memory.handle(custom, Memory.get("/articles/Hello")).status());
    }

    private record Slug(String value) {}

    private record SlugParameter(String name) implements Parameter<Slug> {
        @Override
        public Slug parse(String text) {
            if (!text.matches("[a-z0-9-]+")) throw new IllegalArgumentException();
            return new Slug(text);
        }

        @Override
        public String format(Slug slug) {
            return slug.value();
        }
    }

    private static void methods() throws Exception {
        var routes = routes();
        Check.equal("HEAD uses the GET route", "GET", methodOf(routes.recognise("HEAD", "/symbols/x")));
        var wrong = routes.recognise("PUT", "/symbols/x");
        Check.that("a wrong method is not a 404", wrong instanceof Recognised.NotAllowed);
        Check.equal("Allow includes every method and HEAD", List.of("DELETE", "GET", "HEAD"), allowedBy(wrong));

        var answered = Memory.handle(Router.of().post(INDEX, (request, response) -> {}), Memory.get("/index"));
        Check.equal("dispatch returns 405 for a wrong method", 405, answered.status());
        Check.equal("dispatch writes Allow", "POST", answered.header("Allow").orElse(""));
    }

    private static void ordering() {
        var variable = RouteRef.of("symbol", "/symbols/{name}", NAME);
        var fixed = RouteRef.of("new", "/symbols/new");
        for (var router : List.of(Router.of().get(variable).get(fixed), Router.of().get(fixed).get(variable))) {
            Check.equal("fixed text wins regardless of definition order", "new",
                    nameOf(router.recognise("GET", "/symbols/new")));
        }
        Check.equal("the table exposes recognition order", "new",
                Router.of().get(variable).get(fixed).routes().getFirst().name());

        var create = RouteRef.of("create", "/symbols/new");
        var falling = Router.of().post(create).get(variable);
        Check.equal("a specific route with the wrong method does not hide a matching route", "symbol",
                nameOf(falling.recognise("GET", "/symbols/new")));
    }

    private static void mounting() throws Exception {
        var updates = RouteRef.of("cable.updates", "/updates");
        var one = RouteRef.of("cable.one", "/updates/{id}", ID);
        var feature = Router.of().get(updates, (request, response) -> Responses.text("live", response)).get(one);
        var home = RouteRef.of("home", "/");
        var mounted = Router.of().get(home).mount("/live", feature);

        Check.equal("a mounted reference builds under its prefix", "/live/updates", mounted.path(updates));
        Check.equal("a mounted typed reference keeps its value", "/live/updates/7",
                mounted.path(one.with(ID, 7L)));
        Check.equal("a mounted handler answers there", "live",
                Memory.handle(mounted, Memory.get("/live/updates")).text());
        Check.equal("its old path is gone", 404, Memory.handle(mounted, Memory.get("/updates")).status());
        Check.equal("the host route stays where it was", "/", mounted.path(home));
        Check.throwing("a mount prefix is a path", () -> Router.of().mount("live", feature));
    }

    private static void refusing() {
        Check.throwing("an unreadable URI template is refused",
                () -> RouteRef.of("search", "/search{?q}", new StringParameter("q")));
        Check.throwing("a broken template is refused", () -> RouteRef.of("broken", "/{id", ID));
        Check.throwing("a route needs a name", () -> RouteRef.of("", "/"));
        Check.throwing("every template variable needs a parameter",
                () -> RouteRef.of("post", "/posts/{id}"));
        Check.throwing("extra parameters are refused", () -> RouteRef.of("home", "/", ID));
        Check.throwing("parameter order follows template order",
                () -> RouteRef.of("member", "/{name}/{member}", MEMBER, NAME));
        Check.throwing("an invalid typed value cannot build a path",
                () -> RouteRef.of("post", "/posts/{id}", ID).with(ID, 0L));
        Check.throwing("one name cannot mean two paths", () -> Router.of()
                .get(RouteRef.of("same", "/a"))
                .get(RouteRef.of("same", "/b")));
        Check.throwing("the same method cannot be defined twice", () -> Router.of().get(SYMBOL).get(SYMBOL));
    }

    private static void encoding() {
        var routes = routes();
        Check.equal("a slash in a value is encoded", "/symbols/a%2Fb",
                routes.path(SYMBOL.with(NAME, "a/b")));
        Check.equal("an encoded slash is decoded", "a/b",
                valueOf(routes.recognise("GET", "/symbols/a%2Fb"), "name"));
        for (var value : List.of("a b", "50%", "")) {
            Check.equal("a value survives a build-recognise round trip: " + value, value,
                    valueOf(routes.recognise("GET", routes.path(SYMBOL.with(NAME, value))), "name"));
        }
    }

    private static String nameOf(Recognised recognised) {
        return recognised instanceof Recognised.Match match ? match.route().name() : String.valueOf(recognised);
    }

    private static String methodOf(Recognised recognised) {
        return recognised instanceof Recognised.Match match ? match.route().method() : String.valueOf(recognised);
    }

    private static String valueOf(Recognised recognised, String name) {
        return recognised instanceof Recognised.Match match ? match.value(name) : String.valueOf(recognised);
    }

    private static List<String> allowedBy(Recognised recognised) {
        return recognised instanceof Recognised.NotAllowed refused ? refused.allowed() : List.of();
    }
}
