# How-to

Each section solves one task. The API facts are in [reference.md](reference.md).
The reasons behind the design are in [explanation.md](explanation.md). Follow
the [tutorial](tutorial.md) for a first application.

## Protect one route with a session

1. Create one `Sessions` policy with `Sessions.of(Signature)` or
   `Sessions.of(String)`.
2. Add `sessions.middleware()` around the application if handlers need the
   session on every request.
3. Add `sessions.required(loginLocation)` around the handler that needs a
   session.
4. Read the session with `Sessions.of(request)`.
5. Test `session.present()` before reading `session.text("userId")`.
6. For an HTML request, expect a `303` redirect to `loginLocation`.
7. For a non-HTML request, expect `401` with no login HTML.

`Sessions` carries signed JSON in the browser. It does not verify a password,
define a user, or provide a server-side store.

## Log in and write a session

1. Define a `Form` for the login fields.
2. Capture the request with `form.capture(request)`.
3. Reject an invalid submission with `Forms.reject(markup, response)`.
4. Call an application-owned credential check. Do not treat the session library
   as a password verifier.
5. Create a value with `Sessions.of(request).with("userId", id)`.
6. Call `sessions.write(response, session)`.
7. Redirect with `Responses.redirect(location, response)`, which uses `303`.

```java
var session = Sessions.of(request).with("userId", verifiedUserId); // app-owned id
sessions.write(response, session);
Responses.redirect("/notes", response);
```

## Log out

1. Call `sessions.clear(response)`.
2. Redirect to a public page with `Responses.redirect("/login", response)`.
3. Keep CSRF middleware around the logout route. Logout changes state.

## Add CSRF protection

1. Create `Csrf` with the same or a separate signing secret of at least 16
   bytes.
2. Wrap the application with `csrf.middleware()`.
3. Render `Csrf.token(request)` in a hidden `_csrf` input or a meta tag.
4. Send it in the `X-CSRF-Token` header for Turbo or `fetch`.
5. Send `_csrf` in the query or a normal URL-encoded form when a header is not
   available.
6. Send the header or query value for multipart uploads. CSRF does not buffer a
   multipart body to find a form field.
7. Treat a missing or wrong token as `403`.
8. Set `csrf.secured(true)` when the site uses HTTPS.

The middleware restores a normal URL-encoded body after it reads it. The body
is still one-read data, so do not read it twice in another middleware.

## Set production cookie attributes

1. Keep the signing secret in a secret manager or environment variable.
2. Call `sessions.secured(true)` behind TLS.
3. Call `csrf.secured(true)` behind TLS.
4. Keep `SameSite=Lax` unless the application has a tested cross-site flow that
   needs another value: `sessions.sameSite("Strict")` or
   `sessions.sameSite("None")` is explicit.
5. Remember that `SameSite=None` requires `Secure` in browsers.
6. Keep the session small. It is a signed, readable cookie, not encrypted data.

The default session cookie is `session`, lasts 14 days, is `HttpOnly`, uses
`SameSite=Lax`, and is not `Secure`. The default CSRF cookie is `csrf`, is not
`Secure`, and has a one-megabyte normal-form read limit.

## Handle a multipart upload

1. Add `enctype="multipart/form-data"` to the form.
2. Put the CSRF token in `X-CSRF-Token` or the query string.
3. Call `Uploads.into(request, privateDirectory)`.
4. Use `Uploads.into(request, privateDirectory, limits)` when the defaults do
   not fit.
5. Read ordinary fields from `Received.fields()`.
6. Read a file with `Received.file("attachment")` or all files with
   `Received.files("attachment")`.
7. Treat `Upload.suggested()` as a display label only.
8. Use `Upload.stored()` as the server-chosen path.
9. Catch `UploadException`, use `e.status()`, and write `400` or `413`.
10. Use one private staging directory per request when all-or-nothing cleanup
    matters. A failure in a later part can leave an earlier completed file.
11. Delete the request staging directory after moving the files that the
    application keeps.

The form markup can use the UI tags directly. The CSRF token is in the query in
this example; a Turbo or `fetch` client can send the same value in the
`X-CSRF-Token` header instead.

