package browser;

import web.dispatch.Router;

/// Every URL the browser has of its own, named once.
///
/// Nothing below builds a path by writing one out: a link asks the router for
/// the route by name and gives it the variables. Renaming a route then breaks
/// the page that links to it, at compile time or at the first test, rather than
/// producing a 404 for somebody reading documentation.
///
/// The stream of updates and the URL that serves a file are not here. They
/// belong to [web.cable.Cable] and to [web.Features], which bring them along
/// with the handlers that answer them — see [web.Feature]. This application
/// used to declare both, and the asset one had to repeat a prefix that
/// [web.assets.Assets] was already sure about.
public final class Routes {

    public static final String HOME = "home";

    public static final String SEARCH = "search";

    public static final String SYMBOL = "symbol";

    /// What there is, as a page. The sidebar shows the same thing, and this is
    /// where its opener goes for a reader whose scripts never arrived — which
    /// is why it exists as a URL and not only as a panel.
    public static final String TREE = "tree";

    /// The path every client asks for whether or not a page mentions one. A
    /// `<link rel="icon">` stops a browser asking, and stops nothing else —
    /// so answering here costs one route and removes a class of 404 for good.
    public static final String FAVICON = "favicon";

    private Routes() {}

    public static Router of() {
        return Router.of()
                .get(HOME, "/")
                .get(SEARCH, "/search")
                .get(SYMBOL, "/symbols/{name}")
                .get(TREE, "/tree")
                .get(FAVICON, "/favicon.ico");
    }
}
