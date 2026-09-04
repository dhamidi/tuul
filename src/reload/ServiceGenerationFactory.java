package reload;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Loads root providers for service types that the Tuul host module uses.
///
/// Use [JdkServiceFactory] for public JDK services. Host adapters such as
/// `web.reload` use this type for their own service contracts. A service that
/// the host module does not declare in `uses` fails during provider loading.
public final class ServiceGenerationFactory implements GenerationFactory {

    private final List<Class<?>> services;

    /// Creates a factory for the supplied service types in argument order.
    /// An empty list creates an empty generation.
    public ServiceGenerationFactory(List<? extends Class<?>> services) {
        Objects.requireNonNull(services, "services");
        this.services = List.copyOf(services);
        if (this.services.stream().distinct().count() != this.services.size()) {
            throw new IllegalArgumentException("service types must be unique");
        }
    }

    /// Creates a factory for the supplied service types in argument order.
    public ServiceGenerationFactory(Class<?>... services) {
        this(List.of(services));
    }

    /// Loads each service type and attaches its immutable provider list.
    /// A closeable provider is registered once by object identity.
    /// Provider loading failures propagate as `IllegalStateException`.
    @Override
    public Generation define(CandidateContext candidate) throws Exception {
        Objects.requireNonNull(candidate, "candidate");
        var generation = Generation.empty();
        var closeables = new IdentityHashMap<AutoCloseable, Boolean>();
        try {
            for (var service : services) {
                var providers = load(candidate, service);
                generation = attach(generation, service, providers);
                for (var provider : providers) {
                    if (provider instanceof AutoCloseable closeable
                            && closeables.putIfAbsent(closeable, Boolean.TRUE) == null) {
                        generation = generation.closing(closeable);
                    }
                }
            }
            return generation;
        } catch (Exception | Error failure) {
            try { generation.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static <T> List<T> load(CandidateContext candidate, Class<T> service) {
        return candidate.load(service);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Generation attach(Generation generation, Class<?> service, List<?> providers) {
        return generation.withServices((Class) service, (List) providers);
    }
}
