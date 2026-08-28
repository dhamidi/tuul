package browser;

import harness.Check;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import symbols.Index;
import web.assets.Bundled;
import web.assets.Hotwired;
import web.hyperspec.Hyperspec;
import web.hyperspec.Outcome;
import web.serve.Http;

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
        Check.equal("the application's own assets come first, from beside its own code",
                Bundled.of(Browser.class, Browser.ASSETS), assets.loadPaths().getFirst());
        for (var mine : List.of("browser.css", "search.js", "kind.js", Views.ICON)) {
            Check.that(mine + " is on the load path", assets.find(mine).isPresent());
        }
        Check.that("and Turbo still is, without this application asking for it",
                assets.find(Hotwired.TURBO_FILE).isPresent());
        Check.that("the stylesheet the page links to is really served",
                assets.serve(assets.url("browser.css")).status() == 200);
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
        var sources = Files.createDirectories(root.resolve("src").resolve("json"));
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
