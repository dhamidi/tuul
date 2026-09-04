package browser;

import harness.Check;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import symbols.Index;
import web.assets.Bundled;
import web.assets.Hotwired;
import web.hyperspec.Hyperspec;
import web.hyperspec.Outcome;
import web.serve.Http;
import web.serve.Memory;

/// Everything the browser is, checked at the cheapest level that can see it.
///
/// The updates and the views need nothing at all; the handler needs the
/// in-memory server and no port; only the last of these starts anything. The
/// journeys live in `spec/browse` and are run by [BrowseSpec], because a
/// journey is a description of the application rather than an assertion about
/// one of its parts.
public final class BrowserTest {

    private BrowserTest() {}

    public static void run() throws Exception {
        var sources = sources();
        try (var index = Index.of(List.of(sources), List.of(), index(sources));
             var browser = Browser.of(index, null)) {
            UpdatesTest.run(index);
            ViewsTest.run(browser.routes());
            ResultsTest.run(browser.routes());
            HandlerTest.run(browser);
            assets(browser);
            answers(browser);
        }
        stops(sources);
    }

    /// Shutting down actually shuts down.
    ///
    /// This is the failure mode of moving lifetimes into the features, and it
    /// has no visible symptom: a server that returns from `close` while a
    /// stream is still open, or one that never returns at all. Neither shows up
    /// in a status code, and the second one hangs a test run rather than
    /// failing it — so closing happens on a thread here and the check is that
    /// it finished.
    ///
    /// The browser is built with a file to watch, because the watcher is the
    /// half that only this application has: it is carried by the application's
    /// own feature, and it is the one that must stop before the cable it
    /// broadcasts on.
    private static void stops(Path sources) throws Exception {
        var watched = Files.createTempFile("tuul-browser-watch", ".db");
        watched.toFile().deleteOnExit();

        try (var index = Index.of(List.of(sources), List.of(), index(sources))) {
            var browser = Browser.of(index, watched);
            try (var open = Memory.open(browser.handler(), Memory.get("/updates"))) {
                Check.equal("a page is listening before the shutdown", 1, listening(browser, 1));

                var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
                var closed = new CountDownLatch(1);
                var closing = Thread.ofVirtual().start(() -> {
                    try {
                        browser.close();
                    } catch (Exception thrown) {
                        failure.set(thrown);
                    } finally {
                        closed.countDown();
                    }
                });
                Check.that("closing returns rather than waiting for something that never ends",
                        closed.await(15, TimeUnit.SECONDS));
                Check.that("and does not throw", failure.get() == null);

                Check.equal("every subscription has ended", 0, browser.cable().subscribers());
                Check.that("and the handler holding the stream open has finished",
                        !open.handler().isAlive());
                Check.throwing("the cable really closed, rather than only losing its subscribers",
                        () -> browser.cable().broadcast(Browser.INDEX, web.ui.Turbo.refresh()));

                browser.close();
                Check.equal("closing twice is safe", 0, browser.cable().subscribers());
            }
        }
    }

    /// How many pages the cable has, once it has them. The handler runs on a
    /// thread of its own, so a subscription appears a moment after the response
    /// does — see the longer note in [HandlerTest].
    private static int listening(Browser browser, int wanted) throws InterruptedException {
        browser.cable().awaitSubscribers(wanted, Duration.ofSeconds(2));
        return browser.cable().subscribers();
    }

