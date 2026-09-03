# How-to

Each section is one task. Do the steps in order.

Exact API and state rules are in [reference.md](reference.md). Design reasons
are in [guide.md](guide.md).

## Run a project during development

1. Put the reloadable entrypoint in `src/<entrypoint>/main.java`.
2. Make `main` implement `Program`.
3. Return the complete `Generation` from `define`.
4. Run `tuul dev <entrypoint> --port <port>`.

```sh
tuul dev web --port 8080
```

Omit the entrypoint when the project has a `web` entrypoint or only one
entrypoint. Omit the port to use 8080.

The command keeps the last valid generation active after a compiler or
validation failure. Stop it with Control-C.

## Submit revisions without a file watcher

Use a `RevisionSource` when another system decides that code changed.

1. Materialize one immutable source and resource tree.
2. Compute or receive its revision identity.
3. Build a `Revision` for that tree.
4. Attach a compiled `Program`, normally with `RevisionCompiler`.
5. Submit it to the reload coordinator.
6. Keep the staged tree until compilation finishes, then remove it.

An HTTP handler, artifact receiver, and test double follow these steps. Do not
call a watcher from the coordinator. Do not make an upload handler activate a
class directly.

## Accept a revision through HTTP

Treat source submission as a deployment operation.

1. Authenticate the request before reading its body.
2. Apply a bounded upload limit.
3. Write files beneath a private staging directory chosen by the server.
4. Reject absolute paths, `..` segments, duplicate names, and undeclared files.
5. Verify the revision digest and signature.
6. Submit the staged `Revision`.
7. Return the revision identity and a `submitted` receipt.

Use `web.uploads` to stream multipart bodies. Do not use a client filename as a
filesystem path. Do not call uploaded Java untrusted. Activated code has the
application process's authority.

`HttpRevisionSource` implements this protocol. Wire its source-neutral output
through the host compiler before the coordinator receives it:

```java
var compiler = new RevisionCompiler(output, hostClasspath);
var source = new HttpRevisionSource(staging, limits, policy);
reload.source(source.map(compiler::compile));
```

The source does not load uploaded classes or activate a revision. The callback
must retain the staged tree until the compiled revision is accepted or
rejected.

## Validate a generation before activation

Register validators on the coordinator before a source submits work.

1. Compile every source into a new generation output.
2. Load the selected `Program` from that output.
3. Call `Program.define` once.
4. Check duplicate application, actor, and actor-effect bindings.
5. Run the configured smoke validators.
6. Replay affected durable actor logs without effects.
7. Activate only when every check succeeds.

A validator does not change the active generation. It does not run an external
effect. It records a `Problem` when it rejects the candidate.

## Reload web handlers and UI

Return the web root from `Generation.of(handler)`. Build the router, features,
pages, components, import map, and assets inside the same `define` call.

The host leases the active generation at the start of a handler call. It
releases the lease when the handler returns or throws. A streaming handler
keeps the call open until the stream ends. The next request after activation
uses the new handler.

Browser refresh is separate from code activation. Mount an application-owned
cable or event endpoint when a browser must learn that a generation changed.
Subscribe to reload events and publish only the `activated` event.

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

The snapshot names the active revision, candidate revision, last rejected
revision, and web-handler leases by generation. Problems keep
their source name, line, and message.

Events are observations. A slow event subscriber does not delay activation.

## Return to an earlier revision

Keep the earlier source artifact in deployment storage. Submit it again as a
new candidate. It goes through compilation, validation, actor replay, state
policy checks, and normal activation. The coordinator does not reactivate a
closed class loader and does not undo external effects.

## Test without files or sockets

Use a fake `RevisionSource`. Submit revisions explicitly. Use `web.serve.Memory`
to call the reload handler.

Assert these transitions:

1. The first valid revision becomes active.
2. A later valid revision changes the next request.
3. A request already running finishes on its original generation.
4. A broken revision leaves the active generation unchanged.
5. The old generation closes after its last lease ends.

Keep these checks in a fast suite. Put a real watcher, javac, process, socket,
or filesystem project in an integration suite.
