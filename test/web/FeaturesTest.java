package web;

import harness.Check;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import web.dispatch.Router;
import web.serve.Memory;

/// A feature is one package's whole contribution, and this is the check that it
/// really is whole: that naming a feature is enough, and that forgetting to
/// name one is the only way left to be wrong.
public final class FeaturesTest {

    private FeaturesTest() {}

    public static void run() throws Exception {
        parts();
        serving();
        mounting();
        refusing();
    }

    /// Each of the three parts arrives, and a feature with only some of them is
    /// a feature.
    private static void parts() throws Exception {
        var files = tree();
        var full = Feature.named("live")
                .from(files)
                .pin("@app/live", "live.js")
                .get("live.stream", "/live", (request, response) -> Responses.text("streaming", response));
        var quiet = Feature.named("design").from(files).pin("@app/design", "design.css");

        var wiring = Features.of(Router.of().get("home", "/"), full, quiet);

        Check.that("a feature's route is in the table", wiring.routes().route("live.stream").isPresent());
        Check.equal("and its path is the one it declared", "/live", wiring.routes().path("live.stream"));
        Check.that("the application keeps its own routes", wiring.routes().route("home").isPresent());

        Check.that("a feature's files are on the load path", wiring.assets().find("live.js").isPresent());
        var pins = wiring.importmap().pins();
        Check.equal("a feature's pin is in the import map", "live.js", pins.get("@app/live"));
        Check.equal("including one from a feature with no routes", "design.css", pins.get("@app/design"));
        Check.that("and Hotwired is still pinned for everybody",
                pins.containsKey(web.assets.Hotwired.TURBO));

        Check.equal("a feature's handler answers its route", "streaming",
                Memory.handle(wiring.routing(), Memory.get("/live")).text());

        Check.equal("a feature with no routes adds none", 0, quiet.routes().routes().size());
        Check.that("and still contributes what it ships", wiring.assets().find("design.css").isPresent());
    }

    /// The pipeline serves its own URL, and the route agrees with the prefix
    /// rather than repeating it.
    private static void serving() throws Exception {
        var wiring = Features.of(Router.of(), Feature.named("app").from(tree()).pin("@app/live", "live.js"));

        Check.equal("the asset route is built from the pipeline's own prefix",
                wiring.assets().prefix() + "/{file}",
                wiring.routes().route(Features.ASSET).orElseThrow().template().text());

        var url = wiring.assets().url("live.js");
        var served = Memory.handle(wiring.routing(), Memory.get(url));
        Check.equal("and a digested URL is answered", 200, served.status());
        Check.equal("with the file behind it", "// live\n", served.text());
        Check.equal("a digested asset is cached forever", "public, max-age=31536000, immutable",
                served.header("Cache-Control").orElse(""));
        Check.equal("and a name nobody has is a 404", 404,
                Memory.handle(wiring.routing(), Memory.get(wiring.assets().prefix() + "/nope.js")).status());
    }

    /// A feature moved somewhere else is reached there, and every link still
    /// builds itself from the name.
    private static void mounting() throws Exception {
        var moved = Feature.named("live")
                .at("/streams")
                .get("live.stream", "/live", (request, response) -> Responses.text("moved", response));
        var wiring = Features.of(Router.of(), moved);

        Check.equal("a mounted feature's path carries its prefix", "/streams/live",
                wiring.routes().path("live.stream"));
        Check.equal("and the handler answers there", "moved",
                Memory.handle(wiring.routing(), Memory.get("/streams/live")).text());
        Check.equal("and no longer where it was declared", 404,
                Memory.handle(wiring.routing(), Memory.get("/live")).status());
    }

    private static void refusing() {
        var one = Feature.named("live").get("live.stream", "/live", (request, response) -> {});
        var again = Feature.named("live").get("other", "/other", (request, response) -> {});
        Check.that("two features of the same name are refused",
                refused(() -> Features.of(Router.of(), one, again)).contains("live"));

        var clash = Feature.named("other").get("live.stream", "/elsewhere", (request, response) -> {});
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
        return root;
    }
}
