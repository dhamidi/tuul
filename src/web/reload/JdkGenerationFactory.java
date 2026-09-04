package web.reload;

import com.sun.net.httpserver.HttpHandler;
import java.util.ArrayList;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import reload.Generation;
import reload.GenerationFactory;

/// Builds a generation from the one HTTP handler provided by a named layer.
///
/// The application module only declares a JDK service provider. It does not
/// import Tuul, `web`, or this class. The host owns the stable listener and
/// supplies this factory to the revision compiler.
public final class JdkGenerationFactory implements GenerationFactory {

    /// Creates a factory which defines one generation per named module layer.
    public JdkGenerationFactory() {}

    /// Loads exactly one provider from `layer` and attaches it to a fresh
    /// generation. An `AutoCloseable` provider is owned by that generation;
    /// reload closes it after all leases admitted to the layer drain.
    @Override
    public Generation define(ModuleLayer layer) throws Exception {
        Objects.requireNonNull(layer, "layer");
        var providers = new ArrayList<ServiceLoader.Provider<HttpHandler>>();
        try {
            ServiceLoader.load(layer, HttpHandler.class).stream().forEach(providers::add);
        } catch (ServiceConfigurationError failure) {
            throw new IllegalStateException("cannot load com.sun.net.httpserver.HttpHandler provider: "
                    + failure.getMessage(), failure);
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException("no com.sun.net.httpserver.HttpHandler provider was found in named layer");
        }
        if (providers.size() != 1) {
            throw new IllegalStateException("named layer provides " + providers.size()
                    + " com.sun.net.httpserver.HttpHandler providers; exactly one is required: "
                    + providers.stream().map(provider -> provider.type().getName()).toList());
        }
        HttpHandler handler;
        try {
            handler = providers.getFirst().get();
        } catch (ServiceConfigurationError failure) {
            throw new IllegalStateException("cannot create com.sun.net.httpserver.HttpHandler provider: "
                    + failure.getMessage(), failure);
        }
        var generation = JdkReloadHandler.attach(Generation.empty(), handler);
        if (handler instanceof AutoCloseable closeable) generation = generation.closing(closeable);
        return generation;
    }
}
