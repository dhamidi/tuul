# Reload reference

This document specifies the `reload` package. It is an implementation contract.

For a first program, read [tutorial.md](tutorial.md). For tasks, read
[howto.md](howto.md). For design reasons, read [guide.md](guide.md).

## Scope

The package defines revision submission, compilation attachment, generation
validation, activation, capabilities, leases, application turns, actor handoff,
state transfer, status, and events. It provides in-process revision sources.
The `project.Dev` command provides the directory source.

The package does not define authentication, authorization, signing policy,
artifact storage, a Java security sandbox, cluster consensus, or a user
interface. An application or deployment system supplies those policies.

## Terms

| Term | Meaning |
|---|---|
| revision | One immutable source and resource input, identified by content. |
| revision source | A producer that submits revisions to one coordinator. |
| candidate | A revision being staged or validated. |
| generation | One loaded `Program` result and its owned resources. |
| active generation | The generation leased by new work. |
| lease | One claim that keeps a generation available to work already in progress. |
| drain | The period after retirement while leases remain. |
| retirement | Removal from new work after another generation activates. |
| rejection | A candidate failure that leaves the active generation unchanged. |

## Public types

| Type | Purpose |
|---|---|
| `Program` | Stable interface implemented by a reloadable entrypoint. |
| `Generation` | Complete capability, application, actor, effect, and resource definition. |
| `Revision` | Immutable materialized input and content identity. |
| `RevisionSource` | Producer that submits revisions without activating them. |
| `RevisionCompiler` | In-memory compiler and child-loader bridge for a path-backed revision. |
| `Reload` | Coordinator that stages, validates, activates, drains, and closes generations. |
| `ApplicationDefinition` | Named application factory and optional JSON state transfer. |
| `Applications` | Stable dispatcher for named applications. |
| `Validator` | Candidate check that runs before activation. |
| `StatePolicy` | Rule for state that replay cannot reconstruct. |
| `Status` | Immutable coordinator snapshot. |
| `Event` | Immutable lifecycle observation. |
| `Problem` | One compiler, loader, validator, migration, or activation problem. |
| `Capability` | Typed key for one value carried by a generation. |
| `Lease` | Claim that keeps one active generation available for one unit of work. |

`MemoryRevisionSource` submits `Revision` values through the `RevisionSource`
contract. Web adapters can use the same contract.

## `Program`

`Program` is loaded from each candidate generation. The parent loader owns the
interface. The candidate loader owns the implementation.

`define()` returns one complete `Generation`. The coordinator calls it once for
a candidate. The method may construct resources. It must not start the host
HTTP server, activate actors, mutate the current generation, or submit another
revision.

If `define()` throws, the candidate is rejected. The program must close a
resource that it constructed but could not return in a `Generation`. Resources
in a returned generation close when validation later rejects that candidate.

The conventional implementation is the `main` class in the selected
`src/<entrypoint>/main.java` source set. It needs an accessible no-argument
constructor.

## `Generation`

`Generation.empty()` starts a generation with no capabilities. Attach values
with `Generation.with` and find them with `Generation.capability`.

Generation construction adds these values:

| Value | Rule |
|---|---|
| capability | One typed value carried by the generation. |
| actor definition | One definition and spawn policy per actor type. |
| effect handler | One handler per effect type. A later duplicate is rejected. |
| application definition | One application per stable application name. |
| resource | Closed in reverse registration order after drain. |

The generation becomes immutable when `Program.define()` returns. Validation
and activation read the same snapshot. It is a complete set: omitting a named
application removes it. Omitting an actor system that a prior generation used
submits an empty actor set to that system; its durable logs or loaded ephemeral
state can refuse the removal during preflight.

## `Revision`

A revision has an identity, root, selected entrypoint, sources, resources,
dependencies, and an optional host-owned program.

The identity is a SHA-256 digest of normalized entry names and contents. Entry
order and filesystem modification time do not change it. A caller that supplies
an identity must also supply or run a verifier before activation.

The root is staging storage owned by the source or deployment host. A
`RevisionCompiler` reads it but does not delete it. The owner keeps it until
compilation finishes and then removes it.

`RevisionCompiler` rejects an empty source set with a compile problem.

## `RevisionSource`

A revision source receives a submission callback when it starts. It may submit
zero or more revisions until it closes.

Each source calls the callback in its own submission order. The coordinator
serializes candidate work and does not interrupt validation or activation.

Closing a source stops future submissions. It does not close the coordinator.
A source failure emits a `source-failed` event with its message. It does not
retire the active generation.

