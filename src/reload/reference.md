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
| `RevisionCompiler` | In-memory compiler and named-layer bridge for a path-backed revision. |
| `GenerationFactory` | Defines a generation from a compiled candidate context. |
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
| `CandidateContext` | Root module and layer used to load candidate services. |
| `ServiceGenerationFactory` | Loads selected root services and stores their providers. |
| `JdkServiceFactory` | Restricts service loading to supported JDK adapter services. |
| `JdkToolCatalog` | Lists and runs generation-owned `ToolProvider` values. |

`MemoryRevisionSource` submits `Revision` values through the `RevisionSource`
contract. Web adapters can use the same contract.

## JDK service adapters

Construct `JdkServiceFactory` with the supported service classes that the root
module can provide. A selected class with no root provider produces an empty
provider list. Compose the factory with another generation factory when the
candidate exposes more than one service:

```java
var factory = GenerationFactory.compose(List.of(
        new ProgramGenerationFactory(),
        new JdkServiceFactory(java.util.spi.ToolProvider.class)));
```

The factory loads only providers declared by the candidate root module. It
stores immutable provider lists in the generation and closes providers that
implement `AutoCloseable` after the generation drains. It does not use the
system loader, the thread context loader, or a JDK global registry.

`JdkServiceFactory.supportedServices()` lists the accepted service classes.
They include `ToolProvider`, `FileSystemProvider`, `ScriptEngineFactory`, the
public JAXP factories, JMX connector providers, `Processor`, and javac
`Plugin`. Invoke a provider while its generation lease is open. Close each
file system, JMX connector, JMX server, stream, or other closeable product
before the lease ends. Do not keep an engine, parser, transformer, processor,
or plugin after the lease ends.

Loading a `Processor` or javac `Plugin` does not apply it to the compilation
that created the generation. A host can pass the loaded provider to a later,
host-owned compilation task.

`JdkToolCatalog` takes only a `Reload`. `list()` returns immutable name and
description records. It returns an empty list before the first activation.
`run(name, out, err, args)` finds one name, runs it while one lease is open,
and returns the tool exit code. It throws `IllegalArgumentException` for an
unknown name and rejects duplicate names in deterministic order. The catalog
does not expose provider objects.

Do not pass JVM-global or one-shot JDK SPIs to this factory. Tuul rejects
selector and asynchronous-channel providers, URL and content-handler
factories, charset and time-zone providers, logger finders, security
providers, JDBC drivers, print and sound providers, attach providers, RMI
providers, preferences providers, and `HttpServerProvider`. The JDK caches one
system-wide `HttpServerProvider`. The web host instead owns one stable server
and invokes the generation's `HttpHandler` directly.

### External `ToolProvider` module

Put the service declaration and provider class in the candidate root:

```java
// candidate/module-info.java
module example.tools {
    provides java.util.spi.ToolProvider with example.tools.EchoTool;
}
```

```java
// candidate/example/tools/EchoTool.java
package example.tools;

import java.io.PrintWriter;
import java.util.spi.ToolProvider;

public final class EchoTool implements ToolProvider {
    public String name() { return "echo"; }
    public java.util.Optional<String> description() {
        return java.util.Optional.of("writes each argument");
    }
    public int run(PrintWriter out, PrintWriter err, String... arguments) {
        for (var argument : arguments) out.println(argument);
        return 0;
    }
}
```

Build and load that root from the stable host:

```java
var root = Path.of("candidate");
var descriptor = root.resolve("module-info.java");
var provider = root.resolve("example/tools/EchoTool.java");
var source = new Revision.SourceModule("example.tools", root, descriptor,
        List.of(descriptor, provider), List.of());
var factory = new JdkServiceFactory(java.util.spi.ToolProvider.class);
var compiler = new RevisionCompiler(List.of(), factory);
var revision = compiler.compile(Revision.from("example.tools", List.of(source), List.of()));
try (var reload = new Reload()) {
    reload.submit(revision);
    var catalog = new JdkToolCatalog(reload);
    var tools = catalog.list();
    var exit = catalog.run("echo", out, err, "hello");
}
```

`module tuul` owns `uses java.util.spi.ToolProvider`. The candidate root owns
`provides`. `RevisionCompiler` reads the compiled `module-info.class`, and
`CandidateContext` loads only provider names from that compiled descriptor. It
does not inspect source text for providers.

