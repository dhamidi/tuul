package reload;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import actors.ActorSystem;
import actors.Definition;
import actors.Spawn;
import application.Effect;
import web.Handler;

/// The complete immutable set of definitions and resources for one revision.
public final class Generation implements AutoCloseable {

    private final Handler handler;
    private final List<AutoCloseable> resources;
    private final List<ActorDefinition> actorDefinitions;
    private final List<EffectHandler> effectHandlers;
    private final List<ApplicationBinding> applicationDefinitions;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Generation(Handler handler, List<AutoCloseable> resources,
            List<ActorDefinition> actorDefinitions, List<EffectHandler> effectHandlers,
            List<ApplicationBinding> applicationDefinitions) {
        this.handler = handler;
        this.resources = List.copyOf(resources);
        this.actorDefinitions = List.copyOf(actorDefinitions);
        this.effectHandlers = List.copyOf(effectHandlers);
        this.applicationDefinitions = List.copyOf(applicationDefinitions);
    }

    /// Starts a generation with one web root.
    public static Generation of(Handler handler) {
        return new Generation(java.util.Objects.requireNonNull(handler, "handler"),
                List.of(), List.of(), List.of(), List.of());
    }

    /// Starts a generation that does not serve HTTP.
    public static Generation empty() {
        return of((request, response) -> web.Responses.empty(web.Status.NOT_FOUND, response));
    }

    /// Returns the web root for this generation.
    public Handler handler() {
        return handler;
    }

    /// Returns a new generation with one more owned resource. A null resource
    /// leaves the resource list unchanged.
    public Generation closing(AutoCloseable resource) {
        var next = new ArrayList<>(resources);
        if (resource != null) next.add(resource);
        return new Generation(handler, next, actorDefinitions, effectHandlers, applicationDefinitions);
    }

    /// Returns the resources that this generation closes after it drains.
    public List<AutoCloseable> resources() {
        return resources;
    }

    /// Adds one actor type to this generation. The actor system is stable
    /// host infrastructure; the definition and spawn options belong to the
    /// generation and are replaced together at activation.
    public Generation actor(ActorSystem system, Definition<?> definition, Spawn spawn) {
        var next = new ArrayList<>(actorDefinitions);
        next.add(new ActorDefinition(java.util.Objects.requireNonNull(system, "system"),
                java.util.Objects.requireNonNull(definition, "definition"),
                java.util.Objects.requireNonNull(spawn, "spawn"),
                defaultPolicy(spawn)));
        return new Generation(handler, resources, next, effectHandlers, applicationDefinitions);
    }

    /// Adds a durable actor type. Durable actors replay their command log on
    /// the new definition during a generation handoff.
    public Generation actor(ActorSystem system, Definition<?> definition) {
        return actor(system, definition, Spawn.durable());
    }

    /// Adds one actor type with an explicit state handoff policy and default
    /// durable spawn options.
    public Generation actor(ActorSystem system, Definition<?> definition, StatePolicy policy) {
        return actor(system, definition, Spawn.durable(), policy);
    }

    /// Adds one actor type with an explicit state handoff policy.
    public Generation actor(ActorSystem system, Definition<?> definition, Spawn spawn,
            StatePolicy policy) {
        var next = new ArrayList<>(actorDefinitions);
        next.add(new ActorDefinition(java.util.Objects.requireNonNull(system, "system"),
                java.util.Objects.requireNonNull(definition, "definition"),
                java.util.Objects.requireNonNull(spawn, "spawn"),
                java.util.Objects.requireNonNull(policy, "policy")));
        return new Generation(handler, resources, next, effectHandlers, applicationDefinitions);
    }

    /// Adds one effect handler to the actor system used by this generation.
    /// A duplicate type for the same system rejects activation.
    public Generation effect(ActorSystem system, String type, Effect.Handler effectHandler) {
        var next = new ArrayList<>(effectHandlers);
        next.add(new EffectHandler(java.util.Objects.requireNonNull(system, "system"),
                java.util.Objects.requireNonNull(type, "type"),
                java.util.Objects.requireNonNull(effectHandler, "handler")));
        return new Generation(handler, resources, actorDefinitions, next, applicationDefinitions);
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
        return new Generation(handler, resources, actorDefinitions, effectHandlers, next);
    }

    /// The named applications carried by this immutable generation.
    public List<ApplicationBinding> applicationDefinitions() {
        return applicationDefinitions;
    }

    private static StatePolicy defaultPolicy(Spawn spawn) {
        return spawn.keepsLog() ? StatePolicy.REPLAY : StatePolicy.REFUSE;
    }

    /// One actor binding in a complete generation.
    public record ActorDefinition(ActorSystem system, Definition<?> definition,
            Spawn spawn, StatePolicy policy) {}

    /// One external effect binding in a complete generation.
    public record EffectHandler(ActorSystem system, String type, Effect.Handler handler) {}

    /// One named application and the policy used when its definition changes.
    public record ApplicationBinding(ApplicationDefinition<?> definition, StatePolicy policy) {}

    /// Closes resources in reverse registration order. All resources are tried.
    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        Exception failure = null;
        for (var resource : resources.reversed()) {
            try {
                resource.close();
            } catch (Exception thrown) {
                if (failure == null) failure = thrown;
                else failure.addSuppressed(thrown);
            }
        }
        if (failure != null) throw failure;
    }
}
