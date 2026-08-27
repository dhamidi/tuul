package web.hyperspec;

import static web.ui.Attributes.*;
import static web.ui.Tags.*;

import harness.Check;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import web.Handler;
import web.Request;
import web.Response;
import web.Routing;
import web.dispatch.Router;
import web.serve.Http;
import web.ui.Html;

public final class HyperspecTest {

    private HyperspecTest() {}

    public static void run() throws Exception {
        syntax();
        refusals();
        markup();
        affordances();
        journey();
        together();
        failures();
    }

    /// Tcl's shape: words, quoting, braces, substitution, and a line number on
    /// every command, because a failure has to say where.
    private static void syntax() {
        var script = Syntax.parse("""
                # a comment
                visit /notes
                expect status 200 ;# after a semicolon, this is a comment

                set id [attribute note id]
                fill title "a $kind note"
                client alice { visit / }
                """);
        Check.equal("a comment is not a command", 5, script.commands().size());
        Check.equal("a command knows its line", 2, script.commands().getFirst().line());
        Check.equal("and so does one after a blank line", 5, script.commands().get(2).line());
        Check.equal("a command after a braced body knows its line too", 7, script.commands().get(4).line());

        var quoted = script.commands().get(3).words().get(2);
        Check.equal("a quoted word is its pieces",
                List.of(new Script.Part.Text("a "), new Script.Part.Variable("kind"), new Script.Part.Text(" note")),
                quoted.parts());

        var substituted = script.commands().get(2).words().get(2).parts().getFirst();
        Check.that("a bracketed word is a script of its own",
                substituted instanceof Script.Part.Substitution(var nested)
                        && nested.commands().getFirst().name().equals("attribute"));
        Check.equal("a nested command reports the line it was written on",
                5, ((Script.Part.Substitution) substituted).script().commands().getFirst().line());

        Check.that("a braced word is source, not a value", script.commands().get(4).words().get(2).braced());
        Check.equal("and keeps what is inside it",
                " visit / ", script.commands().get(4).words().get(2).body());

        var separated = Syntax.parse("visit /; expect status 200");
        Check.equal("a semicolon ends a command", 2, separated.commands().size());
        Check.equal("both on the same line", 1, separated.commands().get(1).line());

        var escapes = Syntax.parse("set a \"one\\ntwo \\$notavariable\"");
        Check.equal("a backslash escape is resolved where it is written",
                "one\ntwo $notavariable",
                literal(escapes.commands().getFirst().words().get(2)));

        var braces = Syntax.parse("client a { client b { visit / } }");
        Check.equal("braces nest", " client b { visit / } ", braces.commands().getFirst().words().get(2).body());
    }

    private static void refusals() {
        Check.throwing("an unclosed brace is refused", () -> Syntax.parse("client a { visit /"));
        Check.throwing("an unclosed quote is refused", () -> Syntax.parse("set a \"one"));
        Check.throwing("an unclosed bracket is refused", () -> Syntax.parse("set a [status"));
        try {
            Syntax.parse("visit /\nset a \"one\n");
        } catch (SpecException e) {
            Check.that("something left open says so rather than pointing past the end: " + e.getMessage(),
                    e.getMessage().contains("still open") && e.getMessage().contains("\""));
        }
        try {
            Syntax.parse("visit /\nexpect status ]200");
        } catch (SpecException e) {
            Check.that("and a character nobody expected is named where it is: " + e.getMessage(),
                    e.getMessage().startsWith("line 2: column 15:") && e.getMessage().contains("\"]\""));
        }
    }

