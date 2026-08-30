package web;

import harness.Check;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import web.RouteRef;
import web.Router;
import web.serve.Memory;

/// A feature is one package's whole contribution, and this is the check that it
/// really is whole: that naming a feature is enough, and that forgetting to
/// name one is the only way left to be wrong.
public final class FeaturesTest {

    private static final RouteRef HOME = RouteRef.of("home", "/");
    private static final RouteRef LIVE = RouteRef.of("live.stream", "/live");
    private static final RouteRef OPEN = RouteRef.of("open", "/open");
    private static final RouteRef SHUT = RouteRef.of("shut", "/shut");

    private FeaturesTest() {}

    public static void run() throws Exception {
        parts();
        page();
        serving();
        mounting();
        wrapping();
        guarding();
        refusing();
        closing();
    }

    /// Each of the three parts arrives, and a feature with only some of them is
    /// a feature.
    private static void parts() throws Exception {
        var files = tree();
        var full = Feature.named("live")
                .from(files)
                .pin("@app/live", "live.js")
                .get(LIVE, (request, response) -> Responses.text("streaming", response));
        var quiet = Feature.named("design").from(files).pin("@app/design", "design.css");

        var wiring = Features.of(Router.of().get(HOME), full, quiet);

        Check.that("a feature's route is in the table", wiring.router().route(LIVE).isPresent());
        Check.equal("and its path is the one it declared", "/live", wiring.router().path(LIVE));
        Check.that("the application keeps its own routes", wiring.router().route(HOME).isPresent());

        Check.that("a feature's files are on the load path", wiring.assets().find("live.js").isPresent());
        var pins = wiring.importmap().pins();
        Check.equal("a feature's pin is in the import map", "live.js", pins.get("@app/live"));
        Check.equal("including one from a feature with no routes", "design.css", pins.get("@app/design"));
        Check.that("and Hotwired is still pinned for everybody",
                pins.containsKey(web.assets.Hotwired.TURBO));

        Check.equal("a feature's handler answers its route", "streaming",
                Memory.handle(wiring.router(), Memory.get("/live")).text());

        Check.equal("a feature with no routes adds none", 0, quiet.router().routes().size());
        Check.that("and still contributes what it ships", wiring.assets().find("design.css").isPresent());
    }

    /// What a feature says has to be closed, and when.
    ///
    /// Order is the part worth pinning. A feature can be built from something
    /// an earlier one produced, so unwinding forwards would take a resource
    /// away from something still using it — the browser's watcher broadcasts on
    /// the cable, and stopping it after the cable closes is a broadcast into a
    /// closed cable.
    private static void closing() throws Exception {
        var order = new java.util.ArrayList<String>();

        var wiring = Features.of(Router.of(),
                Feature.named("first").closing(() -> order.add("first")),
                Feature.named("second").closing(() -> order.add("second.a")).closing(() -> order.add("second.b")));
        wiring.close();
        Check.equal("the last feature named is closed first, and its own in reverse too",
                List.of("second.b", "second.a", "first"), order);

        wiring.close();
        Check.equal("and closing twice does nothing the second time",
                List.of("second.b", "second.a", "first"), order);

        var closed = new java.util.ArrayList<String>();
        var failing = Features.of(Router.of(),
                Feature.named("early").closing(() -> closed.add("early")),
                Feature.named("angry")
                        .closing(() -> closed.add("after"))
                        .closing(() -> {
                            throw new IllegalStateException("first to go wrong");
                        }));
        try {
            failing.close();
            Check.that("a resource that throws is reported", false);
        } catch (Exception thrown) {
            Check.equal("the first failure is what comes out", "first to go wrong", thrown.getMessage());
        }
        Check.equal("and everything after it is still closed", List.of("after", "early"), closed);

        var both = new java.util.ArrayList<String>();
        var angrier = Features.of(Router.of(), Feature.named("angry")
                .closing(() -> {
                    both.add("second");
                    throw new IllegalStateException("second");
                })
                .closing(() -> {
                    throw new IllegalStateException("first");
                }));
        try {
            angrier.close();
            Check.that("two failures are reported", false);
        } catch (Exception thrown) {
            Check.equal("the first failure is the one thrown", "first", thrown.getMessage());
            Check.equal("and the rest are attached to it rather than lost",
                    List.of("second"),
                    java.util.Arrays.stream(thrown.getSuppressed()).map(Throwable::getMessage).toList());
        }
        Check.equal("a throwing resource does not stop the ones after it", List.of("second"), both);

        Check.that("a feature that owns nothing needs no closing",
                Feature.named("quiet").closing().isEmpty());
    }

