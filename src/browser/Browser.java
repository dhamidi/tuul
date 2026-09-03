package browser;

import application.Message;
import browser.Symbols.Found;
import browser.Symbols.Symbol;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import markdown.Links;
import symbols.Document;
import symbols.Index;
import symbols.Catalog;
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
import web.Negotiate;
import web.Router;
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

    private final Catalog index;
    private final Features wiring;
    private final Cable cable;

    private Browser(Catalog index, Features wiring, Cable cable) {
        this.index = index;
        this.wiring = wiring;
        this.cable = cable;
    }

    /// Opens the last committed project index and returns without waiting for a
    /// rebuild. Starts one background refresh when the sources are stale.
    ///
    /// The returned browser shows an indexing state if no complete generation
    /// exists. Close the browser to stop its refresh and file watcher.
    public static Browser of(List<Path> sources, List<Path> vendor) throws IOException {
        var cable = Cable.of();
        var work = background(cable, Index.INDEX, sources, vendor);
        return new Browser(Index.catalog(Index.INDEX), Features.of(Routes.of(), Ui.feature(),
                cable.feature(Topics.fixed(INDEX)), own(work)), cable);
    }

    /// A browser over an index that is already open. `watched` is the file to
    /// notice changing, or nothing to notice nothing — which is what a test
    /// wants, and what somebody browsing a project that is not being rebuilt
    /// wants too.
    public static Browser of(Catalog index, Path watched) {
        var cable = Cable.of();
        return new Browser(index, Features.of(Routes.of(), Ui.feature(),
                cable.feature(Topics.fixed(INDEX)), own(watch(cable, watched))), cable);
    }

    /// The browser feature and its asset path.
    ///
    /// The feature adds this application's stylesheet and assets, the icon
    /// contribution, and the search, page-navigation, and result-kind modules.
    /// Everything else on the page comes from the package that ships it.
    /// This feature does not repeat a file name that a framework package owns.
    ///
    /// It is named last in [#of(Index, Path)] so that [#STYLESHEET] cascades
    /// over the components' one. See [Features] on what that order costs.
    ///
    /// It also carries the watcher, which is the application's own resource
    /// rather than any package's: it watches a symbol index, and nothing in
    /// `web` has ever heard of one. Being named last it is closed first, and it
    /// has to be — it broadcasts on the cable, so it must stop before the cable
    /// does.
    ///
    /// The watcher is handed over as what *stops* it, not as itself. An
    /// [ExecutorService] is an [AutoCloseable] whose `close` waits for the
    /// running tasks to end, and this task loops until it is interrupted, so
    /// closing it that way would wait for something that is never going to
    /// happen.
    private static Feature own(ExecutorService watching) {
        var feature = Feature.named("browser")
                .from(Bundled.of(Browser.class, ASSETS))
                .stylesheet(STYLESHEET)
                .head(Views.icon())
                .pin("@tuul/browser-search", "search.js")
                .pin("@tuul/browser-page-navigation", "page-navigation.js")
                .pin(ResultItemKind.MODULE, ResultItemKind.FILE);
        return watching == null ? feature : feature.closing(watching::shutdownNow);
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
        return wiring.router();
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
        return wiring.router()
                .on(Routes.HOME, searching())
                .on(Routes.SEARCH, searching())
                .on(Routes.SYMBOL, showing())
                .on(Routes.DOCUMENT_KIND, documents())
                .on(Routes.DOCUMENT, documents())
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
                .on(Routes.HOME.name(), Symbols::searched)
                .on(Routes.SEARCH.name(), Symbols::searched)
                .on(Symbols.MATCHED, Symbols::matched)
                .on(Message.HANDLE_ERROR, Symbols::unsearched)
                .effect(Symbols.SEARCH, Symbols.searching(index, symbols.Queries.MATCHES))
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
                .on(Routes.SYMBOL.name(), Symbols::asked)
                .on(Symbols.FOUND, Symbols::found)
                .on(Symbols.MISSING, Symbols::missing)
                .on(Message.HANDLE_ERROR, Symbols::failed)
                .effect(Symbols.LOOK, Symbols.looking(index))
                .render(this::symbol);
    }

    private Page<Documents.State> documents() {
        return Page.of(Documents.State::nothing)
                .on(Routes.DOCUMENT_KIND.name(), Documents::asked)
                .on(Routes.DOCUMENT.name(), Documents::asked)
                .on(Documents.FOUND, Documents::found)
                .on(Documents.MISSING, Documents::missing)
                .on(Message.HANDLE_ERROR, Documents::failed)
                .effect(Documents.LOOK, Documents.looking(index))
                .render(this::document);
    }

    private void document(Documents.State state, Request request, Response response) throws IOException {
        var status = state.found() ? Status.OK : Status.NOT_FOUND;
        if (state.found()
                && Negotiate.best(request, Negotiate.HTML, Negotiate.JSON).orElse(Negotiate.HTML).equals(Negotiate.JSON)) {
            response.status(status).header("Content-Type", "application/json");
            var description = state.description();
            (description.get("doc") instanceof json.Json.Str ? description.without("documents") : description)
                    .without("links")
                    .write(response.writer());
            return;
        }
        var content = state.found()
                ? Views.packageDocument(routes(), documentLinks(state.description()), state.description())
                : Views.searching(routes(), Found.nothing().failed(state.problem()));
        render(shell(request, state.found() ? state.description().string("title", "Document") : "Not found",
                Search.blank(routes()), content), status, response);
    }

    private Links documentLinks(json.Json.Object description) {
        var packageName = description.string("package", "");
        var symbols = referencing(packageName);
        return new Links() {
            @Override
            public String destination(String label) {
                return symbols.destination(label);
            }

            @Override
            public String defined(String destination) {
                var hash = destination.indexOf('#');
                var path = hash < 0 ? destination : destination.substring(0, hash);
                var fragment = hash < 0 ? "" : destination.substring(hash);
                if (path.contains(":") || path.contains("/") || Document.name(Path.of(path)).isEmpty()) {
                    return destination;
                }
                return description.list("links").stream()
                        .filter(json.Json.Object.class::isInstance)
                        .map(json.Json.Object.class::cast)
                        .filter(document -> document.string("file", "").equals(path))
                        .findFirst()
                        .map(document -> documentPath(packageName, document) + fragment)
                        .orElse(destination);
            }
        };
    }

    private String documentPath(String packageName, json.Json.Object document) {
        var slug = document.string("slug", "");
        if (slug.isEmpty()) return routes().path(Routes.DOCUMENT_KIND
                .with(Routes.NAME, packageName).with(Routes.KIND, document.string("kind", "")));
        return routes().path(Routes.DOCUMENT.with(Routes.NAME, packageName)
                .with(Routes.KIND, document.string("kind", "")).with(Routes.SLUG, slug));
    }

    private String documentPath(Document document) {
        if (document.slug().isEmpty()) return routes().path(Routes.DOCUMENT_KIND
                .with(Routes.NAME, document.packageName()).with(Routes.KIND, document.kind()));
        return routes().path(Routes.DOCUMENT.with(Routes.NAME, document.packageName())
                .with(Routes.KIND, document.kind()).with(Routes.SLUG, document.slug()));
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
                wiring.router().path(Routes.SYMBOL.with(Routes.NAME, symbol))
                        + (member.isEmpty() ? "" : "#" + member));
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
        render(shell(request, "Page not found", Search.blank(routes()),
                Views.notFound(routes(), request.path())),
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
        if (Views.CONTENT.equals(frame(request))) return Views.pane(routes(), content);
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
        watch(cable, index, watching);
        return watching;
    }

    private static void watch(Cable cable, Path index, ExecutorService watching) {
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
    }

    /// Starts the read-only browser immediately, then brings its database current
    /// on a background virtual thread. No request owns or starts this work.
    private static ExecutorService background(Cable cable, Path index, List<Path> sources, List<Path> vendor) {
        var work = Executors.newVirtualThreadPerTaskExecutor();
        watch(cable, index, work);
        work.submit(() -> {
            try (var coordinator = Index.of(sources, vendor, index)) {
                var changed = coordinator.ensureCurrent();
                if (coordinator.ensureBrowseCurrent()) changed = true;
                if (changed) cable.broadcast(INDEX, Turbo.refresh());
            }
            return null;
        });
        return work;
    }

    private static long modified(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException unreadable) {
            return 0;
        }
    }

    /// Stops the watcher, ends every open stream, and then closes the index.
    ///
    /// The first two are not named here any more. They are what the features
    /// declared, unwound in the order [Features#close()] documents, and this
    /// file no longer knows that a cable is one of the things a shutdown waits
    /// for.
    ///
    /// The index stays, because it is not a web resource and because whoever
    /// handed one to [#of(Index, Path)] may still be using it — a test opens an
    /// index, passes it here, and closes it itself.
    @Override
    public void close() throws Exception {
        wiring.close();
        index.close();
    }
}
