# Explanation

This document says why the package has these boundaries. It is not a
tutorial. It is not a command list. For tasks, read [howto.md](howto.md). For
rules, read [reference.md](reference.md).

## What this package is

`fetch` is the outbound HTTP boundary for tuul. It turns a request description
into a response stream. It does not become a browser, a Maven resolver, or a
document parser.

The package has four visible layers:

1. `Fetch` owns connections and execution resources.
2. `Session` owns cookies and defaults for one user agent.
3. `Request` owns method, URI, headers, and an outbound body.
4. `Response` owns status, headers, redirect metadata, and an inbound body.

Each layer has one lifetime. The transport lifetime can outlive a session.
The session lifetime can outlive one request. The response body can outlive
the future that delivered its headers.

## Why the connection pool belongs to `Fetch`

Persistent connections are transport state. Cookies and default headers are
user-agent state. These states have different owners.

If every session creates its own pool, a browser opens duplicate connections
for the same origin. If all sessions share one cookie jar, one user receives
another user's cookies. Keeping the pool on `Fetch` and the jar on `Session`
separates these lifetimes.

One client can therefore serve a browser with many tabs, or a resolver with
one session per repository. HTTP/1.1 connections stay available for reuse.
HTTP/2 streams share a connection when the peer supports them.

The response must be consumed or closed. Otherwise the client cannot know if
the connection is safe to reuse. This rule makes the cost of an abandoned
body visible at the call site.

`Fetch` is also the lifetime boundary for concurrent work. Closing it stops
new calls, cancels active calls, waits for owned execution work, and closes the
pool. A caller keeps a fetch client open around every future it starts. This
gives virtual-thread calls the same lexical lifetime as the client that owns
them.

## Why execution is a construction choice

An HTTP client starts work, waits for network input, runs callbacks, and
closes work. Each of those actions needs concurrency resources. A hidden
executor makes ownership unclear and makes a small command pay for a pool it
does not need.

`Fetch` accepts `Execution` at construction. The caller chooses the resource
model before it creates a session or a request:

- `currentThread` gives a simple sequential mode.
- `flow` gives one event loop and demand-aware body delivery.
- `virtualThreads` gives blocking code a cheap thread per active exchange.

Sessions never create executors. Requests never create executors. The three
convenience factories are explicit resource owners. `Fetch.of` borrows an
application-owned execution.

This boundary also lets the same HTTP API run in a command, a server, an
actor effect, or a Flow pipeline. The application selects the scheduling
model once.

## Why the body is one-shot

An HTTP body can be large or never end. A client that returns a byte array
before it returns headers cannot support downloads, event streams, or an
incremental parser.

`Body` gives three views of one stream:

- `publisher` composes with Flow and applies back-pressure.
- `writeTo` copies bytes directly to a caller-owned sink.
- `reader`, `text`, and `bytes` provide small-body convenience.

Only one view can consume a response. This prevents two consumers from racing
on the same socket. The caller can copy bytes when it needs two consumers.

`reader` closes the body when the caller closes the returned reader. A caller
that uses a publisher closes the response after the publisher completes or
fails.

The same type describes request sources and response streams. Text and files
are repeatable sources. A publisher is normally one-shot. That distinction
matters when a redirect or a caller retry needs to send a body again.

## Why `Content-Type` selects text encoding

The network carries bytes. A character set is a rule for turning those bytes
into characters. The `Content-Type` header carries that rule in its `charset`
parameter.

`Response.reader()` and `Response.text()` read that parameter. They use UTF-8
when the response does not declare a character set. `Body.reader(charset)` is
the explicit escape when a caller has a stronger rule, such as an HTML parser
that applies its own encoding sniffing.

`Request.text(value, charset)` writes the matching `Content-Type` header. A
bare `Body.text` only creates bytes because a body does not know which media
type the request will use. `Request.form` writes the form media type and the
UTF-8 charset.

