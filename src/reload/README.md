# reload

Reload a running application from a validated, immutable generation.

A generation contains the application code and resources that change together.
A source adapter materializes a revision, a `RevisionCompiler` or development
builder compiles it, and `Reload` loads and validates its `Program`. New work
uses the active generation. Work already admitted finishes at its web request,
application turn, or actor turn boundary.

A revision source only submits revisions. A directory watcher, an HTTP upload,
and an artifact receiver use the same submission contract. None of the
`application`, `actors`, or `web` packages knows which source submitted a
revision.

The host process keeps Tuul and the reload contracts in a parent class loader.
Each application generation uses a child class loader or module layer. A
generation must use the parent's `application`, `actors`, `web`, `json`, and
`reload` classes. It must not carry another copy of those packages.

Reload preserves behavior according to the surface that owns the work:

- A web request keeps one generation while its handler runs.
- A named application changes generation between closed dispatch turns.
- A durable actor changes generation between turns and rebuilds state by replay.
- An effect keeps the handler and resources of the turn that requested it.
- An ephemeral actor or long-lived application uses its declared state policy.

The package keeps the last active generation when a candidate fails. It records
the compiler problems or validation failure against the rejected revision.
Activation does not hide a failure by restarting the process.

Production code submission is deployment. Authenticate and authorize the
submitter. Verify an immutable revision before activation. A class loader
separates lifetimes. It does not restrict what application code can access.

## Documents

Each document answers one kind of question.

| You want | Read |
|---|---|
| To build and reload one web application | [tutorial.md](tutorial.md) |
| To complete a reload task | [howto.md](howto.md) |
| An API rule, state transition, or failure result | [reference.md](reference.md) |
| To understand generations, draining, state, and earlier revisions | [guide.md](guide.md) |

Start with the tutorial when you add reload to a project. Use the reference
when you implement a revision source or a reloadable application surface.
