package reload;

import application.Application;
import application.Message;
import json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/// Dispatches messages to named applications that survive generation changes.
///
/// A dispatch locks one application instance until [Application#dispatch] and
/// all of its effects finish. Activation waits for that lock, then swaps the
/// complete set of named instances. A turn therefore uses one definition and
/// one generation, including the effect handlers installed by that definition.
public final class Applications {

    private final Object monitor = new Object();
    private Map<String, Slot> active = Map.of();
    private long generation;
    private boolean closed;

    Applications() {}

    /// Returns the names that the active generation currently exposes.
    public List<String> names() {
        synchronized (monitor) { return List.copyOf(active.keySet()); }
    }

    /// Returns the generation number assigned to the current application set.
    /// It starts at zero and increases after each successful application activation.
    public long generation() {
        synchronized (monitor) { return generation; }
    }

    /// Dispatches messages to a named application.
    /// An unknown name throws [IllegalArgumentException]. A null message array
    /// or null message throws [NullPointerException].
    public void dispatch(String name, Message... messages) {
        if (messages == null) throw new NullPointerException("messages");
        Slot slot;
        synchronized (monitor) {
            slot = lookup(name);
            slot.lock.lock();
        }
        try {
            for (var message : messages) if (message == null) throw new NullPointerException("message");
            slot.application.dispatch(messages);
        } finally {
            slot.lock.unlock();
        }
    }

    /// Returns a named application's versioned JSON state for inspection or transfer.
    /// The definition must provide [ApplicationDefinition#withTransfer]; otherwise
    /// this method throws [IllegalStateException]. The returned state is immutable.
    public State state(String name) throws Exception {
        Slot slot;
        synchronized (monitor) {
            slot = lookup(name);
            slot.lock.lock();
        }
        try {
            return new State(slot.definition.name(), slot.definition.version(),
                    slot.definition.snapshotUntyped(slot.application));
        } finally {
            slot.lock.unlock();
        }
    }

    private Slot lookup(String name) {
        if (closed) throw new IllegalStateException("reload is closed");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        var slot = active.get(name);
        if (slot == null) throw new IllegalArgumentException("unknown application: " + name);
        return slot;
    }

    Prepared prepare(List<Generation.ApplicationBinding> bindings) throws Exception {
        Map<String, Slot> old;
        List<Slot> locks;
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("reload is closed");
            old = active;
            locks = old.values().stream().sorted(java.util.Comparator.comparing(
                    slot -> slot.definition.name())).toList();
            locks.forEach(slot -> slot.lock.lock());
        }
        var prepared = new Prepared(new LinkedHashMap<>(), locks);
        try {
            for (var binding : bindings) {
                var definition = binding.definition();
                var name = definition.name();
                if (prepared.next.containsKey(name)) throw new IllegalArgumentException("duplicate application " + name);
                var prior = old.get(name);
                var application = create(definition, binding.policy(), prior);
                prepared.next.put(name, new Slot(definition, application));
            }
        } catch (Throwable failure) {
            prepared.abort();
            throw failure;
        }
        return prepared;
    }

    void commit(Prepared prepared) {
        try {
            synchronized (monitor) {
                active = Map.copyOf(prepared.next);
                generation++;
            }
        } finally {
            prepared.release();
        }
    }

    void close() {
        List<Slot> slots;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            slots = active.values().stream().sorted(java.util.Comparator.comparing(
                    slot -> slot.definition.name())).toList();
            slots.forEach(slot -> slot.lock.lock());
            active = Map.of();
        }
        slots.reversed().forEach(slot -> slot.lock.unlock());
    }

    private static Application<?> create(ApplicationDefinition<?> definition,
            StatePolicy policy, Slot prior) throws Exception {
        if (prior == null) return definition.create();
        return switch (policy) {
            case RESTART -> definition.create();
            case REFUSE -> throw new IllegalStateException(
                    "application " + definition.name() + " refuses replacement");
            case TRANSFER -> transfer(definition, prior);
            case REPLAY -> throw new IllegalStateException(
                    "application " + definition.name() + " does not support replay");
        };
    }

    @SuppressWarnings("unchecked")
    private static Application<?> transfer(ApplicationDefinition<?> definition, Slot prior) throws Exception {
        var oldDefinition = prior.definition;
        var oldApplication = prior.application;
        var state = ((ApplicationDefinition<Object>) oldDefinition).snapshot(
                (Application<Object>) oldApplication);
        var envelope = Json.Object.of("version", oldDefinition.version()).with("state", state);
        var transferred = envelope.get("state") instanceof Json.Object object ? object : Json.Object.of();
        return ((ApplicationDefinition<Object>) definition).transfer(
                envelope.string("version", ""), transferred);
    }

    static final class Prepared {
        private final Map<String, Slot> next;
        private final List<Slot> locks;
        private boolean released;

        private Prepared(Map<String, Slot> next, List<Slot> locks) {
            this.next = next;
            this.locks = locks;
        }

        private void release() {
            if (released) return;
            released = true;
            locks.reversed().forEach(slot -> slot.lock.unlock());
        }

        void abort() { release(); }
    }

    /// Immutable JSON state and schema version for one active application.
    public record State(String name, String version, Json.Object value) {}

    private static final class Slot {
        private final ApplicationDefinition<?> definition;
        private final Application<?> application;
        private final ReentrantLock lock = new ReentrantLock();

        private Slot(ApplicationDefinition<?> definition, Application<?> application) {
            this.definition = definition;
            this.application = application;
        }
    }
}
