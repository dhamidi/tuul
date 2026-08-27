package browser;

import harness.Check;
import java.io.BufferedReader;
import java.util.List;
import java.util.Map;
import json.Json;
import web.Headers;
import web.serve.Memory;

/// The handler, through the in-memory server: no socket, no port, no thread.
///
/// This is where routing, negotiation and the frame protocol are decided, and
/// all three are things a browser sees and a unit test of a view cannot. That
/// they can be checked here at all is the return on `web` being interfaces
/// rather than a server — a test that needed a port would be a test somebody
/// eventually stops running.
public final class HandlerTest {

    private HandlerTest() {}

    public static void run(Browser browser) throws Exception {
        routes(browser);
        pages(browser);
        frames(browser);
        negotiates(browser);
        missing(browser);
        assets(browser);
        updates(browser);
    }

    /// Every route the application has, answered. A route that exists and
    /// answers nothing is a route somebody will link to.
    private static void routes(Browser browser) {
        var routes = browser.routes();
        for (var name : List.of(Routes.HOME, Routes.SEARCH, Routes.UPDATES, Routes.FAVICON)) {
            Check.that("the router knows the route " + name, routes.route(name).isPresent());
        }
        Check.equal("a symbol's path is built from its name", "/symbols/json.Json",
                routes.path(Routes.SYMBOL, Map.of("name", "json.Json")));
        Check.that("and reading it back finds the route that built it",
                routes.recognise("GET", routes.path(Routes.SYMBOL, Map.of("name", "json.Json")))
                        instanceof web.dispatch.Recognised.Match match
                        && match.route().name().equals(Routes.SYMBOL));

        Check.equal("the front page answers", 200, Memory.handle(browser.handler(), Memory.get("/")).status());
        Check.equal("so does a search", 200, Memory.handle(browser.handler(), Memory.get("/search?q=json")).status());
        Check.equal("so does a symbol", 200,
                Memory.handle(browser.handler(), Memory.get("/symbols/json.Json")).status());
        Check.equal("so does the icon", 200, Memory.handle(browser.handler(), Memory.get("/favicon.ico")).status());
    }

    /// What a whole page is: a document, once, with the things that make it
    /// interactive.
    private static void pages(Browser browser) {
        var home = Memory.handle(browser.handler(), Memory.get("/"));
        var text = home.text();

        Check.that("a page is a document", text.startsWith("<!DOCTYPE html>"));
        Check.equal("with one root element, not two", 1, count(text, "<html"));
        Check.that("declaring its language", text.contains("<html lang=\"en\">"));
        Check.that("it says where its icon is", text.contains("rel=\"icon\""));
        Check.that("and its stylesheet", text.contains("rel=\"stylesheet\""));
        Check.that("it pins its modules, or nothing on the page would run",
                text.contains("type=\"importmap\""));
        Check.that("including Turbo", text.contains("@hotwired/turbo"));
        Check.that("and Stimulus", text.contains("@hotwired/stimulus"));
        Check.that("it starts them", text.contains("type=\"module\""));
        Check.that("it listens for the index being rebuilt", text.contains("data-controller=\"cable-stream\""));
        Check.that("it offers the search box", text.contains("name=\"q\""));
        Check.that("and a way back to the front page", text.contains("href=\"/\""));
        Check.that("the front page holds no results yet", !text.contains("itemtype=\"/Symbol\""));

        var searched = Memory.handle(browser.handler(), Memory.get("/search?q=write")).text();
        Check.that("a search answers with results", searched.contains("itemtype=\"/Symbol\""));
        Check.that("and holds the question, so the box is not emptied by the answer",
                searched.contains("value=\"write\""));

        var bare = Memory.handle(browser.handler(), Memory.get("/search"));
        Check.equal("a search with nothing typed is the front page", 200, bare.status());
        Check.that("holding no results", !bare.text().contains("itemtype=\"/Symbol\""));

        var punctuation = Memory.handle(browser.handler(), Memory.get("/search?q=..."));
        Check.equal("a query with no words in it is answered, not failed", 200, punctuation.status());
        Check.that("and says nothing matched rather than leaking what the database said",
                !punctuation.text().contains("fts5"));

        var overloads = Memory.handle(browser.handler(), Memory.get("/search?q=write")).text();
        Check.equal("overloads are one result, since they are one place",
                1, count(overloads, "href=\"/symbols/json.Json#write\""));
        Check.that("a private member is not offered at all",
                !Memory.handle(browser.handler(), Memory.get("/search?q=hidden")).text().contains("#hidden"));
    }

