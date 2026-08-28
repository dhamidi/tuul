package web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import web.dispatch.Router;

/// Everything one package contributes to an application, said once.
///
/// A package that offers a thing to do — live updates, file uploads, a search
/// box — has several separate statements to make, and every one of them can be
/// forgotten on its own. Before this existed, an application that used the
/// cable had to declare a route template, bind a handler to that route's name,
/// pin the cable's module, put the cable's directory on the asset load path,
/// and render the cable's element in its layout. Drop the route and the page
/// gets a 404; drop the handler and it gets a 404 from somewhere else; drop the
/// pin and the browser cannot import the controller; drop the load path and the
/// pin cannot resolve; drop the element and nothing at all appears to be wrong.
/// Five ways to be wrong, five different symptoms, and none of them says what
/// is missing.
///
/// ```
/// Feature.named("web.cable")
///         .from(Bundled.of(Cable.class, "assets"))
///         .pin(Cable.MODULE, Cable.FILE)
///         .body((assets, routes, out) -> source(routes.path(UPDATES)).write(out))
///         .get(Cable.UPDATES, "/updates", cable.stream(topics));
/// ```
///
/// **A feature is a value, not a side effect.** Rails engines do this work by
/// mutating a global application while the process boots, which Ruby permits
/// and this design does not: an application here is built out of values, so a
/// feature is one too, and the compiler sees the wiring. There is no registry
/// and nothing is discovered from the class path. An application names the
/// features it uses, which is the one step between wanting a thing and having
/// it, and which is also the only way to read a program and know what it
/// serves.
///
/// A feature need not have every part. [web.ui.Ui#feature()] ships a stylesheet
/// and a controller and declares no routes at all.
///
/// **Routes and files are the same statement here, not two.** A feature holds
/// one [Router], and the handler for each route beside it, because a route
/// without its handler is the thing this exists to prevent. What it ships to
/// the browser is a list of directories and a set of pins — which is not a
/// second route table, and does not repeat one: a file is reached by its
/// digest under [web.assets.Assets#prefix()], and no feature has to know or
/// say that.
///
/// **What it puts in the page it also says here.** A stylesheet on the load
/// path that no document links is a stylesheet nobody reads, so
/// [#stylesheet(String)] says both at once. Anything else the document needs is
/// a [Contribution], written in the order it was declared. See [Features#head()]
/// for what that order means across features.
public record Feature(String name, String mount, List<Path> assets, Map<String, String> pins,
                      Router routes, Map<String, Handler> handlers,
                      List<Contribution> head, List<Contribution> body) {

    public Feature {
        if (name.isBlank()) throw new IllegalArgumentException("a feature needs a name");
        assets = List.copyOf(assets);
        pins = new LinkedHashMap<>(pins);
        handlers = new LinkedHashMap<>(handlers);
        head = List.copyOf(head);
        body = List.copyOf(body);
    }

    /// A feature that contributes nothing yet. The name is for whoever reads an
    /// error: a pin that cannot resolve says which feature asked for it.
    public static Feature named(String name) {
        return new Feature(name, "", List.of(), Map.of(), Router.of(), Map.of(), List.of(), List.of());
    }

    /// A directory of files this ships. Usually one —
    /// [web.assets.Bundled#of(Class, String)], so the files travel with the
    /// code in a checkout and inside a jar alike.
    public Feature from(Path directory) {
        var next = new ArrayList<>(assets);
        next.add(directory);
        return new Feature(name, mount, next, pins, routes, handlers, head, body);
    }

    /// A bare specifier, and the logical name of the file behind it.
    ///
    /// Both halves are the feature's, because a mismatch between them is
    /// silent: a pin with no file cannot resolve, and a file with no pin is
    /// served to nobody.
    public Feature pin(String module, String logical) {
        var next = new LinkedHashMap<>(pins);
        next.put(module, logical);
        return new Feature(name, mount, assets, next, routes, handlers, head, body);
    }

    /// Where this feature's paths hang. Empty means at the root, which is what
    /// most features want; an application that wants the cable at `/live`
    /// rather than `/updates` says so here and nothing else changes, because
    /// the route keeps its name and every link is built from that name.
    public Feature at(String mount) {
        return new Feature(name, mount, assets, pins, routes, handlers, head, body);
    }

    /// A stylesheet this ships, and the `link` that reaches it.
    ///
    /// Two statements that were separate and had to agree: the file went on the
    /// load path here and the application wrote `assets.url("ui.css")` into its
    /// own layout, naming another package's file as a string. Delete that line
    /// and every page still renders correct markup with none of the design on
    /// it, which no status code and no structural test can see.
    ///
    /// The URL is resolved when the page is written, because it carries the
    /// digest of the file's content.
    public Feature stylesheet(String logical) {
        return head((assets, routes, out) -> {
            out.write("<link rel=\"stylesheet\" href=\"");
            out.write(attribute(assets.url(logical)));
            out.write("\">\n");
        });
    }

    /// Something this puts in the document head, after any stylesheet declared
    /// before it.
    public Feature head(Contribution contribution) {
        var next = new ArrayList<>(head);
        next.add(contribution);
        return new Feature(name, mount, assets, pins, routes, handlers, next, body);
    }

    /// Something this puts at the top of the document body.
    ///
    /// It goes first so that an element which has to exist before anything
    /// scripts against it does. What is here is the package's own — the element
    /// the cable listens through — and not the application's layout, which the
    /// application still writes.
    public Feature body(Contribution contribution) {
        var next = new ArrayList<>(body);
        next.add(contribution);
        return new Feature(name, mount, assets, pins, routes, handlers, head, next);
    }

    /// A route and the handler that answers it, together.
    ///
    /// Together is the point. A route with no handler is a 404 that looks like
    /// a missing page, and a handler bound to a name no route has is refused by
    /// [Routing#on] much later, if at all.
    public Feature route(String route, String method, String template, Handler handler) {
        var next = new LinkedHashMap<>(handlers);
        next.put(route, handler);
        return new Feature(name, mount, assets, pins, routes.route(route, method, template), next, head, body);
    }

    public Feature get(String route, String template, Handler handler) {
        return route(route, "GET", template, handler);
    }

    public Feature post(String route, String template, Handler handler) {
        return route(route, "POST", template, handler);
    }

    public Feature put(String route, String template, Handler handler) {
        return route(route, "PUT", template, handler);
    }

    public Feature delete(String route, String template, Handler handler) {
        return route(route, "DELETE", template, handler);
    }

    /// A value safe to put between double quotes in an attribute.
    ///
    /// `web.ui.Escape` does this and more, and `web` cannot call it: `web.ui` is
    /// built on the responses this package defines, so the dependency only goes
    /// one way. An asset name comes from a file on somebody's disk, and a file
    /// name is not something this package gets to make assumptions about — the
    /// same reason [web.assets.Importmap#write] escapes the map it inlines.
    private static String attribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
