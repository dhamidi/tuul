# Technical Architecture

The `tuul` application itself follows the directory structure outlined in the README.md

Since we are not allowed any non-JDK dependencies, 
the following capabilities are developed in-house
up to our modern JDK 27 standard:

- `json`: a streaming JSON parser/serializer, operating on JSON values only,
- `jsonrpc2`: using the `json` module and structured concurrency to implement JSON-RPC 2.0 over arbitrary transports,
- `peg`: parsing expression grammars over streams of objects, producing a parse tree by default,
- `argparse`: a CLI argument parser based on `peg`, parsing a stream of strings,
- [`fetch`](src/fetch/README.md): a concurrent HTTP client with sessions,
  persistent connections, and support for directly streaming files to disk
- `application`: a mini-framework for defining applications according to The Elm Architecture,
- `uritemplates`: an implementation of https://datatracker.ietf.org/doc/html/rfc6570 using `peg`
- `eventstream`: a text/event-stream parser and generator, outputting to a writer,
- `web`: a mini-framework for writing web servers while staying sane, supporting classical hypermedia-style HATEOAS applications (think Ruby on Rails-lite):
   - `web.ui`: a component abstraction rendering HTML to a stream,
   - `web.assets`: a library integrating with file systems for efficient serving of assets,
   - `web.dispatch`: a bidirectional router for named routes, based on URI-templates, used for constructing and recognizing urls,
   - `web.forms`: capturing an incoming request as a message, and rendering that message back to the user with what was wrong with it — the part of ActiveRecord that is about the round trip, not the part that is about a database,
   - A `web.Handler` or a `web.Page` answers a request,
   - `web.cable`: ActionCable-inspired live dispatch of events to connected clients,
   - `web.hyperspec`: a TCL-like DSL, interpreter, and harness runner for testing hypermedia applications, testing affordances, using them (following links, filling forms, following redirects, etc) and nothing else.  Asserts are against navigation state, requests made, resource attributes and affordances.
   - backed by jdk.httpserver

A web application is an `application`: the same update functions, the same
messages, the same effects. A request becomes a message, an update returns a
state and effects, and rendering is what the runtime does with the state. Web
applications therefore compose the way any other application does.

