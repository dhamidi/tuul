package browser;

import browser.Symbols.Found;
import browser.Symbols.Symbol;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import symbols.Index;
import web.Handler;
import web.Page;
import web.Request;
import web.Response;
import web.Responses;
import web.Routing;
import web.Status;
import web.assets.Assets;
import web.assets.Importmap;
import web.cable.Cable;
import web.cable.Topics;
import web.controllers.Negotiate;
import web.dispatch.Router;
import web.ui.Html;
import web.serve.Http;
import web.ui.Turbo;

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

    /// The topic pages listen to. There is one, because there is one thing
    /// worth saying: what you are reading has been rebuilt.
    public static final String INDEX = "index";

    private final Index index;
    private final Router routes;
    private final Assets assets;
    private final Importmap modules;
    private final Cable cable;
    private final ExecutorService watching;

    private Browser(Index index, Router routes, Assets assets, Importmap modules, Cable cable,
                    ExecutorService watching) {
        this.index = index;
        this.routes = routes;
        this.assets = assets;
        this.modules = modules;
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
        return new Browser(index, Routes.of(), Assets.standard(List.of()),
                Importmap.standard()
                        .pin(Cable.MODULE, Cable.FILE)
                        .pin("@tuul/browser-search", "search.js"),
                cable, watching);
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
        return routes;
    }

    public Assets assets() {
        return assets;
    }

    public Cable cable() {
        return cable;
    }

    /// Everything the browser answers. A route that is not here is a 404 page
    /// rather than a bare status, since somebody who mistyped a symbol name is
    /// still reading documentation and should be given the search box back.
    public Handler handler() {
        return Routing.of(routes)
                .on(Routes.HOME, searching())
                .on(Routes.SEARCH, searching())
                .on(Routes.SYMBOL, showing())
                .on(Routes.UPDATES, cable.stream(Topics.fixed(INDEX)))
                .on(Routes.ASSET, this::asset)
                .otherwise(this::missing);
    }

    /// The front page and the search results are the same page: a search with
    /// nothing typed is the front page. Answering a full page either way is
    /// what lets Turbo take the frame out of it and lets somebody without
    /// JavaScript use the form anyway.
    private Page<Found> searching() {
        return Page.of(Found::nothing)
                .on(Routes.HOME, Symbols::searched)
                .on(Routes.SEARCH, Symbols::searched)
                .on(Symbols.MATCHED, Symbols::matched)
                .on("error", Symbols::unsearched)
                .effect(Symbols.SEARCH, Symbols.searching(index, MATCHES))
                .render((found, request, response) -> render(
                        Views.page(routes, assets, modules, title(found), Search.asking(routes, found.query()),
                                Views.results(routes, found)),
                        Status.OK, response));
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
        render(Views.page(routes, assets, modules, state.name(), Search.blank(routes), Views.symbol(routes, state)),
                status, response);
    }

    private void asset(Request request, Response response) throws IOException {
        Responses.send(assets.serve(request.path(), request.headers().first("If-None-Match").orElse(null)), response);
    }

    private void missing(Request request, Response response) throws IOException {
        render(Views.page(routes, assets, modules, "Not found", Search.blank(routes),
                Views.results(routes, Found.nothing().failed("There is nothing at " + request.path() + "."))),
                Status.NOT_FOUND, response);
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
