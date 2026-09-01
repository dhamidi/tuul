package settings;

import actors.ActorEffect;
import actors.Address;
import actors.Behavior;
import actors.Definition;
import actors.MessageType;
import application.Effect;
import application.Message;
import application.Step;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import json.Json;

/// Defines the durable actor that owns one application's settings document.
///
/// The actor folds `set`, `unset`, `reinitialize`, and `initialize` messages
/// into immutable state. It uses the [Configuration] supplied by its
/// contributions to validate each changed namespace. It records accepted
/// commands and initializer results in the actor log.
///
/// Call [#of] with the installed contributions. Register the definition with
/// an actor system. This package intentionally gives that system one settings
/// actor, at `settings/application`; [#of] creates the definition for that
/// actor, not another actor instance. Register a handler for [#RESOLVE] before
/// calling [#address] through [actors.ActorSystem#summon]. Read the resulting
/// document with `ActorSystem.inspect`.
///
/// The static [#address] method is therefore a singleton address within one
/// actor system. Use separate actor systems for separate self-contained
/// applications. The current package does not provide multiple independent
/// settings documents in one actor system because [actors.ActorSystem] keeps
/// one definition per actor type.
///
/// ```
/// var settings = Settings.of(images);
/// try (var system = actors.ActorSystem.named("shop")
///         .define(settings)
///         .effect(Settings.RESOLVE, Initializers.environment(System::getenv))) {
///     system.summon(Settings.address());
///     system.tell(Settings.address(), settings.set("/images/maximumBytes", Json.of(20_000_000)));
///     var document = system.inspect(Settings.address());
/// }
/// ```
public final class Settings implements Definition<Settings.State> {

    /// The actor type registered by this definition.
    public static final String TYPE = "settings";

    /// The id of the one settings actor in an application and actor system.
    public static final String APPLICATION = "application";

    /// The effect type emitted for non-constant initializer sources.
    public static final String RESOLVE = "settings.resolve";

    private static final String SET = "set";
    private static final String UNSET = "unset";
    private static final String REINITIALIZE = "reinitialize";
    private static final String INITIALIZED = "initialize";
    private static final String CHANGED = "settings.changed";
    private static final String INITIALIZING = "settings.initializing";
    private static final String REJECTED = "settings.rejected";

    /// One durable reinitialization request and the value it must replace.
    ///
    /// `present` distinguishes an absent prior value from a prior JSON null.
    /// When `present` is false, `previous` is null. The request remains in
    /// replay-derived state until a matching initializer result is accepted or
    /// a later mutation clears it.
    public record Request(String id, boolean present, Json previous) {
        public Request {
            Objects.requireNonNull(id, "id");
            if (present) Objects.requireNonNull(previous, "previous");
        }

        /// Returns the random identifier recorded for the request.
        @Override public String id() { return id; }

        /// Returns whether the requested path held a value when reinitialization started.
        @Override public boolean present() { return present; }

        /// Returns the prior value when [#present] is true. Returns null otherwise.
        @Override public Json previous() { return previous; }
    }

    /// The immutable state folded from the settings actor log.
    ///
    /// `values` contains only installed namespaces. `absent` contains full
    /// initializer pointers made explicitly absent by an unset. `requests`
    /// contains active reinitialization requests. `revision` counts accepted
    /// settings changes.
    public record State(Json.Object values, Set<String> absent, Map<String, Request> requests, long revision) {
        public State {
            values = Objects.requireNonNull(values, "values");
            absent = Set.copyOf(absent);
            requests = Map.copyOf(requests);
        }

        /// Returns the current installed namespace values.
        @Override public Json.Object values() { return values; }

        /// Returns the full initializer pointers with an explicit absence decision.
        @Override public Set<String> absent() { return absent; }

        /// Returns the active reinitialization requests by full initializer pointer.
        @Override public Map<String, Request> requests() { return requests; }

        /// Returns the count of accepted settings changes.
        @Override public long revision() { return revision; }

        static State empty() {
            return new State(Json.Object.of(), Set.of(), Map.of(), 0);
        }
    }

    private record Mutation(Json value, boolean changed, String reason) {}

    private final Configuration configuration;
    private final List<Configuration.Initializer> orderedInitializers;
    private final List<MessageType> messageTypes;

