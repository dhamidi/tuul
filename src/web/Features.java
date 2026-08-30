package web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import web.assets.Assets;
import web.assets.Importmap;

/// An application's features, composed: one router, one asset pipeline, and
/// one import map.
///
/// This is what an application builds instead of repeating each feature's
/// wiring by hand. It takes the application's own router and the features it
/// uses, and answers the four things a page needs — where the URLs are, where
/// the files are, what the bare specifiers mean, and what answers each route.
///
/// ```
/// var wiring = Features.of(Router.of(), Ui.feature(), cable.feature(topics));
/// var handler = wiring.router().get(HOME, home()).otherwise(missing());
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
///
/// **That order is also document order.** [#head()] and [#body()] write each
/// feature's contributions in the order the features were named, and each
/// feature's own in the order it declared them. This is not an accident of how
/// they are stored: stylesheets cascade, so a design system named before an
/// application is a design system whose rules the application can override, and
/// the reverse silently is not.
///
/// It is middleware order too: the first feature named wraps outermost and sees
/// a request before the ones after it. And it is shutdown order, reversed —
/// see [#close()], which is the one use of this order that agrees with document
/// order rather than fighting it.
///
/// The two uses of that one order pull opposite ways, and an application has to
/// choose. Naming yourself **first** means your file wins when a feature ships
/// one of the same name. Naming yourself **last** means your stylesheet cascades
/// over theirs. You cannot have both from one list, and pretending otherwise
/// would mean an application that reads correctly and renders wrong. Replacing a
/// whole shipped file is rare and overriding some of its rules is not, so name
/// yourself last unless you have a reason.
public final class Features implements AutoCloseable {

    /// The logical or digested filename in [#ASSET].
    public static final StringParameter FILE = new StringParameter("file");

    /// The route that answers for a file. An application can replace its
    /// handler without restating its path.
    public static final RouteRef ASSET = RouteRef.of("web.assets.file", Assets.PREFIX + "/{file}", FILE);

    private final List<Feature> features;
    private final Router router;
    private final Assets assets;
    private final Importmap modules;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Features(List<Feature> features, Router router, Assets assets, Importmap modules) {
        this.features = List.copyOf(features);
        this.router = router;
        this.assets = assets;
        this.modules = modules;
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

        var router = own;
        for (var feature : features) {
            router = router.mount(feature.mount(), feature.router());
        }

        router = router.get(ASSET, serving(assets));
        return new Features(features, router, assets, modules);
    }

    /// Every route and handler from the application and its features.
    /// Feature middleware already wraps the returned router.
    public Router router() {
        return router.wrappedBy(middleware());
    }

    public Assets assets() {
        return assets;
    }

    public Importmap importmap() {
        return modules;
    }

    /// Everything the features put in the document head, and then the import
    /// map.
    ///
    /// The import map is last because nothing may import through it before it
    /// is read, and because the module that starts an application is written
    /// after this and has to come after the map. It is here at all rather than
    /// left to the application for the same reason the stylesheets are: it is
    /// built out of the features' own pins, and a page that forgot it would be
    /// a page that quietly stopped being interactive.
    ///
    /// See the note on order above: this is the order the features were named.
    public Markup head() {
        return out -> {
            for (var feature : features) {
                for (var contribution : feature.head()) contribution.write(assets, router, out);
            }
            modules.write(assets, out);
        };
    }

    /// Everything the features put at the top of the document body.
    ///
    /// An application writes its own layout and puts this at the start of it.
    /// What lands here is what a package needs present before anything scripts
    /// against it — the element the cable listens through — and not anything an
    /// application would recognise as its own page.
    public Markup body() {
        return out -> {
            for (var feature : features) {
                for (var contribution : feature.body()) contribution.write(assets, router, out);
            }
        };
    }

    public List<Feature> features() {
        return features;
    }

    /// Every feature's middleware, as one, outermost first.
    ///
    /// Answered separately as well as applied by [#router()], because a test
    /// that wants to prove what the stack does should not have to build a route
    /// table to see it.
    public Middleware middleware() {
        var stack = new ArrayList<Middleware>();
        for (var feature : features) stack.addAll(feature.middleware());
        return Middleware.of(stack);
    }

    /// Closes every resource the features declared, last named first.
    ///
    /// Reverse of composition, for the reason reverse is always the answer: a
    /// feature can be built from something an earlier one produced, so
    /// unwinding forwards would take a thing away from something still using
    /// it. The browser is exactly that — its own feature holds a watcher that
    /// broadcasts on the cable, so the watcher has to stop before the cable
    /// closes, and naming order gives that for free.
    ///
    /// **This is the third meaning of the one order, and the only one that does
    /// not fight the others.** Dependencies point backwards: a feature built
    /// from another feature's object is named after it, closes before it, and
    /// — being later — also cascades over it. So "name yourself after what you
    /// use" is the same advice as "name yourself last", and only asset
    /// precedence pulls the other way. See the note on order above.
    ///
    /// Everything is closed even when something throws. The first failure is
    /// what comes out and the rest are attached to it, because a shutdown that
    /// abandoned the remaining resources to report the first problem would
    /// leave a server unable to say it had stopped — which is the thing
    /// [web.cable.Cable#close()] exists to guarantee.
    ///
    /// Closing twice does nothing the second time. A server and the thing it
    /// serves are often closed by nested try-with-resources, and both arriving
    /// here is ordinary rather than a mistake.
    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        Exception failure = null;
        for (var feature : features.reversed()) {
            for (var resource : feature.closing().reversed()) {
                try {
                    resource.close();
                } catch (Exception unclosed) {
                    if (failure == null) failure = unclosed;
                    else failure.addSuppressed(unclosed);
                }
            }
        }
        if (failure != null) throw failure;
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