### External javac `Plugin` module

Declare the standard plugin service in the candidate root:

```java
module example.plugin {
    requires jdk.compiler;
    provides com.sun.source.util.Plugin with example.plugin.AuditPlugin;
}
```

Implement the provider with the JDK task API:

```java
package example.plugin;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;

public final class AuditPlugin implements Plugin {
    public String getName() { return "audit"; }
    public void init(JavacTask task, String... arguments) {
        task.addTaskListener(new com.sun.source.util.TaskListener() {
            public void started(com.sun.source.util.TaskEvent event) {}
            public void finished(com.sun.source.util.TaskEvent event) {}
        });
    }
}
```

Load the plugin with `new JdkServiceFactory(Plugin.class)`. The host owns the
compiler task and keeps the plugin call in the lease:

```java
try (var lease = reload.lease().orElseThrow()) {
    var plugin = lease.generation().service(com.sun.source.util.Plugin.class)
            .orElseThrow();
    plugin.init(hostTask);
    var ok = Boolean.TRUE.equals(hostTask.call());
}
```

`RevisionCompiler` does not pass the loaded plugin to the compilation that
creates its generation. A later host-owned task can use the plugin. The host
must not return the provider, task, listener, or another candidate object after
the lease closes.

## `Program`

`Program` is loaded from each candidate generation when the source uses Tuul.
The parent module owns the interface. The candidate module owns the
implementation. An external source can provide
`com.sun.net.httpserver.HttpHandler` instead; `web.reload` defines that
generation through a JDK service provider.

`define()` returns one complete `Generation`. The coordinator calls it once for
a candidate. The method may construct resources. It must not start the host
HTTP server, activate actors, mutate the current generation, or submit another
revision.

If `define()` throws, the candidate is rejected. The program must close a
resource that it constructed but could not return in a `Generation`. Resources
in a returned generation close when validation later rejects that candidate.

The host selects the generation factory. A Tuul-aware module provides exactly
one `reload.Program` service with an accessible no-argument constructor. An
external HTTP module provides exactly one
`com.sun.net.httpserver.HttpHandler` service. The service descriptor is part of
the compiled module and remains in the in-memory layer.

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

A revision has an identity, a root module, a complete named source-module
closure, dependencies, and an optional host-owned program.

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
var compiler = new RevisionCompiler(hostModulePath, new ProgramGenerationFactory());
var source = new MemoryRevisionSource();
reload.source(source.map(compiler::compile));
```

The compiler is host code because compilation and module-layer loading are
deployment policy. The source only materializes an immutable tree.

## Directory source

`project.Dev.DirectorySource` observes `src/`, `entrypoints/`, and `vendor/`.
It submits one revision after their content changes and becomes stable for the
configured quiet period. Project resources are below `src/resources/`.

It ignores build output because build output is outside those roots. It ignores
event names that start with `.` or end with `~`, `.swp`, or `.tmp`. Create,
modify, rename, and delete events trigger a complete rebuild.

The public source constructor submits an initial scan. The `tuul dev` host
builds the first revision before it opens the server and then starts the source
without a second initial scan.

## Compilation and loading

`RevisionCompiler` and `project.Dev.Builder` capture each candidate class and
resource in memory. Reload compilation writes no `.class` file to disk.

The compiler receives the selected source set, runtime dependencies, the
current JDK release, and debug output enabled. A compiler failure rejects the
candidate and records javac diagnostics as compile problems.

The compiler includes every `module-info.java` and source in the selected
module closure. It defines a fresh `ModuleLayer` for each generation. The
parent layer supplies host contracts and the candidate layer supplies the
revision modules. Compilation writes no `.class` files to disk.

Retirement stops new leases for the old layer. Loaded classes and resources
remain usable while a lease or generation resource still refers to them. The
generation closes its resources after the last lease drains.

### `RevisionCompiler`

Construct `RevisionCompiler(parentModulePath, factory)` for the JDK compiler,
or pass a `compiler.Compiler`, parent module path, and factory for a test
double. The compiler captures classes and resources in memory. The module path
must contain every resolved project and dependency module required by the
candidate.

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

The coordinator does not retain a live module layer for rollback. Submit an
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