    /// The frame protocol, which is the part that fails silently when it is
    /// wrong: a browser asks for a panel and gets a document, or asks for a
    /// panel and gets one that is not there.
    private static void frames(Browser browser) {
        var whole = Memory.handle(browser.handler(), Memory.get("/search?q=write")).text();
        var frame = Memory.handle(browser.handler(),
                Memory.request("GET", "/search?q=write", Headers.of("Turbo-Frame", Views.RESULTS), "")).text();

        Check.that("a frame request is answered with the frame", frame.startsWith("<turbo-frame"));
        Check.that("and not with a document Turbo would throw away", !frame.contains("<html"));
        Check.that("while a request that is not one still gets the whole page", whole.contains("<html"));
        Check.that("both carry the same panel", whole.contains("<turbo-frame id=\"results\""));
        Check.that("and the same results in it", frame.contains("itemtype=\"/Symbol\""));
        Check.that("the frame is the smaller of the two", frame.length() < whole.length());

        var symbol = Memory.handle(browser.handler(), Memory.get("/symbols/json.Json")).text();
        Check.that("a symbol page carries no results panel, which is why a result link must leave it",
                !symbol.contains("<turbo-frame id=\"" + Views.RESULTS + "\""));
        Check.that("so every result link says it is leaving",
                count(whole, "href=\"/symbols/") == count(whole, "data-turbo-frame=\"_top\""));
    }

