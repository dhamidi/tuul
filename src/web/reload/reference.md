# web.reload reference

This document specifies the HTTP adapters for `reload`.

## `ReloadHandler`

Construct `new ReloadHandler(reload)` and mount it as a `web.Handler`.
`ReloadHandler.attach(generation, handler)` returns a new generation with the
handler under `ReloadHandler.HANDLER`.

Each request acquires `Reload.lease()`. The adapter calls the attached handler
from that lease and closes the lease after the call. It answers `503` when the
lease is empty or the capability is absent. It propagates handler failures.

## `JdkReloadHandler`

Construct `new JdkReloadHandler(reload)` and mount it as a
`com.sun.net.httpserver.HttpHandler`. `JdkReloadHandler.attach(generation,
handler)` stores an external JDK handler in a generation. It answers `503`
before activation or when the capability is absent. Each exchange holds its
lease until the handler returns.

Use `new JdkGenerationFactory()` with `reload.RevisionCompiler`. The factory
loads providers from the candidate `ModuleLayer` with `ServiceLoader`. Zero or
more than one provider rejects the candidate. A provider that implements
`AutoCloseable` closes with its generation after all admitted exchanges drain.
The external module declares `requires jdk.httpserver` and
`provides com.sun.net.httpserver.HttpHandler with ...`; it does not require
Tuul or `web`.

## `HttpRevisionSource`

Construct `new HttpRevisionSource(staging, limits, policy)`.
The two-argument constructor uses `Limits.DEFAULT`.

`handler()` accepts one `POST multipart/form-data` request. The body contains
one text part named `manifest` and file parts named `file`.

The manifest contains:

| Field | Value |
|---|---|
| `rootModule` | Required name of the module that receives the generation factory. |
| `modules` | Required array of named source-module objects. |
| `dependencies` | Optional array of dependency entry names. |
| `identity` | Optional SHA-256 digest that must match the staged entries. |

Each module object has `name`, `root`, `descriptor`, `sources`, and an optional
`resources` array. Paths in these arrays are uploaded file names and must be
inside the module `root`. The descriptor must also occur in `sources`. This
shape lets one upload describe the complete application and entrypoint module
closure. The root module declares either `provides reload.Program` or
`provides com.sun.net.httpserver.HttpHandler`.

Each declared entry must have one file part. The source rejects undeclared or
duplicate entries, absolute paths, parent segments, malformed JSON, and digest
mismatches. It answers `400` for invalid input and `413` when `Limits` refuses
the upload. A policy refusal answers `403`. A non-POST request answers `405`.

The source answers `503` before [HttpRevisionSource#start] or after
`HttpRevisionSource#close`. A valid upload
answers JSON with the revision identity and `submitted` status. The source
owns a successful staging tree until `close`; the callback must finish using
the revision before it returns, or copy needed files elsewhere. It removes a
temporary staging tree when parsing or writing fails.

`RevisionSubmissionPolicy#allow` runs before the body is read. Return `false`
to refuse the request. `start` binds one callback and rejects a second bind.