Reactivity is [Hotwired](https://hotwired.dev), first-class and out of the box:
Turbo Drive, Turbo Frames and Turbo Streams, with Stimulus for the behaviour
that is genuinely client-side. Turbo Streams arrive over `eventstream` rather
than WebSocket — `jdk.httpserver` has no WebSocket, and Turbo will connect to
an `EventSource` — which is what `web.cable` is.

`web.assets` follows what Rails 8 settled on and invents nothing: digest the
file name, serve it immutable, rewrite the references inside stylesheets, and
pin ES modules in an import map. No bundler, no transpiler, no build step that
has to be installed.

`web` is server-agnostic. It defines the operations the rest of it needs — a
request, a response being written, a handler, a handler wrapping a handler — as
interfaces, and binding a server to them is implementing those interfaces and
nothing else. `jdk.httpserver` is one implementation and an in-memory one used
by the tests is another; neither is special. This is Go's `net/http` shape, for
Go's reason: a handler that takes an interface can be tested without a socket,
deployed on another server without being rewritten, and wrapped by middleware
that knows nothing about either.

`jdk.httpserver` is HTTP/1.1 only, has no WebSocket, and routes by longest
prefix. That is a fair trade for a framework we control end to end, and it puts
two obligations on `web`: set the request and response timeouts, since a server
that does not is trivially held open by a slow client; and expect to be deployed
behind Caddy or nginx, which terminate TLS and speak HTTP/2 or HTTP/3 to the
browser. One consequence is not hidden by the proxy: an event stream holds a
connection per client, and browsers allow about six per origin over HTTP/1.1.

The application tuul dogfoods this with is a fast, interactive browser for a
symbol index — the one `tuul docs` already builds. Both ask `symbols.Queries`,
so the command line and the browser answer a name with one description. Both
read `build/index.db`, and nothing else, for what the index knows.

These are all plain Java libraries. They are packages in one Java module,
named `tuul`.

Structured concurrency here means the lifetime of a task is a block of code,
not the preview `StructuredTaskScope` API. Tasks are forked into a
virtual-thread executor inside a try-with-resources, whose `close()` does not
return until every one of them has finished — the same guarantee, without
pinning tuul's class files, and every project that vendors them, to one exact
JDK build. When `StructuredTaskScope` is final, the executor is a two-line
change.

Applications follow The Elm Architecture, with a slight twist:

- messages are expressed using a `json` object,
- update functions fail open,
- errors are reported as extra messages,
- the update function returns a new state and a list of effects to apply,
- the application runtime applies the effects and applies resulting messages to itself again,
- anything that accepts a message and state, and returns a new state and effects, can work as an update function,
- so that applications compose.

## Outbound HTTP

`fetch` is the outbound HTTP boundary. It is independent of `web`, so a
browser, a Maven resolver, and an actor effect can use the same client.

`Fetch` owns one connection pool and one execution strategy. The pool reuses
HTTP/1.1 keep-alive connections and multiplexes HTTP/2 streams when the peer
supports them. It is shared by every session created by that client. A
`Session` owns cookies and request defaults for one user agent. It does not own
a socket or an executor.

The caller selects concurrency when it constructs `Fetch`:

- `Fetch.sequential()` runs one synchronous exchange on the current thread.
- `Fetch.flow()` runs concurrent exchanges on one Flow-based event loop and
  applies back-pressure to response bodies.
- `Fetch.virtualThreads()` runs blocking exchange work on virtual threads.
- `Fetch.of(Execution)` borrows an execution resource that the application
  provides.

The convenience constructors own the resources they create. The injected
constructor does not close the supplied execution. No session or request
creates a hidden executor.

`Request` and `Response` are metadata plus a `Body`. A body is a stream. A
repeatable body can be sent again. A response body can be consumed once. The
caller can write it to an `OutputStream` or `Path`, read it through a
`Reader` selected by `Content-Type`, or consume it as a
`Flow.Publisher<ByteBuffer>`. `Body.file(Path)` streams a large upload from a
file. `Body.publisher(...)` streams a generated upload with back-pressure. The
client does not buffer an unbounded body. The caller closes every response
after it consumes or cancels the body, which lets the pool reuse or close the
connection safely.

The package keeps HTTP status as data. `send()` returns 404 and 500 responses.
`requireSuccess()` is the explicit adapter for a caller that wants an
exception for a non-2xx result. Redirect policy and cookie handling live on a
session because both are user-agent state. The response retains redirect hop
metadata without retaining intermediate bodies.

This design supports the next layers without moving transport concerns into
them:

- a browser keeps one session, parses body chunks, and adds history, cache,
  and document behavior;
- `tuul add` uses concurrent GET requests and writes Maven jars directly to
  temporary files before checksum verification and rename;
- `web.hyperspec` can use a session to follow links, submit forms, and inspect
  the same status and headers that a browser sees.

## Packages and Java modules

This file used "module" for a package (`json`, `peg`). A Java module in the
JPMS sense is only `tuul`.

Each directory under `src/` is a Java package. `json`, `actors` and `web.ui`
are packages. They are not Java modules.

The jar that `tuul install` vendors is one Java module, named `tuul`. A
project writes `requires tuul` or `import module tuul`. It does not require
each package as a module.

A Java module per package would add a `module-info.java` to every directory.
Tuul has no third-party modules to isolate. The packages already call each
other through `application` and `json`. That is one library, not a set of
optional products.

The CLI is an unnamed class in `src/cli/main.java`. The unnamed package
cannot belong to a named module. `tuul install` leaves that class out of
the library jar. The tool stays on the classpath. The library is
`module tuul`.

A project uses the same split. `src/web/main.java` is unnamed and stays on
the classpath. The vendored jar is `tuul`. If the application has no native
code of its own, native access is `--enable-native-access=tuul`.

A custom runtime from `jlink` needs a named module. `tuul deploy` builds
that runtime from `module tuul`. The source tree has one `module-info.java`.
The build compiles the library as that module. It compiles the CLI separately
and keeps the CLI on the classpath.

## Reload and module layers

UI code is Java that implements `web.ui.Component`. `web.Page` builds a new
application for each request. The next request can load new classes. A
request that is already running keeps the old classes.

The JDK type for that swap is a child class loader. When the application is
a named module, it is a `ModuleLayer`. The parent holds tuul: `application`,
`actors`, `web`, `sqlite3`, `json`. The child holds the application's views
and pages.

A change to those sources compiles into a new child. The server replaces
the handler that `Http.start` calls. Pages that are open refresh through
Turbo. This reload is not built yet. `tuul dev` in the README restarts the
process instead.

The child must not load `web.ui`. Views implement types from the parent.
A second copy of `Component` in the child makes a cast fail.

Actor state does not live in the child. `Definition.inspect` returns JSON
so a caller never holds the state class across a reload. To change an
actor, register a new `Definition` and evict it. That path does not use a
new loader.
