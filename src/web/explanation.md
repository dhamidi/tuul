# Explanation

This document explains the design of `web`. It is not a task guide. For a
first application, read [tutorial.md](tutorial.md). For procedures, read
[howto.md](howto.md). For API facts, read [reference.md](reference.md).

## The web package has a Go-shaped handler

The central contract is:

```java
void handle(Request request, Response response) throws Exception;
```

This has the useful shape of Go's `net/http`: the handler sees a request and
writes a response. It does not know which server owns the socket. The same
handler runs with `serve.Http`, `serve.Memory`, or another binding. Middleware
wraps that contract and therefore stays independent of the server.

`Request` is data. `Response` is a writer. That difference is deliberate. A
middleware can replace a method, path, body, headers, or attribute by making a
new request record. A response cannot be a finished record because a page,
asset, or event stream may still be writing.

## This is TEA plus hypermedia, not MVC

`Page` follows the Elm/TEA shape: a message reaches an update, the update
returns a new state and effects, and a render function writes the state.
`Requests.message` converts route, query, and form data into the message. The
state belongs to one request.

This is not MVC. There is no controller base class, model registry, view
resolver, or shared mutable page model. A handler is a function. A `Page` is a
small `application.Application` created per request. Application or actor
state that must outlive the request stays outside the page and is reached by an
effect. The split keeps request state easy to discard and long-lived state easy
to make durable.

The HTML side is Hotwire-compatible hypermedia. The server returns documents,
frames, or Turbo Streams. Turbo changes how the browser applies the response;
it does not change the handler contract. `Stimulus` adds small client actions
where HTML alone is not enough. The application still owns the routes and the
meaning of each action.

## Why markup writes to a Writer

`Markup` and `web.ui.Html` write to a `Writer`. They do not require a complete
`String`. This follows the same rule as the response body: output can start
while the rest of a page is still being built. `Responses.html` writes UTF-8
and closes the writer. `Tags.text` and attributes escape their values.

`Html.markup()` exists for small values and tests. It is not the normal page
boundary. `Tags.unsafe` is the explicit escape hatch for trusted markup.

Assets use the same rule with bytes. A non-compiled asset is copied from disk
to the response. An event stream stays open and calls `Response.flush()` so a
client receives events before the stream ends.

## Why request bodies are read once

An HTTP body is an `InputStream`. `Request.text()`, `bytes()`, `form()`, and
multipart readers consume it. They do not cache it for later callers. This
lets uploads stream to disk and lets a server refuse a large input while it is
still arriving.

Middleware that must inspect a small ordinary form body restores the bytes with
`request.body(inputStream)`. CSRF does this for URL-encoded forms. It does not
read a multipart body to find a field because buffering an upload defeats the
streaming boundary. Multipart CSRF must use a header or query value.

The same rule applies to `Requests.params(request)`: it reads a normal form.
Read raw bytes or multipart parts before asking for merged parameters.

## Why Response is mutable and streaming

Headers and status have one commit boundary. `Response.body()`, `writer()`, or
`close()` sends headers. After that, changing a status or header throws because
the change cannot reach the client. `Response.sent()` exposes the boundary.

The response stays mutable before that boundary because handlers need to set a
status, replace a header, or add repeated headers such as `Set-Cookie`. It stays
streaming after that boundary because large files and long event streams cannot
wait for a finished in-memory value. A missing `Content-Length` means the
binding can send the body as it arrives.

## Why redirects use 303 and validation uses 422

`Responses.redirect` uses `Status.SEE_OTHER` (`303`) by default. A browser
turns many `302` form redirects into `GET`, but Turbo follows the specification
and can repeat the original `POST`. A 303 states that the result is at another
resource and the next request is a GET.

`Forms.reject` uses `422 Unprocessable Content`. A `200` says that the form
request succeeded, so Turbo does not treat it as a failed submission to replace
with the returned errors. `422` carries the rejected submission and its
field-level problems back to the browser.

## Why routing is one table in two directions

`Router` recognises a method and path and expands a named route into a path.
The same template does both jobs. A route that matches the path but refuses the
method returns `Recognised.NotAllowed` and its allowed methods. `Routing` turns
that into a `405` with an `Allow` header. A missing path is `NotFound` and is a
`404`.

Routes are sorted by fixed text, then variable count, then definition order.
Mounting a router moves templates, not only incoming paths. That keeps the URL
written by a page aligned with the URL the server recognises.

## Why packages are layered

The root `web` package depends on interfaces and small values. `web.ui` depends
on `web.Markup`; `web.forms` depends on UI and request values; `web.assets` and
`web.cable` supply resources and features; `web.serve` binds handlers to a
server. The root does not import the UI package to render a response.

`Feature` is the composition boundary. A feature carries routes, handlers,
assets, import-map pins, markup contributions, middleware, and resources to
close. `Features` composes these in declaration order. It also mounts the
asset route and applies feature middleware. A feature is explicit; nothing is
discovered from a class path.

## Why authentication is session handling

`Signature` signs a payload with HMAC-SHA256. `Sessions` places a small JSON
object and signed expiry in a cookie. The cookie is readable by the client and
is not encrypted. The package does not know users, passwords, password hashes,
or a database. An application verifies credentials and chooses what identifier
to write into the session.

`Csrf` uses a signed cookie token and requires the page to send that token back
on unsafe requests. `Sessions.required` chooses a redirect for HTML and `401`
for other accepted media types. Keeping that negotiation in middleware avoids
HTML in a JSON response.

## Why durable work crosses into actors

A request has a bounded lifetime. A durable job has state and effects that must
survive that lifetime. `ActorSystem` is the boundary for that work. A web
handler tells an `Address` and returns. The actor appends the message, advances
its `Application` state, and runs effects after the state step.

An actor `Definition` must be a pure state/message fold. It registers updates in
`Application`; it does not hold a system or open a socket. `ActorEffect.tell`,
`reply`, `schedule`, `spawn`, and `evict` describe runtime work. External
handlers are registered once on `ActorSystem.effect` because they own resources
that must outlive actor eviction.

Replay advances applied history and suppresses its effects. This rebuilds state
from the command log without sending an old email or repeating an old HTTP
call. Everything learned from an external effect must return as a message so
replay has the same input. By default, replay also suppresses an unapplied tail.
`Spawn.redelivers(true)` runs the complete effect list again for that tail. Use
it only when every effect is idempotent.

The web request does not wait for the job to finish. It returns an admission
response such as `202`, then exposes `ActorSystem.inspect` or a polling route
for status. `tell` reports mailbox admission before the actor appends the
command, so it does not certify durable admission. An application that needs
that stronger promise waits only for its bounded admission protocol. It does
not wait for the job. An `ask` enters and is recorded in the actor mailbox.

## Why servers and tests are separate

`serve.Http` adapts `jdk.httpserver` to `Request` and `Response`. `serve.Memory`
uses the same interfaces and records the result. This is why handler tests do
not need a port, and why a server change does not force a handler rewrite.

`hyperspec` goes one level higher. It examines the links, forms, frames, and
Turbo changes that a client can see. It does not own application persistence or
actor policies.

## Next

Use [howto.md](howto.md) for concrete tasks and [reference.md](reference.md)
for defaults, status behavior, ordering, and ownership. Return to
[tutorial.md](tutorial.md) when you want to build the pieces in sequence.
