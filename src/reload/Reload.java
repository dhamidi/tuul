package reload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;
import actors.ActorSystem;
import actors.ReloadResult;

/// Coordinates immutable generations and leases work against the active one.
public final class Reload implements AutoCloseable {

    private final Object monitor = new Object();
    private final Object submissions = new Object();
    private final List<Validator> validators = new ArrayList<>();
    private final List<Subscriber> subscribers = new ArrayList<>();
    private final List<RevisionSource> sources = new ArrayList<>();
    private final List<Slot> retained = new ArrayList<>();
    private Slot active;
    private Status status = idle();
    private boolean closed;
    private long submitted;
    private long activated;
    private long rejected;
    private long retired;
    private long droppedEvents;
    private final List<Event> history = new ArrayList<>();
    private final Applications applications = new Applications();
    private Set<ActorSystem> actorSystems = Set.of();

    /// Creates an idle coordinator with no active generation.
    public Reload() {}

    /// Creates a coordinator and stages its first program immediately.
    public Reload(Program program) {
        submit(Revision.of(program));
    }

    /// Registers a check run in registration order before activation.
    public Reload validate(Validator validator) {
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("reload is closed");
            validators.add(Objects.requireNonNull(validator, "validator"));
        }
        return this;
    }

    /// Connects a source. The source only submits revisions; this coordinator
    /// owns staging and activation. The source is run on a virtual thread so a
    /// directory or network source can remain open without blocking startup.
    public Reload source(RevisionSource source) {
        Objects.requireNonNull(source, "source");
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("reload is closed");
            sources.add(source);
        }
        Thread.ofVirtual().name("reload-source").start(() -> {
            try {
                source.start(this::submit);
            } catch (Exception failure) {
                synchronized (monitor) {
                    if (!closed) emit(new Event(Instant.now(), "", "source-failed",
                            Map.of("message", String.valueOf(failure.getMessage()))));
                }
            }
        });
        return this;
    }

    /// Submits one revision and activates each declared surface at its safe
    /// work boundary when validation succeeds.
    public Status submit(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        synchronized (submissions) {
            return submitOne(revision);
        }
    }

    private Status submitOne(Revision revision) {
        Generation candidate = null;
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("reload is closed");
            submitted++;
            status = snapshot("staging", activeIdentity(), revision.identity(), List.of());
            emit(new Event(revision.identity(), "submitted"));
            emit(new Event(revision.identity(), "staging"));
        }

        try {
            var program = revision.program();
            if (program == null) throw new CandidateFailure(new Problem("compile", "revision has no in-process program"));
            candidate = Objects.requireNonNull(program.define(), "program.define returned null");
            synchronized (monitor) {
                status = snapshot("validating", activeIdentity(), revision.identity(), List.of());
                emit(new Event(revision.identity(), "validating"));
            }
            var problems = validate(candidate);
            if (!problems.isEmpty()) throw new CandidateFailure(problems);
            activate(revision, candidate);
            candidate = null;
        } catch (RevisionCompiler.CompilationFailure failure) {
            reject(revision, candidate, compilerProblems(failure));
        } catch (CandidateFailure failure) {
            reject(revision, candidate, failure.problems);
        } catch (Throwable failure) {
            reject(revision, candidate, List.of(Problem.exception("define", failure)));
        }
        synchronized (monitor) {
            return status;
        }
    }

    /// Submits an in-process program with an explicit revision identity.
    public Status submit(String identity, Program program) {
        return submit(Revision.of(identity, program));
    }

    /// Returns an immutable snapshot of the current coordinator state.
    public Status status() {
        synchronized (monitor) { return status; }
    }

    /// Returns the stable dispatcher for long-lived applications in active generations.
    /// The dispatcher exposes only named message turns, not mutable application objects.
    public Applications applications() {
        return applications;
    }

    /// Acquires the active generation for one unit of work.
    ///
    /// The result is empty before activation and after [#close]. Closing the
    /// lease allows a retired generation to release its resources.
    public java.util.Optional<Lease> lease() {
        synchronized (monitor) {
            if (closed || active == null) return java.util.Optional.empty();
            active.leases++;
            return java.util.Optional.of(new GenerationLease(active));
        }
    }

    /// Subscribes to ordered lifecycle events without blocking activation.
    /// A subscriber that falls behind loses its oldest queued event.
    public void subscribe(Consumer<Event> subscriber) {
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("reload is closed");
            subscribers.add(new Subscriber(Objects.requireNonNull(subscriber, "subscriber")));
        }
    }

    /// Returns at most the 1,024 most recent lifecycle events.
    public List<Event> events() {
        synchronized (monitor) { return List.copyOf(history); }
    }

    /// Returns the number of events removed from history or subscriber queues.
    public long droppedEvents() {
        synchronized (monitor) { return droppedEvents; }
    }

    private List<Problem> validate(Generation candidate) {
        var problems = new ArrayList<Problem>();
        for (var validator : validators) {
            try {
                var result = validator.validate(candidate);
                if (result != null) problems.addAll(result);
            } catch (Throwable failure) {
                problems.add(Problem.exception("validate", failure));
            }
        }
        return List.copyOf(problems);
    }

    private void activate(Revision revision, Generation candidate) {
        Slot old = null;
        Applications.Prepared prepared = null;
        try {
            prepared = applications.prepare(candidate.applicationDefinitions());
            var plans = actorPlans(candidate);
            preflightActors(plans);
            synchronized (monitor) {
                status = snapshot("activating", activeIdentity(), revision.identity(), List.of());
                emit(new Event(revision.identity(), "activating"));
            }
            activateActors(plans);
            synchronized (monitor) {
                if (closed) throw new IllegalStateException("reload is closed");
                old = active;
                active = new Slot(revision, candidate);
                applications.commit(prepared);
                prepared = null;
                actorSystems = candidateActorSystems(candidate);
                activated++;
                status = snapshot("active", revision.identity(), "", List.of(), Instant.now());
                emit(new Event(revision.identity(), "activated"));
                if (old != null) {
                    old.retired = true;
                    retained.add(old);
                    emit(new Event(old.revision.identity(), "draining",
                            Map.of("leases", String.valueOf(old.leases))));
                }
            }
        } catch (Throwable failure) {
            if (prepared != null) prepared.abort();
            if (failure instanceof CandidateFailure candidateFailure) throw candidateFailure;
            throw new CandidateFailure(Problem.exception("migrate", failure));
        }
        if (old != null && old.leases == 0) retire(old);
    }

    /// Applies actor definitions and effect handlers at the same generation
    /// boundary as leased work. Actor systems gate and drain their own
    /// mailboxes, so this call returns only after every affected system has
    /// either activated or rejected the candidate.
    private List<ActorPlan> actorPlans(Generation candidate) {
        var systems = new LinkedHashSet<>(actorSystems);
        systems.addAll(candidateActorSystems(candidate));
        var plans = new ArrayList<ActorPlan>();
        for (var system : systems) {
            var definitions = new LinkedHashMap<String, actors.Definition<?>>();
            var spawns = new LinkedHashMap<String, actors.Spawn>();
            var policies = new LinkedHashMap<String, StatePolicy>();
            candidate.actorDefinitions().stream().filter(binding -> binding.system() == system)
                    .forEach(binding -> {
                        var type = binding.definition().type();
                        if (definitions.putIfAbsent(type, binding.definition()) != null) {
                            throw new CandidateFailure(new Problem("activate",
                                    "duplicate actor definition " + type));
                        }
                        spawns.put(type, binding.spawn());
                        policies.put(type, binding.policy());
                    });
            var effects = new LinkedHashMap<String, application.Effect.Handler>();
            candidate.effectHandlers().stream().filter(binding -> binding.system() == system)
                    .forEach(binding -> {
                        if (effects.putIfAbsent(binding.type(), binding.handler()) != null) {
                            throw new CandidateFailure(new Problem("activate",
                                    "duplicate actor effect handler " + binding.type()));
                        }
                    });
            plans.add(new ActorPlan(system, definitions, spawns, effects, policies));
        }
        return List.copyOf(plans);
    }

    private void preflightActors(List<ActorPlan> plans) {
        var problems = new ArrayList<Problem>();
        for (var plan : plans) {
            plan.system().preflight(plan.definitions(), plan.spawns(), plan.effects(), plan.policies())
                    .forEach(message -> problems.add(new Problem("activate", message)));
        }
        if (!problems.isEmpty()) throw new CandidateFailure(problems);
    }

    private void activateActors(List<ActorPlan> plans) {
        for (var plan : plans) {
            ReloadResult result;
            try {
                result = plan.system().reload(plan.definitions(), plan.spawns(),
                        plan.effects(), plan.policies());
            } catch (RuntimeException failure) {
                throw new CandidateFailure(Problem.exception("activate", failure));
            }
            if (!result.activated()) {
                var messages = result.problems().stream()
                        .map(problem -> new Problem("activate", problem)).toList();
                if (messages.isEmpty()) throw new CandidateFailure(
                        new Problem("activate", "actor activation was refused"));
                throw new CandidateFailure(messages);
            }
        }
    }

    private static Set<ActorSystem> candidateActorSystems(Generation candidate) {
        var systems = new LinkedHashSet<ActorSystem>();
        candidate.actorDefinitions().forEach(binding -> systems.add(binding.system()));
        candidate.effectHandlers().forEach(binding -> systems.add(binding.system()));
        return Set.copyOf(systems);
    }

    private void reject(Revision revision, Generation candidate, List<Problem> problems) {
        if (candidate != null) closeQuietly(candidate);
        synchronized (monitor) {
            rejected++;
            status = snapshot(active == null ? "rejected" : "active", active == null ? "" : active.revision.identity(),
                    revision.identity(), problems);
            emit(new Event(revision.identity(), "rejected", Map.of("problems", String.valueOf(problems.size()))));
        }
    }

    private void release(Slot slot) {
        var retire = false;
        synchronized (monitor) {
            if (slot.leases > 0) slot.leases--;
            retire = slot.retired && slot.leases == 0;
        }
        if (retire) retire(slot);
    }

    private void retire(Slot slot) {
        synchronized (monitor) {
            if (slot.closed || !slot.retired || slot.leases != 0) return;
            slot.closed = true;
            retired++;
        }
        closeQuietly(slot.generation);
        synchronized (monitor) {
            emit(new Event(slot.revision.identity(), "retired"));
            status = snapshot(closed ? "closed" : active == null ? "idle" : "active",
                    active == null ? "" : active.revision.identity(),
                    "", List.of());
        }
    }

    /// Stops sources and refuses new leases and application work.
    ///
    /// The call waits for an admitted application turn. An existing lease
    /// keeps its generation until it closes. The host still owns actor systems.
    @Override
    public void close() {
        synchronized (submissions) {
            closeOne();
        }
    }

    private void closeOne() {
        List<RevisionSource> closing;
        List<Subscriber> observations;
        Slot toRetire = null;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            closing = List.copyOf(sources);
            sources.clear();
            observations = List.copyOf(subscribers);
            subscribers.clear();
            if (active != null) {
                active.retired = true;
                if (active.leases == 0) toRetire = active;
                active = null;
            }
            status = snapshot("closed", "", "", List.of());
        }
        for (var source : closing) closeQuietly(source);
        applications.close();
        if (toRetire != null) retire(toRetire);
        observations.forEach(Subscriber::close);
    }

    private Status snapshot(String phase, String activeRevision, String candidateRevision, List<Problem> problems) {
        return snapshot(phase, activeRevision, candidateRevision, problems,
                active == null ? null : status.activatedAt());
    }

    private Status snapshot(String phase, String activeRevision, String candidateRevision,
            List<Problem> problems, Instant activationTime) {
        var leaseMap = new LinkedHashMap<String, Integer>();
        if (active != null) leaseMap.put(active.revision.identity(), active.leases);
        for (var slot : retained) if (!slot.closed) leaseMap.put(slot.revision.identity(), slot.leases);
        var rejectedRevision = problems.isEmpty() ? status.rejectedRevision() : candidateRevision;
        var lastProblems = problems.isEmpty() ? status.problems() : problems;
        var currentCandidate = problems.isEmpty() ? candidateRevision : "";
        return new Status(phase, activeRevision, activationTime,
                currentCandidate, rejectedRevision, lastProblems, leaseMap,
                submitted, activated, rejected, retired);
    }

    private void emit(Event event) {
        synchronized (monitor) {
            if (history.size() == 1024) {
                history.removeFirst();
                droppedEvents++;
            }
            history.add(event);
            subscribers.forEach(subscriber -> {
                if (!subscriber.offer(event)) droppedEvents++;
            });
        }
    }

    private String activeIdentity() {
        return active == null ? "" : active.revision.identity();
    }

    private static List<Problem> compilerProblems(RevisionCompiler.CompilationFailure failure) {
        if (failure.problems().isEmpty()) return List.of(new Problem("compile", failure.getMessage()));
        return failure.problems().stream().map(problem -> new Problem("compile",
                problem.source(), problem.line(), problem.message())).toList();
    }

    private static Status idle() {
        return new Status("idle", "", null, "", "", List.of(), Map.of(), 0, 0, 0, 0);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) {}
    }

    private final class Slot {
        private final Revision revision;
        private final Generation generation;
        private int leases;
        private boolean retired;
        private boolean closed;

        private Slot(Revision revision, Generation generation) {
            this.revision = revision;
            this.generation = generation;
        }

        private void release() { Reload.this.release(this); }
    }

    private final class GenerationLease implements Lease {
        private final Slot slot;
        private boolean released;
        private GenerationLease(Slot slot) {
            this.slot = slot;
        }

        @Override public Generation generation() { return slot.generation; }

        @Override public synchronized void close() {
            if (!released) {
                released = true;
                slot.release();
            }
        }
    }

    private static final class Subscriber implements AutoCloseable {
        private static final int CAPACITY = 256;
        private final Consumer<Event> consumer;
        private final LinkedBlockingDeque<Event> events = new LinkedBlockingDeque<>(CAPACITY);
        private final Thread thread;

        private Subscriber(Consumer<Event> consumer) {
            this.consumer = consumer;
            thread = Thread.ofVirtual().name("reload-events").start(this::deliver);
        }

        private boolean offer(Event event) {
            if (events.offerLast(event)) return true;
            events.pollFirst();
            events.offerLast(event);
            return false;
        }

        private void deliver() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try { consumer.accept(events.takeFirst()); }
                    catch (RuntimeException ignored) {}
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override public void close() { thread.interrupt(); }
    }

    private record ActorPlan(ActorSystem system,
            Map<String, actors.Definition<?>> definitions,
            Map<String, actors.Spawn> spawns,
            Map<String, application.Effect.Handler> effects,
            Map<String, StatePolicy> policies) {}

    private static final class CandidateFailure extends RuntimeException {
        private final List<Problem> problems;
        private CandidateFailure(Problem problem) { this(List.of(problem)); }
        private CandidateFailure(List<Problem> problems) { this.problems = List.copyOf(problems); }
    }
}
