package web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import web.assets.Assets;
import web.assets.Importmap;
import web.dispatch.Router;

/// An application's features, composed: one route table, one asset pipeline,
/// one import map.
///
/// This is what an application builds instead of repeating each feature's
/// wiring by hand. It takes the application's own routes and the features it
/// uses, and answers the four things a page needs — where the URLs are, where
/// the files are, what the bare specifiers mean, and what answers each route.
///
/// ```
/// var wiring = Features.of(Routes.of(), Ui.feature(), Cable.feature(cable, topics));
/// var handler = wiring.routing().on(Routes.HOME, home()).otherwise(missing());
/// ```
///
/// **It serves the assets itself.** Every application with an import map needs
/// a URL that answers for a digested file, and that URL has to agree with
/// [Assets#prefix()] — which is not something an application should be trusted
/// to restate. The browser used to write `/assets/{file}` into its own route
/// table beside an [Assets] that independently declared `/assets`, and nothing
/// checked that the two matched. The route is built from the prefix here, so
/// they cannot disagree.
///
/// The order features are named is the order their files are searched, and the
/// first answer wins — so an application that ships a file of the same name as
/// a feature replaces it by naming itself first.
public final class Features {

    /// The route that answers for a file. Named so that it can be replaced by
    /// an application that wants to serve assets some other way, and so that a
    /// 404 from it can be told apart from a 404 from anything else.
    public static final String ASSET = "web.assets.file";

    private final List<Feature> features;
    private final Router routes;
    private final Assets assets;
    private final Importmap modules;
    private final Map<String, Handler> handlers;

    private Features(List<Feature> features, Router routes, Assets assets, Importmap modules,
                     Map<String, Handler> handlers) {
        this.features = List.copyOf(features);
        this.routes = routes;
        this.assets = assets;
        this.modules = modules;
        this.handlers = Map.copyOf(handlers);
    }

    public static Features of(Router own, Feature... features) {
        return of(own, List.of(features));
    }

    /// Composes the application's own routes with the features it uses.
    ///
    /// Two features of the same name is refused rather than merged: a name is
    /// how a person reads which feature a file or a pin came from, and two
    /// answers to that question is worse than none.
    public static Features of(Router own, List<Feature> features) {
        var names = new LinkedHashSet<String>();
        for (var feature : features) {
            if (!names.add(feature.name())) {
                throw new IllegalArgumentException("two features are called " + feature.name());
            }
        }

        var paths = new ArrayList<Path>();
        for (var feature : features) paths.addAll(feature.assets());
        var assets = Assets.standard(paths);

        var modules = Importmap.standard();
        for (var feature : features) {
            for (var pin : feature.pins().entrySet()) modules = modules.pin(pin.getKey(), pin.getValue());
        }

        var handlers = new LinkedHashMap<String, Handler>();
        var routes = own;
        for (var feature : features) {
            routes = routes.mount(feature.mount(), feature.routes());
            handlers.putAll(feature.handlers());
        }

        routes = routes.get(ASSET, assets.prefix() + "/{file}");
        handlers.put(ASSET, serving(assets));
        return new Features(features, routes, assets, modules, handlers);
    }

    /// Every URL this application answers: its own, and every feature's.
    public Router routes() {
        return routes;
    }

    public Assets assets() {
        return assets;
    }

    public Importmap importmap() {
        return modules;
    }

    public List<Feature> features() {
        return features;
    }

    /// The features' handlers, already bound. An application adds its own with
    /// [Routing#on] and ends with [Routing#otherwise].
    public Routing routing() {
        var routing = Routing.of(routes);
        for (var handler : handlers.entrySet()) routing = routing.on(handler.getKey(), handler.getValue());
        return routing;
    }

    /// Answering for a file.
    ///
    /// The pipeline reads the name out of the path itself rather than out of
    /// the route's variable, because it already knows how to strip its own
    /// prefix and how to tell a digested name from a plain one.
    private static Handler serving(Assets assets) {
        return (request, response) -> Responses.send(
                assets.serve(request.path(), request.headers().first("If-None-Match").orElse(null)), response);
    }
}
