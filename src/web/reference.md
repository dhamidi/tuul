# Reference

This index lists the public web APIs and their contracts. The
[tutorial](tutorial.md) is learning-oriented. The [how-to](howto.md) is task-
oriented. The [explanation](explanation.md) describes the design. Read the
focused [session reference](reference-sessions.md) and [upload reference](reference-uploads.md)
for their complete rules.

## Root package

### Handler and middleware

`Handler` is a functional interface:

```java
void handle(Request request, Response response) throws Exception;
```

`Handler.wrappedBy(Middleware)` applies a wrapper. `Middleware.wrap(Handler)`
returns a handler. `Middleware.then(inner)` composes an outer and inner
middleware. `Middleware.of(List<Middleware>)` reduces a list in declaration
order. With `handler.wrappedBy(authentication).wrappedBy(logging)`, logging is
outermost and runs first.

`Middlewares.methodOverride()` reads `_method` from a POST URL-encoded form and
can change it to `PUT`, `PATCH`, or `DELETE`. It restores the bytes it reads.
It does not override other methods and does not read multipart bodies.

### Request

`Request` is a record with `method`, `path`, `query`, `headers`, `body`,
`remote`, and `attributes`. Its constructor uppercases the method and copies
attributes. The body is one-read `InputStream` data.

- `Request.of(method, path)` creates an empty request.
- `Request.of(method, path, headers, body)` splits query from path.
- `method`, `path`, `remote`, `headers`, and `body` return changed requests.
- `with(name, value)` stores an attribute for downstream handlers.
- `attribute(name, Class<T>)` reads a typed attribute and returns empty for a
  missing or wrong type.
- `header(name)`, `type()`, and `length()` read request metadata.
- `text()` and `bytes()` consume the whole body. Use only for bounded input.
- `form()` parses only `application/x-www-form-urlencoded`; otherwise it
  returns `Parameters.NONE`.
- `Request.body(String)` creates a UTF-8 input stream for hand-built tests.

`Requests.params(request)` merges route variables, query parameters, and a
normal form in that order. The later form values win. It consumes the form
body. `Requests.message(request)` creates an `application.Message` whose type
is the matched route name, or `request` when no router ran. Use
`Requests.message(request, params)` when multipart parsing already supplied the
parameters.

### Response

`Response` is a mutable streaming interface:

```java
Response status(int status);
int status();
Response header(String name, String value);
Response add(String name, String value);
Headers headers();
boolean sent();
OutputStream body() throws IOException;
Writer writer() throws IOException;
void flush() throws IOException;
void close() throws IOException;
```

`header` replaces a header. `add` appends a value. Calling `body`, `writer`, or
`close` sends headers. Status and headers cannot change after `sent()` becomes
true. `writer` is UTF-8. `flush` pushes written data. `close` ends the response.
Callers own the response lifecycle unless a helper documents otherwise.

### Responses and status

- `Responses.html(markup[, status], response)` writes HTML as UTF-8.
- `Responses.text(text[, status], response)` writes plain text and a length.
- `Responses.json(json, response)` writes JSON with status `200`.
- `Responses.turbo(markup, response)` writes
  `text/vnd.turbo-stream.html` with status `200`.
- `Responses.redirect(location[, status], response)` sets `Location` and closes;
  the default is `303`.
- `Responses.empty(status, response)` writes no body and closes.
- `Responses.send(served, response)` copies an asset body and honours its
  status, headers, and bodiless status.
- `Responses.events(response)` starts an SSE response and returns an open UTF-8
  writer. It sets no-cache and proxy buffering headers, flushes once, and does
  not close the writer.

`Status` names common values from `200` through `500`. `Status.bodiless` is
true for `1xx`, `204`, and `304`. `Status.redirect` recognises `301`, `302`,
`303`, `307`, and `308`. Use the integer for a code not named by `Status`.

### Headers, parameters, cookies, and negotiation

`Headers` is immutable, keeps value order, and compares names without case.
`first`, `all`, `has`, `names`, `with`, `add`, and `without` are the public
operations. `Set-Cookie` needs `add` when several cookies are sent.

`Parameters` is immutable and preserves repeated values. `parse` reads
percent-encoded `name=value` pairs; a name without `=` has an empty value.
`first`, `all`, `has`, `names`, `isEmpty`, `with`, `and`, `json`, and `encoded`
are public. `and` gives later parameters precedence.

`Cookie.of(name, value)` defaults to `Path=/`, `HttpOnly`, `SameSite=Lax`, no
`Secure`, and no max age. `lasting`, `at`, `secured`, `sameSite`, and
`readableByScript` return changed cookies. `Cookie.header()` returns one
`Set-Cookie` value. `Cookies` reads request cookies and sets, adds, or clears
response cookies. Request values may have optional surrounding quotes; legacy
`$` parameters are ignored. A cookie name or value that contains a space,
control byte, non-ASCII byte, semicolon, comma, or double quote causes
`IllegalArgumentException`.