```java
var uploadForm = web.ui.Tags.form(
        web.ui.Attributes.method("post"),
        web.ui.Attributes.action("/notes?_csrf=" + web.sessions.Csrf.token(request)),
        web.ui.Attributes.enctype("multipart/form-data"),
        web.ui.Tags.input(web.ui.Attributes.type("file"),
                web.ui.Attributes.name("attachment")),
        web.ui.Tags.input(web.ui.Attributes.name("title")),
        web.ui.Tags.button(web.ui.Tags.text("Upload")));
```

```java
var limits = web.uploads.Limits.DEFAULT
        .part(8 * 1024 * 1024)
        .total(16 * 1024 * 1024);
Path staging = null;
try {
    var root = Path.of("var/private");
    Files.createDirectories(root);
    staging = Files.createTempDirectory(root, "request-");
    var received = web.uploads.Uploads.into(request, staging, limits);
    var title = received.fields().first("title", "");
    var file = received.file("attachment");
    if (file.isEmpty()) {
        Responses.text("attachment is required", Status.UNPROCESSABLE, response);
        return;
    }
    var destination = destinationFor(file.get()); // app-owned server path
    Files.move(file.get().stored(), destination);
    saveTitle(destination, title);                 // app-owned persistence
    Responses.redirect("/notes", response);
} catch (web.uploads.UploadException refused) {
    Responses.text(refused.getMessage(), refused.status(), response);
} finally {
    if (staging != null) deleteTree(staging); // application-owned cleanup
}
```

`Uploads.into` creates the directory and streams bytes to a random server name.
It never uses the client filename as a path. Store the directory outside a
public asset tree unless your application has an explicit download policy. A
request-scoped staging directory also lets the application remove files that a
later part or application step leaves behind.

## Read multipart parts yourself

1. Call `Multipart.of(request)` or `Multipart.of(request, limits)`.
2. Use `next()` until it returns `Optional.empty()`, or pass a `PartReader` to
   `each`.
3. Read `Part.body()` before asking for the next part.
4. Use `Part.text()` only for bounded ordinary fields.
5. Close the `Multipart` in try-with-resources.

```java
try (var multipart = web.uploads.Multipart.of(request, limits)) {
    multipart.each(part -> {
        if (part.file()) {
            part.body().transferTo(fileOutput(serverPathFor(part))); // app-owned path
        } else {
            var value = part.text();
            processField(part.name(), value);                    // app-owned
        }
    });
}
```

The current part body is valid only until the next part. Advancing drains
unread bytes. A non-multipart content type or missing boundary is a
`UploadException` with status `400`. `serverPathFor` must choose a path without
using `Part.filename()` or `Part.suggested()` as the destination.

## Run a durable background job from a handler

1. Define an actor type with an application-owned state record.
2. In `instantiate`, return `Application.of(initial).on(...)` updates.
3. Return `Step.of(nextState, Effect.of(...))` for external work. Use
   `ActorEffect` for actor routing, replies, schedules, spawns, and eviction.
4. Register the definition with `ActorSystem.define`. Use
   `Spawn.durable().redelivers(true)` when unfinished work must be tried again.
5. Root the system with `rooted(Path)` when logs must survive a restart.
6. Keep external effect handlers on `ActorSystem.effect`; do not put sockets or
   clocks in an update. Make every redelivered effect idempotent.
7. In the web handler, call `system.tell(address, message)`.
8. Return before the actor finishes. `tell` reports admission with
   `DeliveryStatus`; it does not wait for processing.
9. Expose status through `system.inspect(address)` or a route that polls it.
10. Use `system.ask(address, message, deadline)` only when a bounded reply is
    part of the request. An ask enters the mailbox and is logged.
11. Close the actor system during application shutdown.