    /// What a feature puts in the document, and in what order.
    ///
    /// The order is the whole point of these checks. Stylesheets cascade, so a
    /// feature named after another is a feature whose rules win — and if that
    /// followed the iteration order of a map rather than the order somebody
    /// wrote, a page would render with the right files and the wrong design,
    /// which nothing else here can see.
    private static void page() throws Exception {
        var files = tree();
        var design = Feature.named("design").from(files).stylesheet("design.css");
        var app = Feature.named("app")
                .from(files)
                .stylesheet("live.css")
                .head((assets, routes, out) -> out.write("<meta name=\"app\">"))
                .body((assets, routes, out) -> out.write("<div data-live=\"" + routes.path(LIVE) + "\"></div>"))
                .get(LIVE, (request, response) -> Responses.text("streaming", response));
        var quiet = Feature.named("quiet").from(files).pin("@app/live", "live.js");

        var wiring = Features.of(Router.of(), design, app, quiet);
        var head = written(wiring.head());
        var body = written(wiring.body());

        Check.that("a stylesheet a feature ships is linked",
                head.contains("<link rel=\"stylesheet\" href=\"" + wiring.assets().url("design.css") + "\">"));
        Check.that("and the link carries the digest, so it is the file that was read",
                wiring.assets().url("design.css").contains("-"));
        Check.that("a head contribution is written too", head.contains("<meta name=\"app\">"));
        Check.that("a body contribution lands in the body", body.contains("data-live="));
        Check.equal("and resolves its own route through the composed table",
                "<div data-live=\"/live\"></div>", body);

        Check.that("features are written in the order they were named",
                head.indexOf("design-") < head.indexOf("live-"));
        Check.that("and a feature's own contributions in the order it declared them",
                head.indexOf("live-") < head.indexOf("<meta name=\"app\">"));
        Check.that("the import map comes after every feature, since nothing may import before it",
                head.indexOf("<meta name=\"app\">") < head.indexOf("importmap"));

        Check.that("a feature that contributes nothing to the page writes nothing",
                written(Features.of(Router.of(), quiet).body()).isEmpty());
        Check.that("and still has its pin in the map",
                written(Features.of(Router.of(), quiet).head()).contains("@app/live"));
    }

    private static String written(Markup markup) throws Exception {
        var out = new java.io.StringWriter();
        markup.write(out);
        return out.toString();
    }

    /// The pipeline serves its own URL, and the route agrees with the prefix
    /// rather than repeating it.
    private static void serving() throws Exception {
        var wiring = Features.of(Router.of(), Feature.named("app").from(tree()).pin("@app/live", "live.js"));

        Check.equal("the asset route is built from the pipeline's own prefix",
                wiring.assets().prefix() + "/{file}",
                wiring.router().route(Features.ASSET).orElseThrow().template().text());

        var url = wiring.assets().url("live.js");
        var served = Memory.handle(wiring.router(), Memory.get(url));
        Check.equal("and a digested URL is answered", 200, served.status());
        Check.equal("with the file behind it", "// live\n", served.text());
        Check.equal("a digested asset is cached forever", "public, max-age=31536000, immutable",
                served.header("Cache-Control").orElse(""));
        Check.equal("and a name nobody has is a 404", 404,
                Memory.handle(wiring.router(), Memory.get(wiring.assets().prefix() + "/nope.js")).status());
    }

    /// A feature moved somewhere else is reached there, and every link still
    /// builds itself from the name.
    private static void mounting() throws Exception {
        var moved = Feature.named("live")
                .at("/streams")
                .get(LIVE, (request, response) -> Responses.text("moved", response));
        var wiring = Features.of(Router.of(), moved);

        Check.equal("a mounted feature's path carries its prefix", "/streams/live",
                wiring.router().path(LIVE));
        Check.equal("and the handler answers there", "moved",
                Memory.handle(wiring.router(), Memory.get("/streams/live")).text());
        Check.equal("and no longer where it was declared", 404,
                Memory.handle(wiring.router(), Memory.get("/live")).status());
    }

