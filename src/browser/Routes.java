package browser;

import web.dispatch.Router;

/// Every URL the browser has, named once.
///
/// Nothing below builds a path by writing one out: a link asks the router for
/// the route by name and gives it the variables. Renaming a route then breaks
/// the page that links to it, at compile time or at the first test, rather than
/// producing a 404 for somebody reading documentation.
public final class Routes {

    public static final String HOME = "home";

    public static final String SEARCH = "search";

    public static final String SYMBOL = "symbol";

    public static final String UPDATES = "updates";

    public static final String ASSET = "asset";

    private Routes() {}

    public static Router of() {
        return Router.of()
                .get(HOME, "/")
                .get(SEARCH, "/search")
                .get(SYMBOL, "/symbols/{name}")
                .get(UPDATES, "/updates")
                .get(ASSET, "/assets/{file}");
    }
}
