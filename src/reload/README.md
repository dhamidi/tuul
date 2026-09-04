# reload

Reload a running application from a validated, immutable generation.

A generation contains the named-module code and resources that change together.
A source adapter materializes a revision, a `RevisionCompiler` or development
builder compiles every source module in its closure, and `Reload` loads and
validates the selected generation factory. New work uses the active
generation. Work already admitted finishes at its lease boundary.

A revision source only submits revisions. A directory watcher and an artifact
receiver use the same submission contract. HTTP upload support belongs to
`web.reload`.

The host keeps Tuul and the reload contracts in the parent module layer. Each
compiled generation uses a fresh in-memory `ModuleLayer`. A Tuul-aware
generation uses the parent's `application`, `actors`, `json`, and `reload`
contracts. An external generation can use only its declared JDK and project
modules. Compilation writes no `.class` files to disk.

Reload preserves behavior according to the surface that owns the work:

- A leased unit of work keeps one generation while it runs.
- A named application changes generation between closed dispatch turns.
- A durable actor changes generation between turns and rebuilds state by replay.
- An effect keeps the handler and resources of the turn that requested it.
- An ephemeral actor or long-lived application uses its declared state policy.

The package keeps the last active generation when a candidate fails. It records
the compiler problems or validation failure against the rejected revision.
Activation does not replace the host listener or discard the last good
generation.

Production code submission is deployment. Authenticate and authorize the
submitter. Verify an immutable revision before activation. A module layer
separates code lifetimes. It does not restrict what application code can access.

## Documents

Each document answers one kind of question.

| You want | Read |
|---|---|
| To embed the generation coordinator | [tutorial.md](tutorial.md) |
| To build and reload one web application | [web.reload tutorial](../web/reload/tutorial.md) |
| To complete a reload task | [howto.md](howto.md) |
| An API rule, state transition, or failure result | [reference.md](reference.md) |
| To understand generations, draining, state, and earlier revisions | [guide.md](guide.md) |

Start with the tutorial when you add reload to a project. Use the reference
when you implement a revision source or a reloadable application surface.