`Accept.parse` returns `Accept.ANYTHING` for a null, blank, or wholly empty
header. It parses wildcards and quality parameters. Quality is clamped to the
range 0 through 1; malformed quality text is treated as 1. A quality of 0 is a
refusal. The most specific matching media range decides a type's quality.
`best` returns the highest-quality server offer and keeps the first offer on a
tie. It returns empty when every offer is refused.

`Negotiate` defines `HTML`, `JSON`, and `TURBO_STREAM`. `best` and `accepts`
delegate to `Accept`. `wantsStream` requires a positive Turbo Stream quality
that is at least the HTML quality; a trailing browser wildcard alone does not
request a stream. `stream(request, response, updates, location)` writes the
`Markup` updates as a Turbo Stream when requested and otherwise writes a `303`
redirect. A route that needs a browser redirect can test
`Negotiate.accepts(request, Negotiate.HTML)` or use the session middleware's
`required` behavior.

### Routing

`RouteRef.of(name, template, parameters...)` defines a named route. Each URI
template variable has one `Parameter<?>` with the same name and in the same
order. Bind a value with `reference.with(parameter, value)`.

`Router.of()` creates an immutable handler. Add routes with `route`, `get`,
`post`, `put`, `patch`, or `delete`. The overload that accepts a handler defines
and binds the route in one call. `on(reference, handler)` binds a route that was
defined earlier.

`recognise(method, path)` returns:

- `Recognised.Match(route, variables, parameters)` for a method and path match;
- `Recognised.NotAllowed(method, path, allowed)` for a path with another
  method; and
- `Recognised.NotFound(method, path)` for no path match.

Methods compare uppercased. A GET route accepts HEAD. Query text is not part of
the path. Match order is more literal text, fewer variables, then definition
order. `path(reference)` expands every bound template variable and refuses a
missing value. `mount(prefix, router)` moves routes and handlers under a prefix,
keeps references, and refuses collisions.

Add a fallback with `otherwise(handler)`. `wrappedBy` adds middleware outside
dispatch. `Router.route(request)` reads the matched reference.
`Router.params(request)` reads raw path values. `parameter.get(request)` reads
one parsed value. An invalid typed path value does not match the route. An
unhandled existing route is `404`. A wrong method is `405` with `Allow`.
Middleware also sees requests that become `404`.

### Page

`Page.of(Supplier<S>)` creates fresh state per request. Register
`on(messageType, Update<S>)`, `effect(effectType, Effect.Handler)`, and
`render(Page.Render<S>)`. `Page.handle` builds an `Application`, dispatches
`Requests.message(request)`, then renders. A page effect runs during that
request and is not durable. Use an actor for durable state or work.

### Features and markup

`Feature` declares a named group of routes, handlers, assets, import-map pins,
head/body contributions, middleware, and closeable resources. `Features.of(own,
features)` rejects duplicate feature names, mounts feature routes, serves the
asset route, composes middleware, and returns `Features.router()`.

Feature order is meaningful: files are searched first-to-last, head/body
contributions and pins are written in that order, middleware is wrapped in that
order, and resources close in reverse order. Name a dependent feature after the
feature it uses. `Features.head()` and `body()` write contributions. `close()`
closes every declared resource and is idempotent.

`Markup.write(Writer)` is the root rendering contract. `web.ui.Html` implements
it. `Tags` creates escaped text, elements, fragments, documents, and common
HTML tags. `Attributes` creates escaped attributes, flags, data, ARIA, and
common URL/form attributes. `Tags.unsafe` writes untouched markup and is the
explicit unsafe boundary.

### UI and Turbo

`Turbo.frame(id, content)`, `frame(id, src)`, `streams`, and `stream` build
Turbo markup. Convenience methods include `append`, `prepend`, `replace`,
`update`, `before`, `after`, `remove`, and `refresh`. Attributes include
`targetFrame`, `action`, `advance`, `method`, `confirm`, `permanent`, and
`disabled`.

`Stimulus` builds `controller`, `action`, `target`, `value`, `classes`, `param`,
and `outlet` attributes. `Ui` supplies optional component helpers and
`Ui.feature()` supplies its assets. Components still write to a `Writer`.

## Subpackages

### Sessions and CSRF

See [reference-sessions.md](reference-sessions.md). In brief, `Signature` is
HMAC-SHA256 with a minimum 16-byte secret; `Sessions` writes readable signed
JSON cookies; `Csrf` checks a token from a signed cookie for unsafe methods.
Neither defines a user model or password verification.

### Forms