    /// Enough HTML to find what a page offers, and nothing that pretends to be
    /// a browser.
    private static void markup() {
        var page = Document.read("""
                <!doctype html>
                <html><head><script>var a = "<a href=/ghost>ghost</a>";</script>
                <style>a { color: red }</style></head>
                <body>
                  <a href="/one" rel='next'>First &amp; foremost</a>
                  <img src=/logo.png alt=logo>
                  <input type="checkbox" name="agree" checked>
                  <p>2 &lt; 3 and a stray < angle</p>
                  </div>
                </body></html>
                """);
        Check.equal("a script's content is not markup", 1, page.find("a").size());
        Check.equal("neither is a style's", 1, page.find("a").size());
        Check.equal("entities are decoded", "First & foremost", page.find("a").getFirst().text());
        Check.equal("single quotes are quotes", "next", page.find("a").getFirst().attribute("rel", ""));
        Check.equal("an unquoted value is a value", "/logo.png", page.find("img").getFirst().attribute("src", ""));
        Check.that("a boolean attribute is present without a value", page.find("input").getFirst().has("checked"));
        Check.equal("a void element takes no children", 0, page.find("img").getFirst().content().size());
        Check.equal("a stray angle bracket is text", "2 < 3 and a stray < angle", page.find("p").getFirst().text());
        Check.equal("an end tag that closes nothing is ignored", 1, page.find("body").size());
        Check.equal("text reads in document order",
                "Hello there", Document.read("<p><b>Hello</b> there</p>").find("p").getFirst().text());
    }

    /// What a page offers, which is the only thing a spec is allowed to assert
    /// on.
    private static void affordances() {
        var page = page("""
                <a href="/notes" rel="index">All notes</a>
                <a href="/settings" aria-label="Settings"><svg></svg></a>
                <form id="new" action="/notes" method="post">
                  <input type="text" name="title" value="draft">
                  <input type="checkbox" name="pin">
                  <input type="checkbox" name="public" checked>
                  <input type="hidden" name="token" value="abc">
                  <select name="colour"><option value="red">Red<option value="blue" selected>Blue</select>
                  <textarea name="body">hello</textarea>
                  <button type="submit">Create</button>
                </form>
                <article itemscope itemtype="https://tuul.dev/Note">
                  <h1 itemprop="title">First</h1>
                  <a itemprop="url" href="/notes/1">read</a>
                  <time itemprop="written" datetime="2026-08-28">yesterday</time>
                  <span itemprop="author" itemscope itemtype="https://tuul.dev/User">
                    <span itemprop="name">alice</span>
                  </span>
                </article>
                """);

        Check.equal("a link is found by what it is called", "/notes", page.link("All notes").orElseThrow().href());
        Check.equal("or by its rel", "/notes", page.link("index").orElseThrow().href());
        Check.equal("a link with no text is called what it tells assistive technology",
                "/settings", page.link("Settings").orElseThrow().href());
        Check.that("a link that is not there is not found", page.link("Sign out").isEmpty());

        var form = page.form("new").orElseThrow();
        Check.equal("a form knows where it goes", "/notes", form.action());
        Check.equal("and how", "POST", form.method());
        Check.equal("the only form needs no name", form.action(), page.form("").orElseThrow().action());
        Check.equal("a form sends what it is filled with by default",
                Map.of("title", "draft", "public", "on", "token", "abc", "colour", "blue", "body", "hello"),
                form.values());
        Check.that("an unchecked box sends nothing", !form.values().containsKey("pin"));
        Check.that("a submit button is not a value", !form.values().containsKey("submit"));
        Check.equal("a select sends the option marked selected", "blue", form.field("colour").orElseThrow().value());

        var note = page.item("note").orElseThrow();
        Check.equal("a resource is named by the last part of its type", "note", note.type());
        Check.equal("a property is the text it wraps", "First", note.attribute("title").orElseThrow());
        Check.equal("an anchor property is where it points", "/notes/1", note.attribute("url").orElseThrow());
        Check.equal("a time property is the date a machine can read",
                "2026-08-28", note.attribute("written").orElseThrow());
        Check.that("a nested resource is not the outer one's property",
                note.attribute("name").isEmpty());
        Check.equal("it is a resource of its own", "alice",
                page.item("user").orElseThrow().attribute("name").orElseThrow());
        Check.equal("a page knows how many of a resource it describes", 1, page.items("note").size());
    }