    private Settings(Configuration configuration) {
        this.configuration = configuration;
        this.orderedInitializers = configuration.orderedInitializers();
        this.messageTypes = List.of(MessageType.command(SET), MessageType.command(UNSET),
                MessageType.command(REINITIALIZE), MessageType.command(INITIALIZED));
    }

    /// Composes the installed contributions into one immutable actor definition.
    ///
    /// The method sorts namespaces by name for inspection. It compiles every
    /// schema with the built-in draft 2020-12 store. It rejects duplicate
    /// namespaces, invalid root schemas, duplicate initializer pointers, and
    /// invalid initializer or secret declarations before an actor starts.
    /// Passing no contributions creates an actor with no mutable namespaces.
    public static Settings of(Contribution... contributions) {
        return of(Configuration.of(contributions));
    }

    /// Builds the durable actor on one already composed configuration.
    ///
    /// The actor and runtime snapshots then share the same compiled schemas and
    /// declarations. This method does not register or summon the actor.
    public static Settings of(Configuration configuration) {
        return new Settings(Objects.requireNonNull(configuration, "configuration"));
    }

    /// Returns the immutable configuration shared by this actor and runtime
    /// snapshots.
    public Configuration configuration() {
        return configuration;
    }

    /// Validates a JSON document for use as runtime settings.
    ///
    /// The returned snapshot is independent of the actor. It does not read
    /// initializer sources or persist the document.
    public Values values(Json.Object document) {
        return configuration.values(document);
    }

    /// Returns the local address of the one settings actor in an actor system.
    ///
    /// The returned address is always `settings/application`. This method is
    /// static because the settings definition does not choose an actor id:
    /// the package reserves one settings document for each actor system. It
    /// does not summon the actor and it does not create a log.
    public static Address address() {
        return Address.of(TYPE, APPLICATION);
    }

    /// Builds a command that replaces one JSON Pointer value.
    ///
    /// The pointer must be absolute, non-empty, and belong to an installed
    /// namespace. Missing intermediate objects are created by the actor.
    /// An array index replaces an existing item. A final `-` appends an item.
    /// A write below a secret path is rejected. A secret path accepts only a
    /// [Secret] reference.
    /// The returned message is not sent by this method.
    public Message set(String pointer, Json value) {
        var path = configuration.owned(pointer);
        Objects.requireNonNull(value, "value");
        configuration.checkSecrets(path, value);
        return Message.of(SET).with("pointer", pointer).with("value", value);
    }

    /// Builds a command that removes one JSON Pointer value.
    ///
    /// The pointer must belong to an installed namespace. The message records
    /// the full initializer pointers that overlap it, so replay keeps the same
    /// explicit-absence decision. The returned message is not sent by this
    /// method.
    public Message unset(String pointer) {
        configuration.owned(pointer);
        var initializers = orderedInitializers.stream()
                .filter(initial -> Pointer.overlaps(initial.path(), Pointer.absolute(pointer)))
                .map(Configuration.Initializer::pointer).toList();
        return Message.of(UNSET).with("pointer", pointer)
                .with("initializers", Json.Array.strings(initializers));
    }

    /// Builds a command that requests declared initializers to run again.
    ///
    /// The pointer must equal or contain at least one declared initializer.
    /// The message contains a new random request id for each selected
    /// initializer. The returned message is not sent by this method.
    public Message reinitialize(String pointer) {
        var path = configuration.owned(pointer);
        var selected = orderedInitializers.stream()
                .filter(initial -> Pointer.overlaps(initial.path(), path)).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("no initializer at " + pointer);
        var requests = Json.Object.of();
        for (var initial : selected) requests = requests.with(initial.pointer(), UUID.randomUUID().toString());
        return Message.of(REINITIALIZE).with("pointer", pointer).with("requests", requests);
    }

    /// Returns `settings`, the actor type for this definition.
    @Override
    public String type() {
        return TYPE;
    }

    /// Creates one actor behavior with the four settings commands and resume initialization.
    ///
    /// The behavior starts with an empty document. Replay applies recorded
    /// messages before resume emits effects. The `self` address is accepted to
    /// satisfy the actor definition contract and does not change the state.
    @Override
    public Behavior<State> instantiate(Address self) {
        return Behavior.of(State.empty())
                .on(messageTypes.get(0), this::set)
                .on(messageTypes.get(1), this::unset)
                .on(messageTypes.get(2), this::reinitialize)
                .on(messageTypes.get(3), this::initialized)
                .resuming(this::resume);
    }

