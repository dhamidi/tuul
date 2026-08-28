package browser;

import browser.Symbols.Found;
import browser.Symbols.Symbol;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import symbols.Index;
import symbols.References;
import web.Feature;
import web.Features;
import web.Handler;
import web.Page;
import web.Request;
import web.Response;
import web.Responses;
import web.Status;
import web.assets.Assets;
import web.assets.Bundled;
import web.cable.Cable;
import web.cable.Topics;
import web.controllers.Negotiate;
import web.dispatch.Router;
import web.forms.Submission;
import web.ui.Html;
import web.serve.Http;
import web.ui.Turbo;
import web.ui.Ui;

/// A browser for the symbol index tuul already builds.
///
/// It exists to be tuul's own application: everything it does, it does with
/// `web`. It reads the SQLite index rather than compiling anything, which is
/// what makes a page cost a query instead of a javac run.
///
/// It is also a whole application in the sense `application` means: every page
/// is a [Page], so a request is a message, an update decides what it leads to,
/// and the only thing that touches the index is an effect.
public final class Browser implements AutoCloseable {

    /// How many matches a search answers with. A page of results is a page,
    /// not everything the index knows.
    public static final int MATCHES = 25;

    /// This application's own assets, beside this application's own code.
    ///
    /// They are not tuul's: a stylesheet for browsing a symbol index has no
    /// business on the load path of every application anybody writes with the
    /// framework. [Bundled#of(Class, String)] finds them in a checkout and
    /// inside the jar, so where they are read from is not this file's problem.
    ///
    /// The cable's and the components' come from their own packages, named
    /// here for the same reason their modules are pinned here: this
    /// application uses them, and one that does not should not carry them.
    public static final String ASSETS = "assets";

    /// This application's own stylesheet. It cascades over the components' one,
    /// which is why the feature that ships it is named last.
    public static final String STYLESHEET = "browser.css";

    /// The topic pages listen to. There is one, because there is one thing
    /// worth saying: what you are reading has been rebuilt.
    public static final String INDEX = "index";

    private final Index index;
    private final Features wiring;
    private final Cable cable;
    private final ExecutorService watching;

    private Browser(Index index, Features wiring, Cable cable, ExecutorService watching) {
        this.index = index;
        this.wiring = wiring;
        this.cable = cable;
        this.watching = watching;
    }

    /// A browser over a project's index, watching it for changes.
    public static Browser of(List<Path> sources, List<Path> vendor) throws IOException {
        return of(Index.of(sources, vendor), Index.INDEX);
    }

    /// A browser over an index that is already open. `watched` is the file to
    /// notice changing, or nothing to notice nothing — which is what a test
    /// wants, and what somebody browsing a project that is not being rebuilt
    /// wants too.
    public static Browser of(Index index, Path watched) {
        var cable = Cable.of();
        var watching = watch(cable, watched);
        return new Browser(index, Features.of(Routes.of(), Ui.feature(),
                cable.feature(Topics.fixed(INDEX)), own()), cable, watching);
    }

    /// What this application ships to the browser.
    ///
    /// Its own files, its own stylesheet and icon, and the two controllers it
    /// wrote. Everything else on the page — the components' stylesheet, the
    /// cable's controller and element, Turbo and Stimulus — comes from the
    /// package that ships it, which is why this list is short and why nothing
    /// here repeats a file name that a framework package already knows.
    ///
    /// It is named last in [#of(Index, Path)] so that [#STYLESHEET] cascades
    /// over the components' one. See [Features] on what that order costs.
    private static Feature own() {
        return Feature.named("browser")
                .from(Bundled.of(Browser.class, ASSETS))
                .stylesheet(STYLESHEET)
                .head(Views.icon())
                .pin("@tuul/browser-search", "search.js")
                .pin(ResultItemKind.MODULE, ResultItemKind.FILE);
    }

