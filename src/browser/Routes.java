package browser;

import web.RouteRef;
import web.Router;
import web.StringParameter;

/// Every URL the browser has of its own, named once.
///
/// Nothing below builds a path by writing one out. A link gives the router one
/// of these references with its typed values bound. Renaming a reference then
/// breaks the page at compile time instead of producing a user-facing 404.
///
/// The stream of updates and the URL that serves a file are not here. They
/// belong to [web.cable.Cable] and to [web.Features], which bring them along
/// with the handlers that answer them — see [web.Feature]. This application
/// used to declare both, and the asset one had to repeat a prefix that
/// [web.assets.Assets] was already sure about.
public final class Routes {

    public static final StringParameter NAME = new StringParameter("name");

    public static final StringParameter KIND = new StringParameter("kind");

    public static final StringParameter SLUG = new StringParameter("slug");

    public static final RouteRef HOME = RouteRef.of("home", "/");

    public static final RouteRef SEARCH = RouteRef.of("search", "/search");

    public static final RouteRef SYMBOL = RouteRef.of("symbol", "/symbols/{name}", NAME);

    public static final RouteRef DOCUMENT_KIND = RouteRef.of(
            "document-kind", "/symbols/{name}/{kind}", NAME, KIND);

    public static final RouteRef DOCUMENT = RouteRef.of(
            "document", "/symbols/{name}/{kind}/{slug}", NAME, KIND, SLUG);

    /// What there is, as a page. The sidebar shows the same thing, and this is
    /// where its opener goes for a reader whose scripts never arrived — which
    /// is why it exists as a URL and not only as a panel.
    public static final RouteRef TREE = RouteRef.of("tree", "/tree");

    /// The path every client asks for whether or not a page mentions one. A
    /// `<link rel="icon">` stops a browser asking, and stops nothing else —
    /// so answering here costs one route and removes a class of 404 for good.
    public static final RouteRef FAVICON = RouteRef.of("favicon", "/favicon.ico");

    private Routes() {}

    public static Router of() {
        return Router.of()
                .get(HOME)
                .get(SEARCH)
                .get(SYMBOL)
                .get(DOCUMENT_KIND)
                .get(DOCUMENT)
                .get(TREE)
                .get(FAVICON);
    }
}
