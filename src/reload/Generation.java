package reload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import actors.ActorSystem;
import actors.Spawn;
import application.Effect;

/// The complete immutable set of definitions and resources for one revision.
public final class Generation implements AutoCloseable {

    /// Defines one generation from a compiled candidate.
    @FunctionalInterface
    public interface Definition {
        /// Builds a generation. A thrown exception rejects the candidate.
        Generation define(CandidateContext candidate) throws Exception;
    }

    private final Map<Capability<?>, Object> capabilities;
    private final Map<Class<?>, List<?>> services;
    private final List<AutoCloseable> resources;
    private final List<ActorDefinition> actorDefinitions;
    private final List<EffectHandler> effectHandlers;
    private final List<ApplicationBinding> applicationDefinitions;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Generation(Map<Capability<?>, Object> capabilities, Map<Class<?>, List<?>> services,
            List<AutoCloseable> resources,
            List<ActorDefinition> actorDefinitions, List<EffectHandler> effectHandlers,
            List<ApplicationBinding> applicationDefinitions) {
        this.capabilities = Map.copyOf(capabilities);
        this.services = services.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        this.resources = List.copyOf(resources);
        this.actorDefinitions = List.copyOf(actorDefinitions);
        this.effectHandlers = List.copyOf(effectHandlers);
        this.applicationDefinitions = List.copyOf(applicationDefinitions);
    }

    /// Starts a generation with no capabilities, definitions, or resources.
    public static Generation empty() {
        return new Generation(Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of());
    }

    /// Combines definitions in argument order and closes partial generations
    /// when a later definition fails.
    public static Definition compose(List<Definition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        var copy = definitions.stream().map(Objects::requireNonNull).toList();
        return candidate -> {
            var generations = new ArrayList<Generation>();
            try {
                for (var definition : copy) generations.add(Objects.requireNonNull(
                        definition.define(candidate), "generation definition returned null"));
                return merge(generations);
            } catch (Exception | Error failure) {
                var close = closeAll(generations);
                if (close != null) failure.addSuppressed(close);
                throw failure;
            }
        };
    }

    /// Combines definitions in argument order.
    public static Definition compose(Definition first, Definition... rest) {
        Objects.requireNonNull(first, "first");
        var all = new ArrayList<Definition>();
        all.add(first);
        if (rest != null) for (var definition : rest) all.add(definition);
        return compose(all);
    }