    /// A feature's middleware wraps everything, in the order the features were
    /// named, and nothing an application does afterwards can drop it.
    private static void wrapping() throws Exception {
        var order = new StringBuilder();
        var outer = Feature.named("outer").wrappedBy(mark(order, "outer"));
        var inner = Feature.named("inner").wrappedBy(mark(order, "inner"));
        var wiring = Features.of(Router.of().get(HOME), outer, inner);

        var app = wiring.router()
                .on(HOME, (request, response) -> Responses.text("home", response))
                .otherwise((request, response) -> Responses.text("nothing", response));

        Check.equal("the handler still answers", "home", Memory.handle(app, Memory.get("/")).text());
        Check.equal("the first feature named is the outermost", "outer inner ", order.toString());

        order.setLength(0);
        Check.equal("a request that matches no route is still answered", "nothing",
                Memory.handle(app, Memory.get("/nowhere")).text());
        Check.equal("and the stack still ran for it — a check a 404 skips is not a check",
                "outer inner ", order.toString());

        order.setLength(0);
        Memory.handle(wiring.router().otherwise((request, response) -> {}), Memory.get("/"));
        Check.equal("otherwise carries the stack forward", "outer inner ", order.toString());

        Handler bare = (request, response) -> {};
        Check.that("features that ask for no wrapper compose to the one that does nothing",
                Features.of(Router.of()).middleware().wrap(bare) == bare);
    }

    /// The two scopes, and the fact that only one of them needed building.
    ///
    /// `web.sessions` already produces both kinds: [web.sessions.Sessions#middleware()]
    /// belongs around the whole application, and [web.sessions.Sessions#required(String)]
    /// guards one route. The second needs nothing from [Feature] — it is spelled
    /// on the handler where the route is named — so there is one way to say each
    /// and they do not overlap.
    private static void guarding() throws Exception {
        var sessions = web.sessions.Sessions.of(web.sessions.Signature.of("a-test-secret-that-is-long-enough"));

        var feature = Feature.named("app")
                .wrappedBy(sessions.middleware())
                .get(OPEN, (request, response) ->
                        Responses.text("session=" + web.sessions.Sessions.of(request).present(), response))
                .get(SHUT,
                        ((Handler) (request, response) -> Responses.text("secret", response))
                                .wrappedBy(sessions.required("/sign-in")));

        var app = Features.of(Router.of(), feature).router()
                .otherwise((request, response) -> Responses.empty(Status.NOT_FOUND, response));

        // That the wrapper runs at all is proven in wrapping(); an absent session
        // and an empty one both read as NONE, so this cannot tell them apart and
        // does not claim to. What it shows is that the stack passes a request
        // through rather than swallowing it.
        Check.equal("a request travels through the application-wide stack to its handler",
                "session=false", Memory.handle(app, Memory.get("/open")).text());
        Check.equal("the route-scoped guard refuses a request with no session",
                303, Memory.handle(app, Memory.get("/shut")).status());
        Check.equal("and it guards only its own route",
                200, Memory.handle(app, Memory.get("/open")).status());
    }

    private static Middleware mark(StringBuilder order, String name) {
        return next -> (request, response) -> {
            order.append(name).append(' ');
            next.handle(request, response);
        };
    }

    private static void refusing() {
        var one = Feature.named("live").get(LIVE, (request, response) -> {});
        var again = Feature.named("live").get(RouteRef.of("other", "/other"), (request, response) -> {});
        Check.that("two features of the same name are refused",
                refused(() -> Features.of(Router.of(), one, again)).contains("live"));

        var clash = Feature.named("other").get(RouteRef.of("live.stream", "/elsewhere"), (request, response) -> {});
        Check.that("and so are two features that name the same route",
                !refused(() -> Features.of(Router.of(), one, clash)).isEmpty());
    }

    private static String refused(Runnable mistake) {
        try {
            mistake.run();
            return "";
        } catch (RuntimeException refused) {
            return String.valueOf(refused.getMessage());
        }
    }

    /// A directory of files a feature could ship.
    private static Path tree() throws Exception {
        var root = Files.createTempDirectory("tuul-feature");
        root.toFile().deleteOnExit();
        Files.writeString(root.resolve("live.js"), "// live\n");
        Files.writeString(root.resolve("design.css"), "body { color: red }\n");
        Files.writeString(root.resolve("live.css"), "body { color: blue }\n");
        return root;
    }
}