    /// Starts a server and does not return until it is stopped. What a command
    /// calls, and the only method here that knows a port exists.
    public static void serve(List<Path> sources, List<Path> vendor, int port, Writer out) throws Exception {
        try (var browser = of(sources, vendor); var server = Http.start(browser.handler(), port)) {
            out.write("browsing " + describe(sources) + " at http://localhost:" + server.port() + "\n");
            out.write("  ^C to stop\n");
            out.flush();
            Thread.currentThread().join();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(List<Path> sources) {
        return sources.isEmpty() ? "the JDK" : String.join(", ", sources.stream().map(Path::toString).toList());
    }

    public Router routes() {
        return wiring.routes();
    }

    public Assets assets() {
        return wiring.assets();
    }

    public Cable cable() {
        return cable;
    }

    /// Everything the browser answers. A route that is not here is a 404 page
    /// rather than a bare status, since somebody who mistyped a symbol name is
    /// still reading documentation and should be given the search box back.
    public Handler handler() {
        return wiring.routing()
                .on(Routes.HOME, searching())
                .on(Routes.SEARCH, searching())
                .on(Routes.SYMBOL, showing())
                .on(Routes.TREE, this::browsing)
                .on(Routes.FAVICON, this::favicon)
                .otherwise(this::missing);
    }

    /// The front page and the search results are the same page: a search with
    /// nothing typed is the front page. A request that did not come from a
    /// frame is answered with the whole page, which is what lets somebody
    /// without JavaScript use the form, and what makes a search URL something
    /// that can be shared and reloaded.
    private Page<Found> searching() {
        return Page.of(Found::nothing)
                .on(Routes.HOME, Symbols::searched)
                .on(Routes.SEARCH, Symbols::searched)
                .on(Symbols.MATCHED, Symbols::matched)
                .on("error", Symbols::unsearched)
                .effect(Symbols.SEARCH, Symbols.searching(index, MATCHES))
                .render((found, request, response) -> render(
                        Views.RESULTS.equals(frame(request))
                                ? Views.results(routes(), found)
                                : shell(request, title(found), Search.asking(routes(), found.query()),
                                        Views.searching(routes(), found)),
                        Status.OK, response));
    }

    /// Which frame Turbo asked for, or nothing if it asked for a page.
    ///
    /// It says so in a header, and answering a whole document to a frame
    /// request means sending the head, the import map, the boot script, the bar
    /// and the tree for Turbo to throw all of it away. There are two frames now
    /// — the results and the content pane — so the answer is which, not
    /// whether: a request for one is not satisfied by the other.
    private static String frame(Request request) {
        return request.headers().first("Turbo-Frame").orElse("");
    }

    private Page<Symbol> showing() {
        return Page.of(Symbol::nothing)
                .on(Routes.SYMBOL, Symbols::asked)
                .on(Symbols.FOUND, Symbols::found)
                .on(Symbols.MISSING, Symbols::missing)
                .on("error", Symbols::failed)
                .effect(Symbols.LOOK, Symbols.looking(index))
                .render(this::symbol);
    }

    /// A symbol answers as a page or as the same JSON `tuul docs --json`
    /// prints, depending on what was asked for. An agent reading the index over
    /// HTTP is the reason this application exists at all, so it should not have
    /// to scrape a page to get what the command would hand it.
    private void symbol(Symbol state, Request request, Response response) throws IOException {
        var status = state.found() ? Status.OK : Status.NOT_FOUND;
        if (Negotiate.best(request, Negotiate.HTML, Negotiate.JSON).orElse(Negotiate.HTML).equals(Negotiate.JSON)) {
            response.status(status).header("Content-Type", "application/json");
            state.description().write(response.writer());
            return;
        }
        render(shell(request, state.name(), Search.blank(routes()),
                Views.symbol(routes(), referencing(state.name()), state)), status, response);
    }

    /// A doc comment's `[Type#member]` cross-references, pointed at pages.
    ///
    /// Three libraries meet on one line here and not one of them could do it
    /// alone. Markdown knows the reference names a definition the document
    /// never wrote, and asks rather than guessing — it has no business knowing
    /// what a method signature is. `symbols` knows that `[Type#member]` is
    /// javadoc rather than prose, and whether the index has one. And this knows
    /// the last part, which is the part neither of them can: where a symbol's
    /// page is.
    ///
    /// A name the index does not have stays as the text somebody typed. A dead
    /// link claims the page exists; brackets only claim the name does.
    private markdown.Links referencing(String scope) {
        return References.of(index, scope, (symbol, member) ->
                wiring.routes().path(Routes.SYMBOL, Map.of("name", symbol)) + (member.isEmpty() ? "" : "#" + member));
    }

    /// The tree as a page. The sidebar shows it on every page; this is the same
    /// thing at a URL, which is where the opener goes when there is no
    /// JavaScript to open a panel with — and it is also the honest answer to
    /// somebody who asks what this browser has in it.
    private void browsing(Request request, Response response) throws IOException {
        render(shell(request, "What there is", Search.blank(routes()), Views.browsing(routes(), index.roots())),
                Status.OK, response);
    }

    /// The icon, answered at the conventional path as well as its digested
    /// one. The digested URL is what a page points at and what a cache keeps;
    /// this is for everything that asks without reading the page first.
    private void favicon(Request request, Response response) throws IOException {
        Responses.send(wiring.assets().serve(wiring.assets().url(Views.ICON),
                request.headers().first("If-None-Match").orElse(null)), response);
    }

    private void missing(Request request, Response response) throws IOException {
        render(shell(request, "Not found", Search.blank(routes()),
                Views.searching(routes(), Found.nothing().failed("There is nothing at " + request.path() + "."))),
                Status.NOT_FOUND, response);
    }

    /// The page, or only the pane of it that was asked for.
    ///
    /// A tree link drives the content frame, so the answer to one has to
    /// *contain* that frame — a response without it blanks the pane and writes
    /// `Content missing` into it, which is what the frames spec exists to catch.
    /// Sending only the frame is the same contract kept for a tenth of the
    /// bytes.
    private Html shell(Request request, String heading, Submission search, Html content) {
        if (Views.CONTENT.equals(frame(request))) return Views.pane(content);
        return Views.page(wiring, heading, search,
                index.roots(), content);
    }

    private static void render(Html html, int status, Response response) throws IOException {
        Responses.html(html, status, response);
    }

    private static String title(Found found) {
        return found.asked() ? found.query() : "Symbols";
    }

    /// Watches the index for a rebuild and tells every open page to refresh.
    ///
    /// This is the one thing a browser of a *live* project can say that a
    /// static page cannot: you ran `tuul build` in another terminal, and what
    /// you are reading is out of date. Turbo morphs the page rather than
    /// reloading it, so saying so costs the reader nothing.
    private static ExecutorService watch(Cable cable, Path index) {
        if (index == null) return null;
        var watching = Executors.newVirtualThreadPerTaskExecutor();
        watching.submit(() -> {
            var seen = modified(index);
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(1000);
                var now = modified(index);
                if (now == seen) continue;
                seen = now;
                cable.broadcast(INDEX, Turbo.refresh());
            }
            return null;
        });
        return watching;
    }

    private static long modified(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException unreadable) {
            return 0;
        }
    }

    @Override
    public void close() {
        if (watching != null) watching.shutdownNow();
        cable.close();
        index.close();
    }
}