    /// The whole point: a journey across pages, against a service that is
    /// actually running.
    private static void journey() throws Exception {
        try (var server = Http.start(notes(), 0)) {
            var outcome = Hyperspec.run("""
                    visit /
                    expect status 200
                    expect link "Sign in"
                    expect no link "New note"

                    follow "Sign in"
                    expect form sign-in
                    expect field sign-in who
                    fill who alice
                    submit
                    expect status 303
                    expect redirect /notes

                    follow redirect
                    expect at /notes
                    follow "New note"
                    fill title "First note"
                    submit
                    follow redirect

                    expect item note
                    expect attribute note title "First note"
                    expect attribute note author alice
                    set id [attribute note id]
                    visit /notes/$id
                    expect attribute note title "First note"
                    expect requests 8
                    """, URI.create("http://localhost:" + server.port()));

            Check.that("a journey across five pages passes: " + outcome.report(), outcome.ok());
            Check.equal("and every expectation is counted", 13, outcome.passed());
        }
    }

    /// Two people at once, each with a session of their own — which is what
    /// makes "did what she did show up for him" a question a spec can ask.
    private static void together() throws Exception {
        try (var server = Http.start(notes(), 0)) {
            var outcome = Hyperspec.run("""
                    client alice {
                        visit /session/new
                        fill who alice
                        submit
                        follow redirect
                        follow "New note"
                        fill title "by alice"
                        submit
                        follow redirect
                        expect attribute note author alice
                    }
                    concurrently {
                        client bob { visit /notes ; expect item note ; expect no link "Sign out" }
                        client carol { visit /notes ; expect item note }
                    }
                    client bob {
                        expect requests 1
                        follow "New note"
                        fill title "by bob"
                        submit
                        follow redirect
                        expect attribute note author nobody
                    }
                    """, URI.create("http://localhost:" + server.port()));

            Check.that("two clients run at once and both are asked: " + outcome.report(), outcome.ok());
            Check.equal("every client's expectations are counted", 6, outcome.passed());
            Check.that("what one client wrote, another one sees",
                    outcome.checks().stream().anyMatch(check -> check.client().equals("carol")));
            Check.that("a client that never signed in is a different person",
                    outcome.checks().stream().anyMatch(check ->
                            check.client().equals("bob") && check.what().contains("author is nobody")));
            Check.that("a client picked up again remembers what it did",
                    outcome.checks().stream().anyMatch(check ->
                            check.client().equals("bob") && check.what().equals("1 requests")));
        }
    }

    /// A failure has to say which client, which line, what was wanted, and what
    /// the page offered instead — the last of those being the one that saves
    /// the afternoon.
    private static void failures() throws Exception {
        try (var server = Http.start(notes(), 0)) {
            var service = URI.create("http://localhost:" + server.port());

            var missing = Hyperspec.run("visit /\nexpect link \"Sign out\"", service);
            Check.that("a missing affordance fails", !missing.ok());
            var failure = missing.failures().getFirst();
            Check.equal("on the line it was asked on", 2, failure.line());
            Check.that("saying what was wanted", failure.what().contains("Sign out"));
            Check.that("and what the page offers instead: " + failure.found(), failure.found().contains("Sign in"));

            var status = Hyperspec.run("visit /nowhere\nexpect status 200", service);
            Check.that("a wrong status says what came back: " + status.failures().getFirst().found(),
                    status.failures().getFirst().found().contains("404"));

            var stopped = Hyperspec.run("""
                    visit /
                    expect link "Sign out"
                    expect status 200
                    """, service);
            Check.equal("a journey stops at its first wrong turn", 0, stopped.passed());
            Check.equal("and reports it once", 1, stopped.failures().size());

            var separate = Hyperspec.run("""
                    client alice { visit / ; expect link "Sign out" }
                    client bob { visit / ; expect link "Sign in" }
                    """, service);
            Check.equal("one client's failure is not another's", 1, separate.failures().size());
            Check.equal("and the other one carries on", 1, separate.passed());
            Check.equal("the failure names the client it belongs to", "alice", separate.failures().getFirst().client());

            var unknown = Hyperspec.run("visit /\nvist /again", service);
            Check.that("an unknown command names the commands there are: " + unknown.failures().getFirst().what(),
                    unknown.failures().getFirst().what().contains("visit"));

            var unset = Hyperspec.run("visit /$nothing", service);
            Check.that("so does an unset variable", unset.failures().getFirst().what().contains("$nothing"));

            var noRedirect = Hyperspec.run("visit /\nfollow redirect", service);
            Check.that("and a redirect that is not there: " + noRedirect.failures().getFirst().found(),
                    noRedirect.failures().getFirst().found().contains("200"));
        }
    }

