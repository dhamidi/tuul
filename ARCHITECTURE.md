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
   - `web.cable`: ActionCable-inspired live dispatch of events to connected clients,
   - `web.hyperspec`: a TCL-like DSL, interpreter, and harness runner for testing hypermedia applications, testing affordances, using them (following links, filling forms, following redirects, etc) and nothing else.  Asserts are against navigation state, requests made, resource attributes and affordances.
   - backed by jdk.httpserver

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