`Content-Encoding` is not a character set. It describes compression or another
wire transformation. The transport must handle that transformation before a
text helper decodes characters. A text helper must never treat `gzip` or
`deflate` as a charset.

## Why event streams always use UTF-8

The event stream format fixes its character encoding to UTF-8. A response can
include a different `charset` parameter, but that parameter does not change the
format. `Response.eventStream` therefore reads UTF-8 directly instead of using
`Response.charset`.

An event stream can stay open without a final length. `Body.eventStream` reads
a Java stream only when the HTTP client requests bytes. A slow connection then
slows signal consumption. Closing or cancelling the request closes the signal
stream. No adapter collects all signals before the request starts.

## Why forms keep arrays

HTML forms can send the same field name more than once. A map from `String` to
`String` loses all values after the first one. `Form` stores a `String[]` for
each name, so the model keeps the wire meaning before it encodes anything.

`Body.form(Map<String, ?>)` is a short initializer. It accepts a `String` for
one value and a `String[]` for repeated values. `Form` is the type to use when
the caller needs to inspect or build the logical form before sending it.

The encoder writes one pair for each array element. It encodes UTF-8 bytes,
uses `+` for spaces, and percent-encodes a literal `+`. It never invents a
bracketed field name such as `tag[]`.

## Why large uploads use a body source

An upload can be larger than memory. The request must therefore consume bytes
as the connection asks for them.

`Body.file` opens a file stream at send time. It supplies the file size when
the file system provides it. It can open a new stream for a redirect or a
retry. The request code stays the same for a small or large file.

`Body.publisher` covers generated data and sources that do not have a file
path. Back-pressure moves from the network connection to the source. A
publisher stops when the request cancels it. A caller that needs a retry must
create a new publisher or use a repeatable source.

## Why status is data

404 and 500 are complete HTTP responses. The client received them correctly.
A browser must inspect a 404 page. A Maven resolver must report a missing
artifact with its repository status. Throwing from `send` would make normal
HTTP results look like broken transport.

`send` returns every complete response. `requireSuccess` is an explicit
adapter for code that wants the other rule. The exception still carries the
status and headers.

## Why redirects live on a session

Redirects use cookies, authorization, request bodies, and origin rules. Those
are user-agent decisions. They must run in the same state machine as the
session cookie jar.

The response keeps earlier hop metadata but not earlier bodies. The client
must consume or close an intermediate body before it follows the next
location. A browser can show where navigation went without keeping all
redirect bytes in memory.

No redirect policy silently resends a one-shot body. A caller can use a file,
text, or byte body when a repeatable request is required. The client does not
retry automatically for the same reason.

## Why the package stops at HTTP

The browser and Maven resolver need different policies above the same client.
A browser adds a DOM, cache, history, form rules, and content parsers. Maven
adds repository metadata, checksums, coordinate resolution, and a vendor
directory. They should share sessions, redirects, headers, and streaming
bodies without sharing their unrelated state.

The `tuul add` command can use one virtual-thread fetch client to resolve
metadata and download artifacts concurrently. It can write each jar directly
to a temporary file and then verify and rename it. A browser can use one Flow
client to feed response chunks to an HTML parser. Neither layer needs a second
HTTP abstraction.

## Why no cache is built in

A cache changes request semantics. It needs freshness, validation, storage,
privacy, and invalidation rules. A browser and a dependency resolver need
different answers to each question.

The transport can expose headers and status without choosing a cache policy.
The caller can place a cache above `Session` and still keep the connection
pool below it.

## Why no automatic retry is built in

A retry is safe for some GET requests and unsafe for some POST requests. A
retry can also duplicate a request after the server accepted it but the client
lost the response.

The package reports the transport failure and marks whether a retry may be
reasonable. The caller chooses the retry count and supplies a repeatable body
when it needs one. That keeps a data-changing action from running twice by
accident.
