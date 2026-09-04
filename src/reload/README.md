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

## Load JDK services

Use `JdkServiceFactory` in the host when the candidate root module provides an
adapter-oriented JDK service. The factory loads only providers declared by the
candidate root module. It stores providers in the generation, and reload closes
any provider that implements `AutoCloseable` after leases drain.

Use `JdkToolCatalog` with the same `Reload` to list tool metadata or run one
named `ToolProvider`:

```java
var tools = new JdkToolCatalog(reload);
var names = tools.list();
var status = tools.run("my-tool", out, err, "--check");
```

The [reload tutorial](tutorial.md#run-a-generation-owned-jdk-tool) contains a
complete external `example.tools` module, provider class, source-root wiring,
and host call. Its candidate `module-info.java` has `provides`; the `module tuul`
descriptor has `uses`. `RevisionCompiler` reads the compiled module descriptor,
so `CandidateContext` does not scan source text for providers.

Use the same boundary for a javac `Plugin`:

```java
try (var lease = reload.lease().orElseThrow()) {
    var plugin = lease.generation().service(com.sun.source.util.Plugin.class)
            .orElseThrow();
    plugin.init(hostTask);
    var compiled = Boolean.TRUE.equals(hostTask.call());
}
```

The host creates `hostTask`, calls the plugin, and closes the lease after the
task. A provider, task listener, parser, engine, or other candidate product
must not escape that lease. Loading a processor or plugin does not affect the
compilation that creates its generation. The host passes it to a later task.

The catalog returns names and descriptions. It does not return tool provider
instances. The run call acquires one lease and closes it after the tool returns.
Duplicate tool names fail deterministically.

The direct support matrix is intentionally small:

| Service | Adapter | Reload rule |
|---|---|---|
| `ToolProvider` | `JdkToolCatalog` | Generation-owned providers and one lease per run |
| `FileSystemProvider` | `JdkServiceFactory` | Close each file system before its lease ends |
| `ScriptEngineFactory` | `JdkServiceFactory` | Keep each engine inside its lease |
| JAXP factories | `JdkServiceFactory` | Keep each parser or transformer task inside its lease |
| JMX connector providers | `JdkServiceFactory` | Close each connector or server before its lease ends |
| `Processor`, javac `Plugin` | `JdkServiceFactory` | Pass the provider to one host-owned compilation task |

Tuul rejects JVM-global or one-shot SPIs. Do not install selector,
asynchronous-channel, URL, content-handler, charset, time-zone, logger,
security, JDBC, print, sound, attach, RMI, preferences, or
`HttpServerProvider` implementations through this API. Their JDK consumers
keep static state or have no reload-safe removal. `HttpHandler` is different:
the stable server invokes that application callback directly.