    /// Loads root-module services and attaches them to a new generation.
    /// Closeable providers belong to the returned generation. The service list
    /// is checked for duplicates and provider failures close partial work.
    public static Generation services(CandidateContext candidate,
            List<? extends Class<?>> serviceTypes) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(serviceTypes, "service types");
        var types = List.copyOf(serviceTypes);
        if (types.stream().distinct().count() != types.size()) {
            throw new IllegalArgumentException("service types must be unique");
        }
        var generation = empty();
        var closeables = new java.util.IdentityHashMap<AutoCloseable, Boolean>();
        try {
            for (var service : types) {
                Objects.requireNonNull(service, "service");
                var providers = candidate.load(service);
                @SuppressWarnings({"rawtypes", "unchecked"})
                var next = generation.withServices((Class) service, (List) providers);
                generation = next;
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

    /// Loads root-module services supplied in argument order.
    public static Generation services(CandidateContext candidate, Class<?>... serviceTypes) {
        return services(candidate, List.of(serviceTypes));
    }

    /// Attaches a capability value to this generation.
    ///
    /// A later value for the same key replaces the earlier value. The returned
    /// generation keeps the source generation unchanged.
    /// A null key or value throws `NullPointerException`.
    public <T> Generation with(Capability<T> capability, T value) {
        var next = new HashMap<>(capabilities);
        next.put(Objects.requireNonNull(capability, "capability"),
                Objects.requireNonNull(value, "value"));
        return new Generation(next, services, resources, actorDefinitions, effectHandlers, applicationDefinitions);
    }

    /// Finds a capability value attached to this generation.
    /// The result is empty when the key has no value. A null key throws
    /// `NullPointerException`.
    public <T> Optional<T> capability(Capability<T> capability) {
        Objects.requireNonNull(capability, "capability");
        @SuppressWarnings("unchecked")
        var value = (T) capabilities.get(capability);
        return Optional.ofNullable(value);
    }

    /// Attaches an immutable provider list to this generation.
    /// A null service, list, or provider throws `NullPointerException`.
    public <T> Generation withServices(Class<T> service, List<? extends T> providers) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(providers, "providers");
        var next = new HashMap<>(services);
        next.put(service, List.copyOf(providers));
        return new Generation(capabilities, next, resources, actorDefinitions, effectHandlers,
                applicationDefinitions);
    }

    /// Finds the provider list attached for `service`.
    /// The returned list is empty when no list is attached.
    public <T> List<T> services(Class<T> service) {
        Objects.requireNonNull(service, "service");
        @SuppressWarnings("unchecked")
        var answer = (List<T>) services.getOrDefault(service, List.of());
        return answer;
    }

    /// Finds one provider attached for `service` when its list is non-empty.
    /// The result is empty when no provider is attached.
    public <T> Optional<T> service(Class<T> service) {
        return services(service).stream().findFirst();
    }

    /// Combines immutable generations and rejects duplicate capability or service keys.
    /// The returned generation owns all resources from its arguments.
    /// A merge failure closes every input generation and adds close failures to it.
    /// Do not use an input after this method returns.
    public static Generation merge(List<Generation> generations) {
        Objects.requireNonNull(generations, "generations");
        var capabilities = new HashMap<Capability<?>, Object>();
        var services = new HashMap<Class<?>, List<?>>();
        var resources = new ArrayList<AutoCloseable>();
        var owned = new java.util.IdentityHashMap<AutoCloseable, Boolean>();
        var actors = new ArrayList<ActorDefinition>();
        var effects = new ArrayList<EffectHandler>();
        var applications = new ArrayList<ApplicationBinding>();
        try {
            for (var generation : generations) {
                Objects.requireNonNull(generation, "generation");
                for (var entry : generation.capabilities.entrySet()) {
                    if (capabilities.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                        throw new IllegalStateException("duplicate generation capability");
                    }
                }
                for (var entry : generation.services.entrySet()) {
                    if (services.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                        throw new IllegalStateException("duplicate generation service " + entry.getKey().getName());
                    }
                }
                for (var resource : generation.resources) {
                    if (owned.putIfAbsent(resource, Boolean.TRUE) == null) resources.add(resource);
                }
                actors.addAll(generation.actorDefinitions);
                effects.addAll(generation.effectHandlers);
                applications.addAll(generation.applicationDefinitions);
            }
        } catch (RuntimeException failure) {
            var close = closeAll(generations);
            if (close != null) failure.addSuppressed(close);
            throw failure;
        }
        var merged = new Generation(capabilities, services, resources, actors, effects, applications);
        for (var generation : generations) generation.closed.set(true);
        return merged;
    }

    /// Combines generations in argument order.
    public static Generation merge(Generation first, Generation... rest) {
        Objects.requireNonNull(first, "first");
        var all = new ArrayList<Generation>();
        all.add(first);
        if (rest != null) for (var generation : rest) all.add(generation);
        return merge(all);
    }

    /// Returns a new generation with one more owned resource. A null resource
    /// leaves the resource list unchanged.
    public Generation closing(AutoCloseable resource) {
        var next = new ArrayList<>(resources);
        if (resource != null) next.add(resource);
        return new Generation(capabilities, services, next, actorDefinitions, effectHandlers, applicationDefinitions);
    }

    /// Returns the resources that this generation closes after it drains.
    public List<AutoCloseable> resources() {
        return resources;
    }

    /// Adds one actor type to this generation. The actor system is stable
    /// host infrastructure; the definition and spawn options belong to the
    /// generation and are replaced together at activation.
    public Generation actor(ActorSystem system, actors.Definition<?> definition, Spawn spawn) {
        var next = new ArrayList<>(actorDefinitions);
        next.add(new ActorDefinition(java.util.Objects.requireNonNull(system, "system"),
                java.util.Objects.requireNonNull(definition, "definition"),
                java.util.Objects.requireNonNull(spawn, "spawn"),
                defaultPolicy(spawn)));
        return new Generation(capabilities, services, resources, next, effectHandlers, applicationDefinitions);
    }

    /// Adds a durable actor type. Durable actors replay their command log on
    /// the new definition during a generation handoff.
    public Generation actor(ActorSystem system, actors.Definition<?> definition) {
        return actor(system, definition, Spawn.durable());
    }

    /// Adds one actor type with an explicit state handoff policy and default
    /// durable spawn options.
    public Generation actor(ActorSystem system, actors.Definition<?> definition, StatePolicy policy) {
        return actor(system, definition, Spawn.durable(), policy);
    }

    /// Adds one actor type with an explicit state handoff policy.
    public Generation actor(ActorSystem system, actors.Definition<?> definition, Spawn spawn,
            StatePolicy policy) {
        var next = new ArrayList<>(actorDefinitions);
        next.add(new ActorDefinition(java.util.Objects.requireNonNull(system, "system"),
                java.util.Objects.requireNonNull(definition, "definition"),
                java.util.Objects.requireNonNull(spawn, "spawn"),
                java.util.Objects.requireNonNull(policy, "policy")));
        return new Generation(capabilities, services, resources, next, effectHandlers, applicationDefinitions);
    }

    /// Adds one effect handler to the actor system used by this generation.
    /// A duplicate type for the same system rejects activation.
    public Generation effect(ActorSystem system, String type, Effect.Handler effectHandler) {
        var next = new ArrayList<>(effectHandlers);
        next.add(new EffectHandler(java.util.Objects.requireNonNull(system, "system"),
                java.util.Objects.requireNonNull(type, "type"),
                java.util.Objects.requireNonNull(effectHandler, "handler")));
        return new Generation(capabilities, services, resources, actorDefinitions, next, applicationDefinitions);
    }

    /// Returns the actor bindings in registration order.
    public List<ActorDefinition> actorDefinitions() {
        return actorDefinitions;
    }

    /// Returns the actor effect bindings in registration order.
    public List<EffectHandler> effectHandlers() {
        return effectHandlers;
    }

    /// Adds a named long-lived application that the stable reload host can dispatch.
    /// The default [StatePolicy#REFUSE] rejects replacement after first activation.
    public Generation application(ApplicationDefinition<?> definition) {
        return application(definition, StatePolicy.REFUSE);
    }

    /// Adds a named long-lived application and selects its state boundary policy.
    /// [StatePolicy#TRANSFER] requires JSON functions from
    /// [ApplicationDefinition#withTransfer(ApplicationDefinition.Snapshot, ApplicationDefinition.Transfer)].
    public Generation application(ApplicationDefinition<?> definition, StatePolicy policy) {
        var next = new ArrayList<>(applicationDefinitions);
        next.add(new ApplicationBinding(java.util.Objects.requireNonNull(definition, "definition"),
                java.util.Objects.requireNonNull(policy, "policy")));
        return new Generation(capabilities, services, resources, actorDefinitions, effectHandlers, next);
    }

    /// The named applications carried by this immutable generation.
    public List<ApplicationBinding> applicationDefinitions() {
        return applicationDefinitions;
    }

    private static StatePolicy defaultPolicy(Spawn spawn) {
        return spawn.keepsLog() ? StatePolicy.REPLAY : StatePolicy.REFUSE;
    }

    /// One actor binding in a complete generation.
    public record ActorDefinition(ActorSystem system, actors.Definition<?> definition,
            Spawn spawn, StatePolicy policy) {}

    /// One external effect binding in a complete generation.
    public record EffectHandler(ActorSystem system, String type, Effect.Handler handler) {}

    /// One named application and the policy used when its definition changes.
    public record ApplicationBinding(ApplicationDefinition<?> definition, StatePolicy policy) {}

    /// Closes resources in reverse registration order. All resources are tried.
    @Override
    public void close() throws Exception {
        var failure = closeAll(List.of(this));
        if (failure != null) throw failure;
    }

    static Exception closeAll(List<Generation> generations) {
        Exception failure = null;
        var closedResources = new java.util.IdentityHashMap<AutoCloseable, Boolean>();
        for (var generation : generations.reversed()) {
            if (generation == null || !generation.closed.compareAndSet(false, true)) continue;
            for (var resource : generation.resources.reversed()) {
                if (closedResources.putIfAbsent(resource, Boolean.TRUE) != null) continue;
                try {
                    resource.close();
                } catch (Exception thrown) {
                    if (failure == null) failure = thrown;
                    else failure.addSuppressed(thrown);
                }
            }
        }
        return failure;
    }
}
