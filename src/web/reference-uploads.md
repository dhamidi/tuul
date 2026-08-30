# Reference: multipart uploads

This page is the factual reference for `web.uploads`. Use the
[tutorial](tutorial.md) to learn the flow, [howto.md](howto.md) for tasks,
[explanation.md](explanation.md) for streaming reasons, and [reference.md](reference.md)
for the web index.

## Uploads.into

`Uploads.into(request, directory)` uses `Limits.DEFAULT`. The overload with a
`Limits` value creates the directory, reads a `multipart/form-data` request,
streams each file to a server-selected path, and returns `Received`.

`Received.fields()` is `Parameters`. `Received.file(field)` returns one
optional `Upload`; `Received.files(field)` returns all uploads for that field.
`Upload` records `field`, `suggested`, `type`, `stored`, and `size`.

The stored filename is random. A safe extension may be retained, but the client
filename never decides the directory or path. `Part.suggested()` strips path
segments and unsafe characters for a display label. It is still not a path.
Use `Upload.stored()` for server-side reads.

`Uploads.into` deletes the file that it is writing when that write fails. Files
from earlier parts can remain if a later part fails before the method returns.
It also does not delete a completed file when application validation,
persistence, or later processing fails. Use one staging directory per request
when the application must clean up the complete attempt. The application owns
completed-file cleanup, retention, and moving files out of that directory.
Keep upload directories out of public asset roots unless downloads are
deliberate and authorised.

## Limits

`Limits.DEFAULT` is:

- 16 MiB per part;
- 32 MiB total request;
- 64 parts;
- 16 KiB headers per part;
- 64 KiB per ordinary field.

`part`, `total`, `parts`, and `field` return changed limits. Limits are checked
while the stream is read. A limit refusal is `UploadException` with status
`413`. A malformed multipart request is status `400`. Constructing `Limits`
with any non-positive value causes `IllegalArgumentException`.

## Multipart

`Multipart.is(request)` checks the folded content type. `Multipart.of(request)`
uses default limits. `Multipart.of(request, limits)` requires
`multipart/form-data` and a boundary parameter; otherwise it throws
`UploadException` with status `400`.

`next()` returns the next `Part`, or empty at the end. `each(PartReader)` visits
each part. `close()` closes the source stream. The current part body is valid
until the next call to `next`; advancing drains unread bytes. Read or copy the
body before advancing.

## Part

`Part` exposes:

- `name()` — the submitted field name;
- `filename()` — the optional client filename;
- `file()` — whether a filename was supplied;
- `type()` — the part content type;
- `headers()` — part headers;
- `body()` — the one-read part stream;
- `text()` — UTF-8 text bounded by `Limits.fieldBytes()`;
- `suggested()` — a safe display label, never a destination.

Do not retain a `Part.body()` stream after requesting the next part. The parser
keeps one current part and a bounded buffer, not the whole request.

## Failure handling

`UploadException` is a runtime exception with `status()`. Use that status in
the response:

```java
try {
    var received = Uploads.into(request, privateDirectory, limits);
    save(received); // application-owned persistence
} catch (UploadException refused) {
    Responses.text(refused.getMessage(), refused.status(), response);
}
```

`IOException` remains possible while reading or writing. Handle it according
to the application's server policy. Do not convert every error to `400`: a
client format refusal and a local disk failure are different failures.

## CSRF and forms

The request must pass CSRF middleware before the upload handler. Multipart CSRF
tokens must be in `X-CSRF-Token` or `_csrf` in the query. The CSRF middleware
does not parse a multipart field. A form must use `enctype="multipart/form-data"`.

`web.forms.Form` does not store file parts. Use `Uploads` for the multipart
stream, and use `Requests.message(request, received.fields())` when an
application page needs a message containing the ordinary fields.

## Ownership and lifecycle

The request body and multipart reader are owned by the active handler. Close a
`Multipart` reader when using it directly. `Uploads.into` closes its reader
before returning. The returned path is application-owned. Decide explicitly
when to move, publish, scan, retain, and delete it. A request ending does not
delete completed files.