    /// One URL, two readers. This is why the application exists in a toolchain
    /// whose main user is an agent.
    private static void negotiates(Browser browser) {
        var page = Memory.handle(browser.handler(),
                Memory.request("GET", "/symbols/json.Json", Headers.of("Accept", "text/html"), ""));
        Check.equal("a browser is answered with a page", 200, page.status());
        Check.that("which is HTML", page.header("Content-Type").orElse("").startsWith("text/html"));
        Check.that("and describes the symbol as a resource", page.text().contains("itemtype=\"/Symbol\""));

        var agent = Memory.handle(browser.handler(),
                Memory.request("GET", "/symbols/json.Json", Headers.of("Accept", "application/json"), ""));
        Check.that("an agent is answered with JSON",
                agent.header("Content-Type").orElse("").startsWith("application/json"));
        Check.equal("and it is the description tuul docs prints", "json.Json", described(agent.text()));
        Check.that("carrying the members too, not just the name", !members(agent.text()).isEmpty());

        var browserish = Memory.handle(browser.handler(), Memory.request("GET", "/symbols/json.Json",
                Headers.of("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), ""));
        Check.that("what a browser actually sends is answered with a page",
                browserish.header("Content-Type").orElse("").startsWith("text/html"));

        var anything = Memory.handle(browser.handler(),
                Memory.request("GET", "/symbols/json.Json", Headers.of("Accept", "*/*"), ""));
        Check.that("something with no preference gets the page, since a person is the likelier reader",
                anything.header("Content-Type").orElse("").startsWith("text/html"));

        var silent = Memory.handle(browser.handler(), Memory.get("/symbols/json.Json"));
        Check.that("and so does something that asked for nothing at all",
                silent.header("Content-Type").orElse("").startsWith("text/html"));

        var absent = Memory.handle(browser.handler(), Memory.request("GET", "/symbols/nothing.At.All",
                Headers.of("Accept", "application/json"), ""));
        Check.equal("an agent asking for a symbol nobody has is told so in its own terms", 404, absent.status());
        Check.that("in JSON", absent.header("Content-Type").orElse("").startsWith("application/json"));
    }

    /// The two ways to arrive nowhere, both of which still leave somebody able
    /// to carry on.
    private static void missing(Browser browser) {
        var unknown = Memory.handle(browser.handler(), Memory.get("/symbols/nothing.At.All"));
        Check.equal("a symbol that does not exist is a 404", 404, unknown.status());
        Check.that("and still offers the search box", unknown.text().contains("name=\"q\""));
        Check.that("saying what went wrong as a resource", unknown.text().contains("itemtype=\"/Problem\""));
        Check.that("rather than as a symbol nobody has", !unknown.text().contains("itemtype=\"/Symbol\""));

        var nowhere = Memory.handle(browser.handler(), Memory.get("/nowhere"));
        Check.equal("so is a page that does not exist", 404, nowhere.status());
        Check.that("which also offers the search box", nowhere.text().contains("name=\"q\""));
        Check.that("and says what was not there", nowhere.text().contains("/nowhere"));
    }

    /// Assets, which are the one thing here a browser fetches more than once
    /// and should never fetch twice.
    private static void assets(Browser browser) {
        var url = browser.assets().url("browser.css");
        var css = Memory.handle(browser.handler(), Memory.get(url));
        Check.equal("a digested asset is served", 200, css.status());
        Check.that("as the kind of thing it is", css.header("Content-Type").orElse("").startsWith("text/css"));
        Check.that("cacheable for good, since its name changes when it does",
                css.header("Cache-Control").orElse("").contains("immutable"));
        Check.that("and tagged, so a client can ask whether it changed", css.header("ETag").isPresent());

        var again = Memory.handle(browser.handler(),
                Memory.request("GET", url, Headers.of("If-None-Match", css.header("ETag").orElse("")), ""));
        Check.equal("a client that already has it is told so rather than sent it again", 304, again.status());
        Check.equal("with nothing in the answer", 0, again.text().length());

        var icon = Memory.handle(browser.handler(), Memory.get("/favicon.ico"));
        Check.equal("the icon is answered at the path every client asks for", 200, icon.status());
        Check.that("and is an icon", icon.header("Content-Type").orElse("").contains("svg"));

        var wrong = Memory.handle(browser.handler(), Memory.get("/assets/browser-0000000000000000.css"));
        Check.equal("an asset nobody has is a 404", 404, wrong.status());
    }

    /// The live stream, which has to hold open rather than answer.
    private static void updates(Browser browser) throws Exception {
        try (var open = Memory.open(browser.handler(), Memory.get("/updates"))) {
            Check.equal("the update stream answers", 200, open.status());
            Check.that("as an event stream",
                    open.headers().first("Content-Type").orElse("").startsWith("text/event-stream"));
            Check.equal("with one page listening", 1, browser.cable().subscribers());

            browser.cable().broadcast(Browser.INDEX, web.ui.Turbo.refresh());
            var reader = new BufferedReader(open.reader());
            var lines = new StringBuilder();
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                lines.append(line).append('\n');
                if (line.isEmpty() && lines.toString().contains("turbo-stream")) break;
            }
            Check.that("and a rebuilt index reaches it", lines.toString().contains("turbo-stream"));
            Check.that("as something Turbo acts on rather than a message to read",
                    lines.toString().contains("refresh"));
        }
    }

    /// What the answer says it describes, or nothing when the answer was not
    /// JSON at all.
    ///
    /// Parsing defensively rather than letting it throw is the difference
    /// between a check that fails and a run that stops: an answer that is
    /// suddenly HTML is precisely what this is here to notice, and noticing it
    /// by throwing would take every test after this one down with it.
    private static String described(String body) {
        return parsed(body).map(described -> described.string("class", "")).orElse("");
    }

    private static List<Json> members(String body) {
        return parsed(body).map(described -> described.list("methods")).orElse(List.of());
    }

    private static java.util.Optional<Json.Object> parsed(String body) {
        try {
            return Json.parse(body) instanceof Json.Object described
                    ? java.util.Optional.of(described)
                    : java.util.Optional.empty();
        } catch (RuntimeException notJson) {
            return java.util.Optional.empty();
        }
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
