# How-to

Each section is one task. Do the steps in order.

Exact API and state rules are in [reference.md](reference.md). Design reasons
are in [guide.md](guide.md).

## Submit revisions without a file watcher

Use a `RevisionSource` when another system decides that code changed.

1. Materialize one immutable source and resource tree.
2. Compute or receive its revision identity.
3. Build a `Revision` for that tree.
4. Attach a compiled `Program`, normally with `RevisionCompiler`.
5. Submit it to the reload coordinator.
6. Keep the staged tree until compilation finishes, then remove it.

An artifact receiver and test double follow these steps. Do not call a watcher
from the coordinator. Do not make a source activate a class directly.

## Validate a generation before activation

Register validators on the coordinator before a source submits work.

1. Compile every source into a new in-memory generation.
2. Load the selected `Program` from that snapshot.
3. Call `Program.define` once.
4. Check duplicate application, actor, and actor-effect bindings.
5. Run the configured smoke validators.
6. Replay affected durable actor logs without effects.
7. Activate only when every check succeeds.

A validator does not change the active generation. It does not run an external
effect. It records a `Problem` when it rejects the candidate.

## Reload a durable actor definition

1. Add the actor definition and its `Spawn` value to the generation.
2. Add each external effect handler that the definition can request.
3. Keep the actor type and address stable.
4. Make the new definition understand every recorded command.
5. Submit the revision.

During activation, the actor system gates new deliveries for the affected
type. Each loaded actor finishes mail admitted before the gate. The system
installs the new definition. The next delivery summons the actor, rebuilds
state by replay, runs `actors.resume`, and then handles live mail.

Replay never runs effects. A timer or cache that must exist again belongs in a
`resuming` update.

Use a versioned snapshot or migration only when replay alone cannot construct
the new state. Bind the snapshot to the generation that wrote it.

## Reload an ephemeral actor

Choose the state policy when you add the actor to the generation.

- Use `restart` when the actor may start again from its initial state.
- Use `refuse` when losing the state must reject activation.

The default is `refuse` for a loaded ephemeral actor. Actor JSON transfer is
not implemented. Use a durable actor and replay when state must survive.

## Reload a long-lived application

Register the application under a stable name. Select how its state crosses the
generation boundary.

- Use `restart` for disposable state.
- Use `transfer` when the old generation can export JSON and the candidate can
  import or migrate it.
- Use `refuse` when none of those choices is correct.

The coordinator changes the application between closed dispatch turns. A
dispatch that has started uses one generation for its messages and all effects
they request.

Each generation is a complete definition. Omitting the name removes the
application and its in-memory state.

## Replace an effect handler

Add effect handlers to the same generation as the updates that request them.
Do not mutate the handler map of a running application.

The enclosing application or actor turn keeps its handler generation. A handler
already running finishes, times out, or becomes fenced before that turn reaches
its activation boundary. If the handler owns a closeable resource, also add
that resource with `Generation.closing`.

## Inspect reload status

Read the coordinator's snapshot for automation. Subscribe to events for a live
operator view.

The snapshot names the active revision, candidate revision, and last rejected
revision. Problems keep
their source name, line, and message.

Events are observations. A slow event subscriber does not delay activation.

## Return to an earlier revision

Keep the earlier source artifact in deployment storage. Submit it again as a
new candidate. It goes through compilation, validation, actor replay, state
policy checks, and normal activation. The coordinator does not reactivate a
closed module layer and does not undo external effects.

## Test without files or sockets

Use a fake `RevisionSource`. Submit revisions explicitly. Acquire a lease for
one unit of work and close it after that work ends.

Assert these transitions:

1. The first valid revision becomes active.
2. A later valid revision changes the next acquired lease.
3. Work already admitted finishes on its original generation.
4. A broken revision leaves the active generation unchanged.
5. The old generation closes after its last lease ends.

Keep these checks in a fast suite. Put a real watcher, javac, process, socket,
or filesystem project in an integration suite.