`RevisionSource#map(UnaryOperator)` creates a source-neutral compilation seam.
The transform receives a staged `Revision` and returns the revision that the
host can submit to `Reload`. A source does not load or activate classes. A
typical host wires a source like this:

```java
var compiler = new RevisionCompiler(hostClasspath);
var source = new MemoryRevisionSource();
reload.source(source.map(compiler::compile));
```

The compiler is host code because compilation and class loading are deployment
policy. The source only materializes an immutable tree.

## Directory source

`project.Dev.DirectorySource` observes `src/` and `vendor/`. It submits one
revision after their content changes and becomes stable for the configured
quiet period. Project resources are below `src/resources/`.

It ignores build output because build output is outside those roots. It ignores
event names that start with `.` or end with `~`, `.swp`, or `.tmp`. Create,
modify, rename, and delete events trigger a complete rebuild.

The public source constructor submits an initial scan. The `tuul dev` host
builds the first revision before it opens the server and then starts the source
without a second initial scan.

## Compilation and loading

`RevisionCompiler` and `project.Dev.Builder` capture each candidate class and
resource in memory. Reload compilation creates no class output directory.

The compiler receives the selected source set, runtime dependencies, the
current JDK release, and debug output enabled. A compiler failure rejects the
candidate and records javac diagnostics as compile problems.

The loader delegates Tuul and JDK contracts to its parent. It loads application
classes and resources from the candidate snapshot. A revision may contain
`module-info.java`; reload compilation omits that descriptor and loads the
candidate as an unnamed child generation.

Closing a retired loader stops new class and resource loads. Loaded classes
remain usable while a lease or resource still refers to them.

### `RevisionCompiler`

Construct `RevisionCompiler(parentClasspath)` for the JDK compiler, or pass a
`compiler.Compiler` and `parentClasspath` for a test double. The compiler
captures classes and resources in memory. `parentClasspath` makes the
host-owned contracts visible to javac; the running parent class loader must own
the same contracts.

`compile(revision)` preserves identity and paths and attaches a lazy `Program`.
It returns an in-process revision that `Reload.submit` can stage. Compilation
happens during staging. The source or deployment host still owns the revision's
staging root.

## Validation

Validators run in registration order after `Program.define()` returns. A
validator receives the immutable candidate generation and a context that
cannot activate it.

A validator returns zero or more problems. One problem rejects the candidate.
A validator that throws produces one validation problem.

Built-in activation checks duplicate application names, actor types, and actor
effect types. `ActorSystem.preflight` checks definitions, policies, loaded
state, and durable replay with effects suppressed. Web builders such as
`Router` validate their own definitions while `Program.define()` runs.

Validation must not perform an external effect. A custom validator that needs
I/O belongs in an explicit deployment gate, not the default fast validation
path.

## Activation state machine

One coordinator has these candidate states:

```text
submitted -> staging -> validating -> activating -> active
                    \-> rejected
                               activating -> rejected
```

The coordinator first prepares application state and preflights every actor
system. It then performs actor handoffs and commits the application set. Each
surface changes only at its safe boundary. A candidate with more
than one actor system is not a distributed transaction: if a later system has
an unexpected runtime handoff failure after preflight, an earlier system can
already be active. Use one actor system per reload coordinator when this
distinction is not acceptable.

An old in-flight unit of work can send an actor command while actor handoff is
in progress. The candidate gate validates that command. Keep command schemas
backward-compatible until old calls drain. Generation activation does not
make an external call across two independent runtimes transactional.

After the pointer changes, the prior generation becomes `draining`. It becomes
`retired` when its last lease ends and its resources close.

The first valid revision starts the reload surfaces. Before it, the lease
result is empty and the named application dispatcher has no names. The host
owns each `ActorSystem`; do not register reload-managed actor types outside the
generation protocol.

## Leases

Call `Reload.lease()` before one unit of work. The result is empty when no
generation is active or after the coordinator closes. Close a present `Lease`
when the work ends, including when the work throws. The lease's `generation()`
value remains fixed until close.

An adapter can use this contract to keep one external request on one
generation. The `web.reload` package provides an HTTP adapter as one example.

## Application turns

A named long-lived application acquires one generation for one closed dispatch
turn. The update and every effect requested by that turn use that generation.
Messages emitted by those effects enter the next turn after the prior turn
ends.

Activation waits for admitted turns to reach their boundary. A definition
restarts, transfers versioned JSON, or refuses state according to
`StatePolicy`. Named application replay is not implemented.

Create a definition with a stable name, a state schema version, and a factory:

