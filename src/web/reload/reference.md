# web.reload reference

This document specifies the HTTP adapters for `reload`.

## `ReloadHandler`

Construct `new ReloadHandler(reload)` and mount it as a `web.Handler`.
`ReloadHandler.attach(generation, handler)` returns a new generation with the
handler under `ReloadHandler.HANDLER`.

Each request acquires `Reload.lease()`. The adapter calls the attached handler
from that lease and closes the lease after the call. It answers `503` when the
lease is empty or the capability is absent. It propagates handler failures.

## `HttpRevisionSource`

Construct `new HttpRevisionSource(staging, limits, policy)`.
The two-argument constructor uses `Limits.DEFAULT`.

`handler()` accepts one `POST multipart/form-data` request. The body contains
one text part named `manifest` and file parts named `file`.

The manifest contains:

| Field | Value |
|---|---|
| `entrypoint` | Required non-blank entrypoint name. |
| `sources` | Required array of source entry names. |
| `resources` | Required array of resource entry names. |
| `dependencies` | Optional array of dependency entry names. |
| `identity` | Optional SHA-256 digest that must match the staged entries. |

Each declared entry must have one file part. The source rejects undeclared or
duplicate entries, absolute paths, parent segments, malformed JSON, and digest
mismatches. It answers `400` for invalid input and `413` when `Limits` refuses
the upload. A policy refusal answers `403`. A non-POST request answers `405`.

The source answers `503` before [HttpRevisionSource#start] or after
`HttpRevisionSource#close`. A valid upload
answers JSON with the revision identity and `submitted` status. The source
removes a temporary staging tree when parsing or writing fails.

`RevisionSubmissionPolicy#allow` runs before the body is read. Return `false`
to refuse the request. `start` binds one callback and rejects a second bind.
