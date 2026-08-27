# Technical Architecture

The `tuul` application itself follows the directory structure outlined in the README.md

Since we are not allowed any non-JDK dependencies, 
the following capabilities are developed in-house
up to our modern JDK 24 standard:

- `json`: a streaming JSON parser/serializer, operating on JSON values only,
- `jsonrpc2`: using the `json` module and structured concurrency to implement JSON-RPC 2.0 over arbitrary transports,
- `peg`: parsing expression grammars over streams of objects, producing a parse tree by default,
- `argparse`: a CLI argument parser based on `peg`, parsing a stream of strings,
- `fetch`: a concurrent HTTP client with support for directly streaming files to disk
- `application`: a mini-framework for defining applications according to The Elm Architecture,
- `uritemplates`: an implementation of https://datatracker.ietf.org/doc/html/rfc6570 using `peg`
- `eventstream`: a text/event-stream parser and generator, outputting to a writer,
- `web`: a mini-framework for writing web servers while staying sane, supporting classical hypermedia-style HATEOAS applications (think Ruby on Rails-lite):
   - `web.ui`: a component abstraction rendering HTML to a stream,
   - `web.assets`: a library integrating with file systems for efficient serving of assets,
   - `web.dispatch`: a bidirectional router for named routes, based on URI-templates, used for constructing and recognizing urls,
   - `web.forms`: capturing an incoming request as a message, and rendering that message back to the user with what was wrong with it — the part of ActiveRecord that is about the round trip, not the part that is about a database,
   - `web.controllers`: the things every request needs and no handler should rewrite — authentication, file uploads, content negotiation,
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
symbol index — the one `tuul docs` already builds.

These are all plain Java libraries.

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