    /// Returns the operational JSON view of settings state.
    ///
    /// The result contains `revision` and the installed namespace values. It
    /// omits explicit absence markers, request ids, and initializer sources.
    /// The result contains secret references but never resolved secret bytes.
    @Override
    public Json inspect(State state) {
        return Json.Object.of().with("revision", state.revision()).with("values", state.values());
    }

    private Step<State> set(State state, Message message) {
        var pointer = message.string("pointer", "");
        try {
            var path = configuration.owned(pointer);
            var value = message.get("value");
            if (value == null) return reject(state, pointer, "missing-value", null);
            configuration.checkSecrets(path, value);
            var mutation = setAt(state.values(), path, value);
            if (!mutation.reason().isEmpty()) return reject(state, pointer, mutation.reason(), null);
            return commit(state, pointer, mutation.value(), path, null);
        } catch (IllegalArgumentException invalid) {
            return reject(state, pointer, "invalid-pointer", null);
        }
    }

    private Step<State> unset(State state, Message message) {
        var pointer = message.string("pointer", "");
        try {
            var path = configuration.owned(pointer);
            var mutation = removeAt(state.values(), path);
            if (!mutation.reason().isEmpty()) return reject(state, pointer, mutation.reason(), null);
            var absent = new HashSet<String>();
            message.list("initializers").forEach(value -> {
                if (value instanceof Json.Str(var initial)) absent.add(initial);
            });
            var requests = clearRequests(state.requests(), path);
            var nextAbsent = new HashSet<>(state.absent());
            nextAbsent.addAll(absent);
            var changed = mutation.changed() || !nextAbsent.equals(state.absent()) || !requests.equals(state.requests());
            var next = new State(sorted(mutation.value()), nextAbsent, requests,
                    changed ? state.revision() + 1 : state.revision());
            return changed ? reply(next, pointer, CHANGED,
                    Json.Object.of().with("pointer", pointer).with("revision", next.revision())) : Step.of(next);
        } catch (IllegalArgumentException invalid) {
            return reject(state, pointer, "invalid-pointer", null);
        }
    }

    private Step<State> reinitialize(State state, Message message) {
        var pointer = message.string("pointer", "");
        try {
            var path = configuration.owned(pointer);
            var raw = message.get("requests");
            if (!(raw instanceof Json.Object requests)) return reject(state, pointer, "invalid-requests", null);
            var nextRequests = new HashMap<>(state.requests());
            var nextAbsent = new HashSet<>(state.absent());
            var selected = 0;
            for (var entry : requests.fields().entrySet()) {
                if (!(entry.getValue() instanceof Json.Str(var id)) || id.isEmpty()) {
                    return reject(state, pointer, "invalid-requests", null);
                }
                var initialPath = Pointer.absolute(entry.getKey());
                if (!Pointer.overlaps(initialPath, path)) continue;
                var prior = Configuration.locate(state.values(), initialPath);
                nextRequests.put(entry.getKey(), new Request(id, prior.present(), prior.value()));
                nextAbsent.remove(entry.getKey());
                selected++;
            }
            if (selected == 0) return reject(state, pointer, "no-initializer", null);
            var next = new State(state.values(), nextAbsent, nextRequests, state.revision() + 1);
            return reply(next, pointer, INITIALIZING, Json.Object.of().with("pointer", pointer));
        } catch (IllegalArgumentException invalid) {
            return reject(state, pointer, "invalid-pointer", null);
        }
    }

    private Step<State> initialized(State state, Message message) {
        var pointer = message.string("pointer", "");
        try {
            var path = configuration.owned(pointer);
            var value = message.get("value");
            var condition = message.get("condition");
            if (value == null || !current(state, path, condition)) return Step.of(state);
            configuration.checkSecrets(path, value);
            var mutation = setAt(state.values(), path, value);
            if (!mutation.reason().isEmpty()) return Step.of(state);
            var committed = commitCandidate(state, mutation.value(), path);
            if (committed == null) return Step.of(state);
            var requests = new HashMap<>(state.requests());
            requests.remove(pointer);
            var absent = new HashSet<>(state.absent());
            absent.remove(pointer);
            return Step.of(new State(sorted(committed), absent, requests, state.revision() + 1));
        } catch (IllegalArgumentException invalid) {
            return Step.of(state);
        }
    }