```java
var counter = ApplicationDefinition.of("counter", "2", () -> counterApplication());
var generation = Generation.empty().application(counter, StatePolicy.RESTART);
```

`withTransfer(snapshot, transfer)` adds the two functions required by
`StatePolicy.TRANSFER`. The old definition converts its state to a `Json.Object`.
The candidate receives the old version and JSON and creates its new
`application.Application`.

`Reload.applications()` returns the stable host dispatcher.
`dispatch(name, messages...)` returns no candidate-owned state object.
`state(name)` returns an immutable `Applications.State` with the name, version,
and JSON value, and therefore requires a snapshot function. `names()` and
`generation()` expose the active application set.

## Actor turns

Activation gates new deliveries to an affected actor type. Loaded actors finish
the current turn and mail accepted before the gate. The gate validates against
the candidate declaration and uses the candidate mailbox capacity.

A durable actor then stops without deleting its log. The candidate definition
instantiates a new behavior and replays the log without effects. The runtime
sends `actors.resume` once before it admits gated mail.

Actor address and type are stable identities. Renaming an actor type is a data
migration outside ordinary reload.

An unknown historical command rejects activation. A definition must not rely
on the application's default ignored update during replay validation.

`Generation.actor(system, definition)` adds a durable actor with replay policy.
The overloads accept explicit `Spawn` and `StatePolicy` values.
`Generation.effect(system, type, handler)` adds an actor effect handler.
`ActorSystem.preflight` validates a complete proposed set without mutation;
`ActorSystem.reload` performs the awaited handoff.

## Effect handlers

An effect uses the generation of the turn that requested it. A replacement
handler applies only to later turns.

Activation does not cancel an effect. Application activation waits for the
complete dispatch turn. Actor activation stops an actor only after admitted
mail and its effects finish. The existing application or actor patience can
stop waiting for an effect and fence a late emission.

## `StatePolicy`

State that cannot remain in the parent loader declares one policy:

| Policy | Result |
|---|---|
| `restart` | The candidate uses its initial state. |
| `replay` | A durable actor rebuilds state from recorded commands. |
| `transfer` | The old generation exports JSON and the candidate imports or migrates it. |
| `refuse` | A loaded state rejects activation. |

`transfer` data contains a schema or version identity. Export and import run at
a turn boundary. They run no effects. A failed export or import rejects
activation and leaves the old state active.

The default for a durable actor is `replay`. The default for a loaded ephemeral
actor or long-lived application is `refuse`. Long-lived applications support
`restart`, `transfer`, and `refuse`. Actors support `restart`, `replay`, and
`refuse`; actor JSON transfer is not implemented.

## Status

`Status` is a snapshot. It stops changing when returned.

It contains:

- the active revision and activation time;
- the candidate revision and phase, when one exists;
- the last rejected revision and its problems;
- lease counts for active and draining generations;
- submitted, activated, rejected, and retired counts.

No active generation is represented by an absent active revision.

## Events

The coordinator emits these lifecycle kinds:

| Kind | Meaning |
|---|---|
| `submitted` | A source submitted the revision. |
| `staging` | Compilation or loading started. |
| `validating` | Candidate validation started. |
| `rejected` | Problems stopped the candidate. |
| `activating` | The coordinator started the generation boundary. |
| `activated` | New work can lease the generation. |
| `draining` | A replaced generation still has leases. |
| `retired` | The generation has no leases and its resources closed. |
| `source-failed` | A revision source could not produce a complete revision. |

Each event contains its time, revision identity, kind, and string detail map.
Each subscriber has an ordered queue of 256 events. A full queue loses its
oldest event. The retained history has 1,024 events. The coordinator counts
subscriber and history drops.

## Problems

A problem has a phase, source name, line, and message. The phase is `source`,
`compile`, `load`, `define`, `validate`, `migrate`, or `activate`.

Source name may be empty when the failing operation has no source file. Line is
zero when the operation has no source line. Problems are immutable and retain
candidate order.

## Earlier revisions

The coordinator does not retain a live class loader for rollback. Submit an
earlier source artifact again to compile it into a new candidate. Normal actor
replay and application state policy checks apply. External effects are not
reversed.

## Shutdown

Closing the coordinator stops revision sources and new application work. It
waits for an admitted application turn. A leased unit that is already running
keeps its generation; `close()` returns and that generation closes when the
unit finishes. The coordinator does not close an actor system,
because the host owns that stable system.

Closing twice has no effect. An interrupted close restores the interrupt flag
and continues closing resources that do not require waiting.
