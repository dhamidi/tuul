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
- `application`: a mini-framework for defining applications according to The Elm Architecture.

These are all plain Java libraries.

Applications follow The Elm Architecture, with a slight twist:

- messages are expressed using a `json` object,
- update functions fail open,
- errors are reported as extra messages,
- the update function returns a new state and a list of effects to apply,
- the application runtime applies the effects and applies resulting messages to itself again,
- anything that accepts a message and state, and returns a new state and effects, can work as an update function,
- so that applications compose.