    private Step<State> resume(State state, Message ignored) {
        var effects = new ArrayList<Effect>();
        for (var initial : orderedInitializers) {
            var current = Configuration.locate(state.values(), initial.path());
            var request = state.requests().get(initial.pointer());
            if (request == null && (current.present() || state.absent().contains(initial.pointer()))) continue;
            var condition = request == null ? Json.Object.of().with("kind", "missing")
                    : condition(request);
            var message = Message.of(INITIALIZED).with("pointer", initial.pointer())
                    .with("condition", condition).with("value", initial.initial().constant()
                            ? initial.initial().value() : Json.NULL);
            effects.add(initial.initial().constant() ? Effect.send(message)
                    : Effect.of(RESOLVE).with("pointer", initial.pointer())
                            .with("source", initial.initial().scheme() + ":" + initial.initial().name())
                            .with("decoder", initial.initial().decoder().name())
                            .with("condition", condition));
        }
        return new Step<>(state, effects);
    }

    private Step<State> commit(State state, String pointer, Json values, List<String> path, Json ignored) {
        var committed = commitCandidate(state, values, path);
        if (committed == null) return reject(state, pointer, "invalid-value", validation(state, path, values));
        var absent = state.absent().stream().filter(marker -> !overlap(marker, path)).collect(java.util.stream.Collectors.toSet());
        var requests = clearRequests(state.requests(), path);
        var next = new State(sorted(committed), absent, requests, state.revision() + 1);
        return reply(next, pointer, CHANGED, Json.Object.of().with("pointer", pointer)
                .with("revision", next.revision()));
    }

    private Json commitCandidate(State state, Json values, List<String> path) {
        return configuration.validCandidate(values, path) ? values : null;
    }

    private Step<State> reject(State state, String pointer, String reason, Json errors) {
        var body = Json.Object.of().with("pointer", pointer).with("reason", reason);
        if (errors != null) body = body.with("errors", errors);
        return reply(state, pointer, REJECTED, body);
    }

    private Step<State> reply(State state, String pointer, String type, Json body) {
        var payload = body instanceof Json.Object object ? object : Json.Object.of();
        return Step.of(state, ActorEffect.reply(Message.of(type, payload)));
    }

    private Json validation(State state, List<String> path, Json values) {
        return configuration.validation((Json.Object) values, path);
    }

    private static boolean current(State state, List<String> path, Json condition) {
        if (!(condition instanceof Json.Object object)) return false;
        var current = Configuration.locate(state.values(), path);
        var kind = object.string("kind", "");
        if (kind.equals("missing")) {
            return !current.present() && !state.absent().contains(Pointer.render(path));
        }
        if (!kind.equals("requested")) return false;
        var request = state.requests().get(Pointer.render(path));
        if (request == null || !request.id().equals(object.string("request", ""))) return false;
        if (request.present() != current.present()) return false;
        if (request.present() != object.fields().containsKey("previous")) return false;
        if (request.present() && !request.previous().equals(object.get("previous"))) return false;
        return !request.present() || request.previous().equals(current.value());
    }

    private static Json.Object condition(Request request) {
        var result = Json.Object.of().with("kind", "requested").with("request", request.id());
        return request.present() ? result.with("previous", request.previous()) : result;
    }

    private static Json.Object sorted(Json value) {
        var object = (Json.Object) value;
        var result = Json.Object.of();
        for (var entry : object.fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            result = result.with(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map<String, Request> clearRequests(Map<String, Request> requests, List<String> path) {
        return requests.entrySet().stream().filter(entry -> !overlap(entry.getKey(), path))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static boolean overlap(String pointer, List<String> path) {
        try {
            return Pointer.overlaps(Pointer.absolute(pointer), path);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Mutation setAt(Json value, List<String> path, Json replacement) {
        try {
            return new Mutation(pointer(path).set(value, replacement), true, "");
        } catch (json.JsonException invalid) {
            return new Mutation(value, false, "cannot-traverse");
        }
    }

    private static Mutation removeAt(Json value, List<String> path) {
        var pointer = pointer(path);
        if (!pointer.exists(value)) return new Mutation(value, false, "");
        try {
            return new Mutation(pointer.remove(value), true, "");
        } catch (json.JsonException invalid) {
            return new Mutation(value, false, "cannot-traverse");
        }
    }

    private static json.Pointer pointer(List<String> path) {
        var pointer = json.Pointer.root();
        for (var token : path) pointer = pointer.append(token);
        return pointer;
    }

}