```java
record JobState(String status, String result) {}

final class Jobs implements actors.Definition<JobState> {
    public String type() { return "note-job"; }

    public application.Application<JobState> instantiate(actors.Address self) {
        return application.Application.of(new JobState("queued", ""))
                .on("run", (state, message) -> application.Step.of(
                        new JobState("running", ""),
                        application.Effect.of("job.work")
                                .with("jobId", self.id())
                                .with("input", message.body().string("input", ""))))
                .on("finished", (state, message) -> application.Step.of(
                        new JobState("done", message.body().string("result", ""))));
    }

    public json.Json inspect(JobState state) {
        return json.Json.Object.of()
                .with("status", state.status())
                .with("result", state.result());
    }
}

var system = actors.ActorSystem.named("web").rooted(Path.of("var/actors"));
system.define(new Jobs(), actors.Spawn.durable().redelivers(true));
system.effect("job.work", (effect, emit) -> {
    var result = JobWorker.performOnce( // application-owned idempotent effect
            effect.string("jobId", ""), effect.string("input", ""));
    emit.emit(application.Message.of("finished").with("result", result));
});
var address = actors.Address.of("note-job", requestId); // app-owned id
var status = system.tell(address,
        application.Message.of("run").with("input", input));
Responses.empty(status == actors.DeliveryStatus.accepted ? 202 : 503, response);
```

The actor appends a command when it takes that command from the mailbox. It
advances state before it runs effects. With redelivery enabled, a restart
advances applied history quietly and runs the complete effect list again for
an unapplied tail command. `JobWorker.performOnce` must therefore deduplicate
the stable `jobId`.

`tell` returning `accepted` means immediate mailbox admission. It does not mean
that the command is already in the durable log. A process failure before the
actor appends it can still lose the command. If an HTTP response must certify
durable admission, use an application protocol with a bounded actor reply or a
separate durable ingress. A durable job is not complete because `tell` returned
`accepted`; inspect the state or poll a status route.

## Use Turbo and UI markup

1. Build safe markup with `Tags` and `Attributes`.
2. Use `Turbo.frame` for a frame and `Turbo.append`, `replace`, or `remove` for
   a stream response.
3. Use `Responses.turbo(markup, response)` for the Turbo stream content type.
4. Use `Ui.feature()` in `Features` when you want its UI assets and components.
5. Use `Stimulus.controller`, `action`, and `target` for the shipped Stimulus
   integration.

```java
var stream = web.ui.Turbo.replace("notice", web.ui.Tags.p(web.ui.Tags.text("Saved")));
web.Responses.turbo(stream, response);
```

## Add assets and an import map

1. Put application files in an asset directory.
2. Create `Assets.standard(List.of(assetDirectory))`.
3. Use `assets.url("application.css")` in markup.
4. Create `Importmap.standard().pin("application", "application.js")`.
5. Write `importmap.write(assets, response.writer())` in the document head.
6. Prefer `Features.of(ownRoutes, feature...)` when assets belong to features.

Asset URLs contain a content digest. Serve with `assets.serve(path,
ifNoneMatch)` through `Responses.send`; digested names are immutable and a
matching `ETag` returns `304`.

## Push an update with SSE and Turbo

1. Create one `Cable` and one `Topics` policy.
2. Add `cable.feature(topics)` to `Features`, or route `cable.stream(topics)`
   yourself.
3. Call `cable.broadcast(topic, Turbo.append(target, markup))` after a change.
4. Close the cable during application shutdown.
5. Keep private topic selection in your `Topics` function. `Topics.query` trusts
   the client and is for public topics only.

`Cable` uses `text/event-stream`, not WebSockets. It keeps a bounded queue and
backlog. A slow client is disconnected and can catch up on reconnect.

## Test a handler and a stream

1. Build a request with `Memory.get`, `Memory.post`, or `Memory.request`.
2. Call `Memory.handle(handler, request)` for a finite response.
3. Assert `Recorded.status`, headers, `text`, `pushes`, and `failure`.
4. Use `Memory.open` for a handler that keeps an event stream open.
5. Close the returned `Memory.Open` after the assertion.

```java
var result = web.serve.Memory.handle(app, web.serve.Memory.get("/notes"));
assert result.failure().isEmpty();
assert result.status() == 200;
```

## Exercise a whole application with Hyperspec

1. Write a spec string or resource using the hyperspec syntax.
2. Call `Hyperspec.run(spec, serviceUri)`.
3. Check the returned `Outcome`.
4. Use `Memory` for unit boundary tests and Hyperspec for client-visible
   links, forms, frames, and changes.

Hyperspec parses and follows affordances. It is not a server and it does not
replace tests for application-owned persistence or actor state.

## Next

Read [tutorial.md](tutorial.md) for the learning path, [explanation.md](explanation.md)
for concepts, and [reference.md](reference.md) for factual contracts.