    /// The application's own assets reach the pipeline from beside its own
    /// code, and tuul's reach it too.
    ///
    /// The point of the first check is the second half of it: `browser.css`
    /// used to be found because it sat in the tree tuul ships, so every
    /// application written with the framework was handed the stylesheet for
    /// browsing a symbol index. It is here now because this application put it
    /// here.
    private static void assets(Browser browser) {
        var assets = browser.assets();
        var mine = Bundled.of(Browser.class, Browser.ASSETS);
        Check.that("the application's own assets are on the load path, from beside its own code",
                assets.loadPaths().contains(mine));
        Check.that("after the components', so this application's stylesheet cascades over theirs",
                assets.loadPaths().indexOf(Bundled.of(web.ui.Ui.class, web.ui.Ui.ASSETS))
                        < assets.loadPaths().indexOf(mine));
        for (var file : List.of(
                "browser.css", "search.js", "page-navigation.js", "kind.js",
                Views.ICON, Views.LOGO, Views.SEARCH_ICON,
                "source-sans-3-latin.woff2", "source-sans-3-latin-ext.woff2",
                "source-sans-3-OFL.txt", "source-serif-4-latin.woff2",
                "source-serif-4-latin-ext.woff2", "source-serif-4-latin-italic.woff2",
                "source-serif-4-latin-ext-italic.woff2", "source-serif-4-OFL.txt")) {
            Check.that(file + " is on the load path", assets.find(file).isPresent());
        }
        Check.that("and Turbo still is, without this application asking for it",
                assets.find(Hotwired.TURBO_FILE).isPresent());
        Check.that("the stylesheet the page links to is really served",
                assets.serve(assets.url("browser.css")).status() == 200);
        for (var font : List.of("source-sans-3-latin.woff2", "source-sans-3-latin-ext.woff2")) {
            var served = assets.serve(assets.url(font));
            Check.equal(font + " is served as a webfont", 200, served.status());
            Check.equal(font + " has the WOFF2 content type", "font/woff2",
                    served.headers().get("Content-Type"));
        }
        for (var font : List.of(
                "source-serif-4-latin.woff2", "source-serif-4-latin-ext.woff2",
                "source-serif-4-latin-italic.woff2", "source-serif-4-latin-ext-italic.woff2")) {
            var served = assets.serve(assets.url(font));
            Check.equal(font + " is served as a webfont", 200, served.status());
            Check.equal(font + " has the WOFF2 content type", "font/woff2",
                    served.headers().get("Content-Type"));
        }
    }

    /// One of the specs in `cases`, by name — a resource, so it is found the
    /// same way wherever the tests are run from.
    private static Outcome spec(String name, URI service) throws IOException {
        return Hyperspec.run(BrowserTest.class, "cases/" + name + ".hyperspec", service);
    }

    /// That the application answers a hyperspec at all, against a live server —
    /// which is the only way a hyperspec runs.
    private static void answers(Browser browser) throws IOException {
        try (var server = Http.start(browser.handler(), 0)) {
            var service = URI.create("http://localhost:" + server.port());
            var arrives = spec("arrives", service);
            Check.that("the front page answers a spec: " + arrives.report(), arrives.ok());

            var broken = spec("asks-for-nothing", service);
            Check.that("a spec that asks for what is not there fails", !broken.ok());
            Check.that("and says what the page did offer",
                    broken.failures().getFirst().found().contains("offers"));
        }
    }

    /// An index of its own, so a test never writes over the one the project is
    /// using and never depends on what happens to be in it.
    private static Path index(Path sources) {
        return sources.resolveSibling("index.db");
    }

    /// A small source tree, indexed for the test. Using tuul's own would make
    /// the test depend on tuul's source, which changes every time somebody
    /// works on it.
    ///
    /// It is small but not trivial: an interface with overloads and a private
    /// member, a class with a supertype and a public field, so that ranking,
    /// numbering, linking and what a page hides all have something to be about.
    static Path sources() throws IOException {
        var root = Files.createTempDirectory("tuul-browser");
        root.toFile().deleteOnExit();
        var sourceRoot = Files.createDirectories(root.resolve("src"));
        Files.writeString(sourceRoot.resolve("module-info.java"), "module browser.fixture { exports json; }\n");
        var sources = Files.createDirectories(sourceRoot.resolve("json"));
        Files.writeString(sources.resolve("Json.java"), """
                package json;

                import java.io.Writer;

                /// A JSON value, for a browser to show.
                public interface Json extends Comparable<Json> {

                    /// Writes this value somewhere.
                    ///
                    /// @param out where it goes
                    /// @return what was written
                    String write(Writer out);

                    /// Writes this value somewhere, indented.
                    String write(Writer out, boolean pretty);

                    @Override
                    default int compareTo(Json other) {
                        return 0;
                    }

                    /// What nobody outside can see.
                    private String hidden() {
                        return "";
                    }
                }
                """);
        Files.writeString(sources.resolve("JsonWriter.java"), """
                package json;

                import java.io.Writer;

                /// Writes JSON to a writer.
                public class JsonWriter extends Writer {

                    /// What separates two values.
                    public static final String NEWLINE = "\\n";

                    @Override
                    public void write(char[] buffer, int from, int length) {}

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                }
                """);
        return root.resolve("src");
    }
}
