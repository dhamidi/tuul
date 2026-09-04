# Why reload uses generations

Use a generation when several definitions must change as one behavior. A file
is only evidence that a revision may exist. It is not a safe activation unit.

## A save is not an activation

A Java source change can produce several class definitions. One class can define an
application update while another defines the effect handler that update
requests. Activating the first class before the second creates behavior that no
source revision described.

A compiler adapter captures the complete revision in an isolated in-memory
module layer. The coordinator defines and validates the complete generation
result.
Capability adapters and named applications then change through stable
dispatchers. Actor systems perform their own gated turn boundary.

This rule gives development and production the same behavior. Development gets
its revisions from a directory. Production may get a signed revision from an
artifact service. Both use the same staging and activation state machine.

## The parent owns contracts

Keep Tuul, reload contracts, and durable runtime objects in the parent module
layer. Load application implementations in a fresh child `ModuleLayer`.

Java type identity includes the defining loader. A child copy of
`web.ui.Component` is not the parent's `web.ui.Component`, even when the bytes
are identical. Parent delegation keeps framework interfaces singular and lets
candidate classes implement them.

The layer boundary also gives application code a lifetime. Retirement removes
runtime references to that layer after work and resources drain. The boundary
does not constrain file, network, process, reflection, or native access.

## Work keeps one meaning

Lease a generation for a unit of work whose definitions must agree.

A web request leases before routing. An application or actor turn leases before
the update. Effects requested by that update keep the same lease. The runtime
does not change an effect handler halfway through the turn because a revision
activated.

This permits overlap. A slow old request can finish while a new request uses
the new page. The overlap is explicit and bounded by leases rather than hidden
in whichever objects the garbage collector can still reach.

## Actor replay is a migration mechanism

A durable actor stores commands. Its state is the fold of those commands
through the definition registered now. Reload can therefore change the state
class and update logic without copying the old state object across loaders.

Replay also applies bug fixes to derived state. That power requires
compatibility. The candidate must understand old commands and must produce
valid state for them. Validation performs the fold without effects before the
definition becomes active.

A migration or snapshot is necessary only when replay cannot express the
change or the log has become too expensive. Bind either value to a generation.
An unversioned snapshot can silently restore state computed by obsolete code.

## Other state needs a declared policy

A Java object whose class belongs to a retired loader cannot become the state
of a candidate application by cast. Reflection does not make that transfer
safe. Serialization without an owned schema only moves the incompatibility to
runtime.

The package makes the choice visible: restart, replay, transfer JSON, or refuse.
Refusal is the safe default for loaded state that has no declared transition.

JSON is the transfer boundary because Tuul owns its value types in the parent
loader. A candidate can validate and migrate a versioned JSON document without
holding the old state class.

## Effects make rollback asymmetric

Submitting old source can restore old code. It cannot unsend an email, remove a
payment, or make a remote write disappear.

An effect therefore keeps the generation that requested it. Production
handlers should use idempotency keys when a retry or redelivery is possible.
The old source is a new candidate and must pass current durable replay and state
policy checks. This does not reverse the outside world.

## Resources drain with code

An effect handler can own a connection pool. A web feature can own a cable. A
candidate can construct these resources before activation.

The generation owns them. A rejection closes candidate resources immediately.
A replacement closes old resources after the last lease. Reverse close order
preserves the dependency order declared during construction.

This release has no forced drain deadline. One streaming handler can therefore
retain a generation until the handler returns. A host that needs a deadline
must put the timeout in its handler or server boundary.

## A watcher belongs at the edge

A watcher answers one question: did a configured tree produce different
content? It submits a revision after the tree becomes stable.

It does not compile, validate, activate, refresh browsers, or reload actors.
Those actions belong to the coordinator. The `web.reload` HTTP adapter or an
artifact receiver can replace the watcher without duplicating reload behavior.

## The coordinator has an application shape

The coordinator models its work as messages, state transitions, and effects. A submitted
revision changes state to staging and requests compilation. A compiler result
changes state to validation and requests validators. An accepted candidate
changes state to activation and requests the surface boundaries.

This shape serializes decisions while compilation and validation use external
work. It also makes every transition observable and testable without a watcher,
compiler, socket, or module layer in the fast suite.

The coordinator does not need a durable actor for local development. A
production control plane may journal revision commands when it needs an audit
history or recovery after the host restarts.

## Production submission is deployment

Authenticate the principal that submits a revision. Authorize the application
and environment it may change. Verify content before compilation. Record the
revision, principal, validation result, activation time, and result of
submitting an earlier revision.

Use process or operating-system isolation when submitted source is not trusted.
A class-loader boundary controls class lifetime. It is not a security sandbox.

Use canaries by deciding which host receives a verified revision first. Do not
put percentage routing inside one generation pointer. Each host must still
activate one coherent generation for its own work.

## JDK providers stay inside the generation

Use `JdkServiceFactory` to load a supported JDK provider from the candidate
root module. The factory stores the provider in `Generation.services` and
registers closeable providers as generation resources.

Use `JdkToolCatalog` to list and run `ToolProvider` values. The catalog reads
providers under one lease and returns only tool metadata. A duplicate tool name
rejects the operation in deterministic order.

The [tutorial ToolProvider example](tutorial.md#run-a-generation-owned-jdk-tool)
shows the complete candidate descriptor, provider class, and host wiring. The
candidate descriptor provides `java.util.spi.ToolProvider`; `module tuul` owns
the `uses` declaration. `RevisionCompiler` reads the compiled descriptor rather
than candidate source text.

The same boundary applies to `com.sun.source.util.Plugin`. The candidate
provides the plugin, and the host obtains it from `Generation.service` while a
lease is open. The host creates the `JavacTask`, calls `Plugin.init`, and calls
the task before closing the lease. The plugin, task listener, and other
candidate products do not escape that lease.

`RevisionCompiler` compiles the candidate before `JdkServiceFactory` loads its
providers. A loaded `Processor` or `Plugin` therefore does not affect the
compilation that creates its generation. The host passes it to a later,
host-owned compilation task.

Do not install a provider into a JDK global registry. Selector, networking,
charset, time-zone, logger, security, JDBC, print, sound, attach, RMI, and
preferences providers keep process-wide or one-shot state and cannot form a
safe layer boundary.
