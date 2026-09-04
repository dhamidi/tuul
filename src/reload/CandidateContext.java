package reload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.lang.module.Configuration;

/// Loads service providers declared by the root module of one candidate layer.
public final class CandidateContext {

    private final ModuleLayer layer;
    private final Module root;
    private final Map<Class<?>, List<?>> loaded = new HashMap<>();

    /// Creates a context for `layer` and its exact `root` module.
    public CandidateContext(ModuleLayer layer, Module root) {
        this.layer = Objects.requireNonNull(layer, "layer");
        this.root = Objects.requireNonNull(root, "root");
        if (!layer.modules().contains(root)) throw new IllegalArgumentException(
                "root module is not in candidate layer: " + root.getName());
    }

    /// Returns the fresh module layer that contains the candidate code.
    public ModuleLayer layer() { return layer; }

    /// Returns the module whose descriptor declares candidate services.
    public Module root() { return root; }

    /// Returns the resolved module graph represented by [#layer].
    public Configuration configuration() { return layer.configuration(); }

    /// Returns provider names declared by `service` in the root descriptor.
    /// The list is empty when the root declares no providers for the service.
    public List<String> declarations(Class<?> service) {
        Objects.requireNonNull(service, "service");
        return root.getDescriptor().provides().stream()
                .filter(provides -> provides.service().equals(service.getName()))
                .flatMap(provides -> provides.providers().stream())
                .sorted().toList();
    }

    /// Returns root provider identities for `service` in deterministic order.
    /// The list is empty when the root declares no providers for the service.
    public List<Provider> providers(Class<?> service) {
        Objects.requireNonNull(service, "service");
        return declarations(service).stream().map(name -> new Provider(root.getName(), name)).toList();
    }

    /// Loads the root module's providers for `service` in provider-name order.
    /// Providers declared by parent or dependency modules are excluded.
    /// The returned list is immutable and cached for this context.
    /// A missing or unloadable root provider throws `IllegalStateException`.
    /// The host module must declare `uses` for the service.
    /// Each provider class must implement the service and have a public
    /// no-argument constructor.
    public synchronized <T> List<T> load(Class<T> service) {
        Objects.requireNonNull(service, "service");
        @SuppressWarnings("unchecked")
        var prior = (List<T>) loaded.get(service);
        if (prior != null) return prior;
        var declared = declarations(service);
        var found = new HashMap<String, ServiceLoader.Provider<T>>();
        try {
            ServiceLoader.load(layer, service).stream()
                    .filter(provider -> provider.type().getModule().equals(root))
                    .forEach(provider -> found.put(provider.type().getName(), provider));
        } catch (ServiceConfigurationError failure) {
            throw failure(service, failure);
        }
        var missing = declared.stream().filter(name -> !found.containsKey(name)).toList();
        if (!missing.isEmpty()) throw new IllegalStateException("cannot load " + service.getName()
                + " providers from root module " + root.getName() + ": " + missing);
        var providers = new ArrayList<T>();
        for (var name : declared) {
            try {
                providers.add(found.get(name).get());
            } catch (ServiceConfigurationError failure) {
                var translated = failure(service, failure);
                close(providers, translated);
                throw translated;
            } catch (RuntimeException | Error failure) {
                close(providers, failure);
                throw failure;
            }
        }
        var answer = List.copyOf(providers);
        loaded.put(service, answer);
        return answer;
    }

    private IllegalStateException failure(Class<?> service, ServiceConfigurationError failure) {
        return new IllegalStateException("cannot load " + service.getName() + " from root module "
                + root.getName() + ": " + failure.getMessage(), failure);
    }

    /// Identifies one provider declared by a candidate root module.
    public record Provider(String module, String type) {
        public Provider {
            module = require(module, "module");
            type = require(type, "provider type");
        }

        /// Returns the module and provider type in diagnostic form.
        public String identity() { return module + "/" + type; }
    }

    private static void close(List<?> providers, Throwable failure) {
        var closed = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<AutoCloseable, Boolean>());
        for (var provider : providers.reversed()) {
            if (!(provider instanceof AutoCloseable closeable) || !closed.add(closeable)) continue;
            try {
                closeable.close();
            } catch (Exception close) {
                failure.addSuppressed(close);
            }
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