    // The application under test: sign in, list, compose, read.

    private static final Map<String, String[]> NOTES = new LinkedHashMap<>();
    private static final Map<String, String> SESSIONS = new LinkedHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger(1);

    private static Handler notes() {
        NOTES.clear();
        SESSIONS.clear();
        NEXT.set(1);
        var routes = Router.of()
                .get("home", "/")
                .get("sign-in", "/session/new")
                .post("session", "/session")
                .get("notes", "/notes")
                .get("compose", "/notes/new")
                .post("create", "/notes")
                .get("note", "/notes/{id}");
        return Routing.of(routes)
                .on("home", (request, response) -> render(response, a(href("/session/new"), text("Sign in"))))
                .on("sign-in", (request, response) -> render(response, form(id("sign-in"),
                        action("/session"), method("post"),
                        input(type("text"), name("who")),
                        button(type("submit"), text("Continue")))))
                .on("session", HyperspecTest::signIn)
                .on("notes", (request, response) -> render(response, div(
                        a(href("/notes/new"), text("New note")),
                        Html.fragment(NOTES.values().stream().map(note -> (Html) article(
                                flag("itemscope"), attribute("itemtype", "https://tuul.dev/Note"),
                                a(attribute("itemprop", "url"), href("/notes/" + note[0]), text(note[1])),
                                span(attribute("itemprop", "id"), text(note[0])))).toList()))))
                .on("compose", (request, response) -> render(response, form(id("new-note"),
                        action("/notes"), method("post"),
                        input(type("text"), name("title")),
                        button(type("submit"), text("Create")))))
                .on("create", HyperspecTest::create)
                .on("note", HyperspecTest::show);
    }

    private static void signIn(Request request, Response response) throws Exception {
        var token = "s" + NEXT.getAndIncrement();
        SESSIONS.put(token, request.form().first("who", ""));
        response.status(303).header("Set-Cookie", "session=" + token + "; Path=/").header("Location", "/notes").close();
    }

    private static void create(Request request, Response response) throws Exception {
        var id = String.valueOf(NEXT.getAndIncrement());
        NOTES.put(id, new String[] {id, request.form().first("title", ""), who(request)});
        response.status(303).header("Location", "/notes/" + id).close();
    }

    private static void show(Request request, Response response) throws Exception {
        var note = NOTES.get(Routing.variables(request).first("id", ""));
        if (note == null) {
            response.status(404).close();
            return;
        }
        render(response, article(flag("itemscope"), attribute("itemtype", "https://tuul.dev/Note"),
                h1(attribute("itemprop", "title"), text(note[1])),
                span(attribute("itemprop", "id"), text(note[0])),
                span(attribute("itemprop", "author"), text(note[2])),
                a(href("/notes"), rel("index"), text("All notes"))));
    }

    private static String who(Request request) {
        var cookie = request.header("Cookie").orElse("");
        var at = cookie.indexOf("session=");
        return at < 0 ? "nobody" : SESSIONS.getOrDefault(cookie.substring(at + 8).split(";")[0], "nobody");
    }

    private static void render(Response response, Html content) throws Exception {
        response.header("Content-Type", "text/html; charset=utf-8");
        document(html(body(content))).write(response.writer());
    }

    private static Page page(String html) {
        return new Page(URI.create("http://localhost/"), "GET", 200, web.Headers.NONE, html, Document.read(html));
    }

    private static String literal(Script.Word word) {
        var text = new StringBuilder();
        for (var part : word.parts()) {
            if (part instanceof Script.Part.Text(var value)) text.append(value);
        }
        return text.toString();
    }
}
