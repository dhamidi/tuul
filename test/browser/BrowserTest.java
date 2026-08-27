package browser;

import harness.Check;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import json.Json;
import symbols.Index;
import web.Headers;
import web.hyperspec.Hyperspec;
import web.hyperspec.Outcome;
import web.serve.Http;
import web.serve.Memory;

public final class BrowserTest {

    private BrowserTest() {}

    public static void run() throws Exception {
        var sources = sources();
        try (var index = Index.of(List.of(sources), List.of(), index(sources));
             var browser = Browser.of(index, null)) {
            updates(browser);
            results(browser);
            negotiates(browser);
            missing(browser);
            journey(browser);
        }
    }

    /// The updates an application asks for are arithmetic on values: no server,
    /// no index, no disk. That is the whole reason a page is an `application`
    /// and not a handler that does the work itself.
    private static void updates(Browser browser) {
        var asked = Symbols.asked(Symbols.Symbol.nothing(),
                application.Message.of(Routes.SYMBOL, params(Map.of("name", "json.Json"))));
        Check.equal("asking for a symbol asks the index for it", 1, asked.effects().size());
        Check.equal("and remembers what was asked for", "json.Json", asked.state().name());

        var nothing = Symbols.asked(Symbols.Symbol.nothing(), application.Message.of(Routes.SYMBOL, params(Map.of())));
        Check.that("a request naming no symbol asks the index nothing", nothing.effects().isEmpty());
        Check.that("and says why", !nothing.state().problem().isEmpty());

        var empty = Symbols.searched(Symbols.Found.nothing(), application.Message.of(Routes.HOME, params(Map.of())));
        Check.that("an empty search is the front page, not a failure", empty.effects().isEmpty());
        Check.that("and has nothing to say about itself", empty.state().problem().isEmpty());

        var typed = Symbols.searched(Symbols.Found.nothing(),
                application.Message.of(Routes.SEARCH, params(Map.of("q", "json"))));
        Check.equal("a search that was typed asks the index once", 1, typed.effects().size());
        Check.equal("and keeps the question, so the box still holds it", "json", typed.state().query());
    }

    /// What a search result is for: being clicked. Each of these was a way the
    /// results looked right and led nowhere.
    private static void results(Browser browser) {
        var found = Memory.handle(browser.handler(), Memory.get("/search?q=write")).text();

        Check.that("a result link leaves the frame it is in, rather than asking a page for one",
                found.contains("data-turbo-frame=\"_top\""));
        Check.that("a member links to its type's page and the place on it",
                found.contains("href=\"/symbols/json.Json#write\""));

        var page = Memory.handle(browser.handler(), Memory.get("/symbols/json.Json"));
        Check.equal("which is a page that exists", 200, page.status());
        Check.that("and that place is on it", page.text().contains("<li id=\"write\""));
        Check.that("an overload gets an id of its own, because two cannot share one",
                page.text().contains("<li id=\"write-2\""));
        Check.that("a type in a signature is a link, so a return type can be followed",
                page.text().contains("href=\"/symbols/java.io.Writer\""));

        Check.equal("overloads are one result, since they are one place",
                1, found.split("href=\"/symbols/json.Json#write\"", -1).length - 1);
        Check.that("a private member is not offered at all",
                !Memory.handle(browser.handler(), Memory.get("/search?q=hidden")).text().contains("#hidden"));

        var frame = Memory.handle(browser.handler(),
                Memory.request("GET", "/search?q=write", Headers.of("Turbo-Frame", Views.RESULTS), ""));
        Check.that("a frame request is answered with the frame", frame.text().startsWith("<turbo-frame"));
        Check.that("and not with a document Turbo would throw away", !frame.text().contains("<html"));
        Check.that("while a request that is not one still gets the whole page", found.contains("<html"));

        var punctuation = Memory.handle(browser.handler(), Memory.get("/search?q=..."));
        Check.equal("a query with no words in it is answered, not failed", 200, punctuation.status());
        Check.that("and says nothing matched rather than showing a database error",
                !punctuation.text().contains("fts5"));
    }

    /// The same URL answers a person and an agent differently, which is the
    /// point of a documentation browser in a toolchain whose main user is an
    /// agent.
    private static void negotiates(Browser browser) {
        var page = Memory.handle(browser.handler(),
                Memory.request("GET", "/symbols/json.Json", Headers.of("Accept", "text/html"), ""));
        Check.equal("a browser is answered with a page", 200, page.status());
        Check.that("which is HTML", page.headers().first("Content-Type").orElse("").startsWith("text/html"));
        Check.that("and describes the symbol as a resource", page.text().contains("itemtype=\"/Symbol\""));

        var agent = Memory.handle(browser.handler(),
                Memory.request("GET", "/symbols/json.Json", Headers.of("Accept", "application/json"), ""));
        Check.that("an agent is answered with JSON",
                agent.headers().first("Content-Type").orElse("").startsWith("application/json"));
        Check.equal("and it is the description tuul docs prints", "json.Json",
                Json.parse(agent.text()) instanceof Json.Object described ? described.string("class", "") : "");
    }

    private static void missing(Browser browser) {
        var unknown = Memory.handle(browser.handler(), Memory.get("/symbols/nothing.At.All"));
        Check.equal("a symbol that does not exist is a 404", 404, unknown.status());
        Check.that("and still offers the search box", unknown.text().contains("name=\"q\""));

        var nowhere = Memory.handle(browser.handler(), Memory.get("/nowhere"));
        Check.equal("so is a page that does not exist", 404, nowhere.status());
    }

    /// That the application answers a hyperspec at all, against a live server —
    /// which is the only way a hyperspec runs.
    ///
    /// The journey itself lives in `spec/browse` now, as files somebody can
    /// edit without a compiler, run by [BrowseSpec].
    /// One of the specs in `cases`, by name — a resource, so it is found the
    /// same way wherever the tests are run from.
    private static Outcome spec(String name, URI service) throws IOException {
        return Hyperspec.run(BrowserTest.class, "cases/" + name + ".hyperspec", service);
    }

    private static void journey(Browser browser) throws IOException {
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

    private static Json.Object params(Map<String, String> values) {
        var params = Json.Object.of();
        for (var entry : values.entrySet()) params = params.with(entry.getKey(), entry.getValue());
        return Json.Object.of().with("params", params);
    }

    /// An index of its own, so a test never writes over the one the project is
    /// using and never depends on what happens to be in it.
    private static Path index(Path sources) throws IOException {
        return sources.resolveSibling("index.db");
    }

    /// A small source tree, indexed for the test. Using tuul's own would make
    /// the test depend on tuul's source, which changes every time somebody
    /// works on it.
    private static Path sources() throws IOException {
        var root = Files.createTempDirectory("tuul-browser");
        root.toFile().deleteOnExit();
        var sources = Files.createDirectories(root.resolve("src").resolve("json"));
        Files.writeString(sources.resolve("Json.java"), """
                package json;

                /// A JSON value, for a browser to show.
                public interface Json {

                    /// Writes this value somewhere.
                    ///
                    /// @param out where it goes
                    /// @return what was written
                    String write(java.io.Writer out);

                    /// Writes this value somewhere, indented.
                    String write(java.io.Writer out, boolean pretty);

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