`Form.at(action)` and `Form.named(name, action)` create POST forms. Their
`Router, RouteRef` overloads resolve a named action, including its mount. `get`,
`post`, `method`, `with`, and `check` return changed definitions. `Field` has
constructors such as `text`, `textarea`, `password`, `email`, `url`, `search`,
`hidden`, `number`, `integer`, `id`, `decimal`, `date`, `checkbox`, and `choice`.
`Field.of(parameter)` uses any `Parameter<T>` for parsing. Field modifiers
include `label`, `hint`, `required`, `repeated`, `options`, `rule`, and `type`.

`Form.capture(request)` reads normal request parameters. `capture(parameters)`
does not throw for user input. It returns a `Submission` with typed values,
problems, ignored fields, and original submitted text. `blank` and `showing`
create render states. `Submission.message(type)` creates an application
message carrying values, `ok`, and problems.

`Submission.value(parameter)` returns the captured Java value. `Form.schema`
applies a compiled JSON Schema after parameter parsing and form checks pass.
Top-level property errors appear beside that field. Other schema errors appear
as form problems.

`Rules` supplies `least`, `most`, `matching`, `email`, numeric bounds,
`oneOf`, text predicates, and number predicates. `Forms.html` draws the whole
form. `Forms.field`, `control`, `problems`, and `reject` draw parts or a `422`
response. `Forms.reject` owns writing the markup and response status; it does
not persist anything.

### Uploads

See [reference-uploads.md](reference-uploads.md). `Uploads.into` streams
multipart files to a server-selected directory and returns `Received` fields
and `Upload` metadata. `Multipart` and `Part` expose the lower-level stream.
Limits are enforced while reading. Failures are `UploadException` with `400`
for malformed input and `413` for limits.

### Assets and import maps

`Assets.of(paths[, prefix])` scans load paths in order. `Assets.standard(paths)`
adds the bundled Turbo and Stimulus files. `find` resolves an `Asset`; `url`
returns a digest URL; `manifest` returns logical-to-digested names; `serve`
returns a `Served` result. A digest is based on sent content. Digested assets
are immutable; plain names are revalidated. `If-None-Match` can produce `304`.

`Importmap.standard()` pins Turbo and Stimulus. `pin(module, logical)` returns a
changed map. `json(assets)` resolves pins. `write(assets, writer)` writes the
import map and module preload links. The map must be written before modules
that import through it.

### Cable and SSE

`Cable.of([Settings])` creates an SSE cable. `stream(Topics)` returns a handler;
`broadcast(topic, Html)` sends a Turbo Stream event and `broadcast(topic,
Event)` sends an arbitrary event. `Topics.fixed` is public-topic policy;
`Topics.query` trusts the query and must not guard private topics. `feature`
adds the route, controller asset, import-map pin, and source element.

`Settings.standard()` is queue 64, backlog 64, heartbeat 20 seconds, and
refresh-on-gap. A slow subscriber is dropped when its queue is full. `close`
ends subscriptions and waits. The application owns the `Cable` and must close
it, directly or through a feature.

### Servers, tests, and Hyperspec

`Http.start(handler, port)` and its overloads bind `jdk.httpserver`. `port()`
and `address()` report the binding. `close()` stops it. The binding maps handler
exceptions to a server error and owns the exchange response.

`Memory.get`, `head`, `post`, and `request` build requests. `Memory.handle`
returns `Recorded` after the handler ends. `Recorded` exposes status, headers,
body, `text`, `pushes`, and optional failure. `Memory.open` returns `Open` for a
live handler and must be closed by the caller.

`Hyperspec.run(spec, URI)`, `run(Path, URI)`, and `run(Class, resource, URI)`
run a client-visible spec. `Syntax.parse` parses a spec. `Outcome` reports the
result. Hyperspec checks affordances; it does not replace unit tests.

## Actor integration boundary

Web code may hold an `actors.ActorSystem` and call `tell`, `ask`, `inspect`,
`history`, or `known`. The actor system is not a web dependency. A handler may
call `tell` and return an immediate mailbox-admission result for background
work.

An actor type implements `Definition<S>`. `instantiate(Address)` returns an
`Application<S>` with `on` updates. Updates return `Step<S>` and may request
`ActorEffect.tell`, `reply`, `schedule`, `spawn`, or `evict`. Register external
effect handlers with `ActorSystem.effect`. `Address.of(type, id)` names a local
actor. `Spawn.durable()` is the default; `Spawn.ephemeral()` keeps no log.

Commands are logged before their updates run. Applied history replays without
effects. The default also suppresses effects for an unapplied tail.
`Spawn.redelivers(true)` runs the complete effect list again for that tail and
requires idempotent effects. `ActorSystem.tell` returns `DeliveryStatus`; it
does not wait for a log append or processing. `ask` has a deadline and enters
the mailbox. `inspect` returns definition-provided JSON without adding a query
command to the log.

The actor owns actor state and the system owns logs, scheduling, and effect
handlers. The web handler owns the HTTP response. Neither side owns the other
side's lifecycle unless the application explicitly closes both.
