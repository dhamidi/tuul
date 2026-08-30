# Tutorial: a small notes application

This tutorial builds a small hypermedia application. It serves notes, accepts
a form, starts durable background work, and runs the same handlers in memory
tests and in an HTTP server.

The tutorial also teaches Tuul's execution model. You first build an
`application.Application` from messages, updates, steps, and effects. You then
use that model in two different runtimes:

- `Page` creates one application for one HTTP request;
- `ActorSystem` gives an application an address, a mailbox, and a durable log.

Learn these parts in order. `Page` and actors are small once the application
loop is clear.

You need JDK 27. The types named `Note`, `Notes`, `NotesStore`, `Summaries`,
`Login`, and `Views` below are application-owned placeholders. Replace them
with your own types. All other types come from Tuul.

For tasks, read [howto.md](howto.md). For the design, read
[explanation.md](explanation.md). For exact API facts, read
[reference.md](reference.md), [reference-sessions.md](reference-sessions.md),
and [reference-uploads.md](reference-uploads.md).

## 1. Write a handler

A handler takes a `Request` and writes a `Response`. Start with a page that
does not need a database.

```java
import web.Handler;
import web.Responses;

Handler home = (request, response) -> Responses.text("notes", response);
```

The body is a stream. `Responses.text` sets the content type and length, writes
UTF-8 bytes, and closes the response.

## 2. Name the URLs

Use one `Router` for dispatching requests and building links. A `RouteRef`
names a route, contains its template, and declares the type of each path value.

```java
import web.RouteRef;
import web.Router;
import web.StringParameter;

var id = new StringParameter("id");
var notesRoute = RouteRef.of("notes", "/notes");
var noteRoute = RouteRef.of("note", "/notes/{id}", id);
var createNote = RouteRef.of("create-note", "/notes");
var summarizeNote = RouteRef.of("summarize-note", "/notes/{id}/summary", id);
var summaryJob = RouteRef.of("summary-job", "/summary-jobs/{id}", id);
var login = RouteRef.of("login", "/login");
var logout = RouteRef.of("logout", "/logout");
var routes = Router.of()
        .get(notesRoute)
        .get(noteRoute)
        .post(createNote)
        .post(summarizeNote)
        .get(summaryJob)
        .post(login)
        .post(logout);

var noteUrl = routes.path(noteRoute.with(id, "42"));
// /notes/42
```

`Router` chooses the most specific matching template. A path that exists with
the wrong method is a `405`; a path that does not exist is a `404`.

## 3. Connect handlers to routes

Bind handlers on the same router. A typed parameter reads its value from the
matched request. `Notes` and `Views` are application-owned placeholders.

```java
import web.Responses;
import web.Status;

final class Notes {
    private final web.Router routes;
    private final web.StringParameter id;

    Notes(web.Router routes, web.StringParameter id) {
        this.routes = routes;
        this.id = id;
    }

    void index(web.Request request, web.Response response) throws Exception {
        var html = Views.notesPage(routes.path(notesRoute)); // application-owned
        Responses.html(html, response);
    }

    void show(web.Request request, web.Response response) throws Exception {
        Responses.html(Views.notePage(id.get(request)), response); // application-owned
    }

    void create(web.Request request, web.Response response) throws Exception {
        // Application-owned persistence and validation go here.
        Responses.redirect(routes.path(notesRoute), response);
    }

    void save(String title, String body) throws Exception {
        NotesStore.save(title, body); // application-owned persistence
    }
}

var notes = new Notes(routes, id);
var app = routes
        .on(notesRoute, notes::index)
        .on(noteRoute, notes::show)
        .on(createNote, notes::create)
        .on(login, Login::handle)       // application-owned
        .on(logout, Login::logout)      // application-owned
        .otherwise((request, response) -> Responses.empty(Status.NOT_FOUND, response));
```

`id.get(request)` returns the parsed path value. `Router.route(request)` returns
the matched `RouteRef`. A handler does not parse the path itself.

## 4. Learn the application loop

Tuul uses the architecture commonly called TEA, after The Elm Architecture.
The name is less important than the four values in the loop:

- **State** is what the application knows now.
- A **Message** is something that happened.
- An **Update** maps the current state and one message to a `Step`.
- A **Step** contains the next state and zero or more `Effect` values.

An effect is data that describes work. It is not a lambda that does the work.
An effect handler owns the file, database, clock, or network call. When the
handler learns something, it emits another message.

```text
Message -> Update(state, message) -> Step(next state, effects)
   ^                                      |
   |                                      v
   +----------- emitted Message <- effect handler
```

This separation keeps an update deterministic. You can give it a state and a
message in a test and compare the returned `Step` without opening a resource.

Build the loop directly before you use the web convenience type. This example
loads one title from application-owned storage:

```java
import application.Application;
import application.Effect;
import application.Message;
import application.Step;

record NoteState(String latestTitle, boolean loading, String error) {}

var notesApplication = Application.of(new NoteState("", false, ""))
        .on("notes", (state, message) -> Step.of(
                new NoteState(state.latestTitle(), true, ""),
                Effect.of("notes.read")))
        .on("notes.loaded", (state, message) -> Step.of(
                new NoteState(message.body().string("title", ""), false, "")))
        .on("error", (state, message) -> Step.of(
                new NoteState(state.latestTitle(), false,
                        message.body().string("reason", "error"))))
        .effect("notes.read", (effect, emit) -> emit.emit(
                Message.of("notes.loaded")
                        .with("title", NotesStore.latestTitle()))); // app-owned I/O

var state = notesApplication.dispatch(Message.of("notes"));
```

Follow this dispatch one turn at a time:

1. The `notes` message reaches its registered update.
2. The update marks the state as loading and returns a `notes.read` effect.
3. The registered effect handler reads storage.
4. The handler emits `notes.loaded` with what it learned.
5. That message reaches the next update and produces the final state.
6. `dispatch` returns when no message or effect remains.

`Message.with` and `Effect.with` add JSON payload fields. The message type and
payload are separate. An update selects on the type and reads the payload from
`message.body()`.

`Application.dispatch` combines two lower-level operations. `advance(message)`
commits the returned state but does not run its effects. `perform(effects)`
runs effect handlers and returns the messages that they emit. A normal
application uses both until it settles. An actor logs a message, advances it,
and then performs its effects. During replay, it advances recorded messages
without performing their old effects.

A plain `Application` has one state and one dispatching thread. Do not share
one mutable instance between concurrent HTTP requests. Use `Page` for
request-local state. Use an actor when state must outlive a request and needs a
mailbox or a log.

## 5. Render a page with the same loop

`Page` is an HTTP adapter for the application loop. It gives each request fresh
state, turns the request into an `application.Message`, dispatches that
message, and renders the settled state. `NoteState` and `Views.notePage` are
application-owned placeholders.

```java
var notesPage = Page.of(() -> new NoteState("", false, ""))
        .on("notes", (state, message) -> application.Step.of(
                new NoteState(state.latestTitle(), true, ""),
                application.Effect.of("notes.read")))
        .effect("notes.read", (effect, emit) -> emit.emit(
                application.Message.of("notes.loaded")
                        .with("title", NotesStore.latestTitle())))
        .on("notes.loaded", (state, message) -> application.Step.of(
                new NoteState(message.body().string("title", ""), false, "")))
        .on("error", (state, message) ->
                application.Step.of(new NoteState(state.latestTitle(), false,
                        message.body().string("reason", "error"))))
        .render((state, request, response) ->
                Responses.html(Views.notePage(state), response)); // app-owned
```

Use the page as the route handler:

```java
var app = routes
        .on(notesRoute, notesPage)
        .on(noteRoute, notes::show)
        .on(createNote, notes::create)
        .on(login, Login::handle)
        .on(logout, Login::logout);
```

`Requests.message(request)` uses the matched route name as the message type.
For `GET /notes`, the type is therefore `notes`. Route variables, query values,
and normal form values are merged into the message payload's `params` object.

`Page.handle` creates the `Application`, registers these updates and effect
handlers, dispatches one request message, and renders once. `Step.state()` is
request-local. The update does not read storage. An update or effect-handler
failure becomes an `error` message instead of escaping from the loop.

## 6. Define and show a form

Define fields once. The definition draws the form and validates a submission.

```java
import web.forms.Field;
import web.forms.Form;
import web.forms.Forms;
import web.forms.Rules;

var noteForm = Form.at(routes, createNote)
        .post()
        .with(Field.text("title").label("Title").required()
                .rule(Rules.most(120)))
        .with(Field.textarea("body").label("Note").required());

var empty = noteForm.blank();
var formMarkup = Forms.html(empty, web.ui.Tags.button(web.ui.Tags.text("Save")));
```

On `POST`, capture the request. `Submission` keeps the text the person typed,
the coerced values, and the problems.

```java
var submission = noteForm.capture(request);
if (!submission.ok()) {
    Forms.reject(Views.noteFormPage(submission), response); // app-owned view
    return;
}
notes.save(submission.text("title", ""), submission.text("body", ""));
Responses.redirect(routes.path(notesRoute), response); // 303
```

`Forms.reject` writes the submission with status `422`. Turbo uses that status
to replace the form with its errors. A successful form redirect uses `303 See
Other`, which makes the next request a `GET`.

## 7. Give an application an actor runtime

`Page` discards its application after it writes the response. That is correct
for page state. A background summary job is different: it must keep its state
after the POST request ends, process one message at a time, and recover its
state after a restart.

An actor is not a second application model. It is the same `Application` with
three runtime services:

- an `Address` names one instance;
- a mailbox serializes messages for that address;
- a command log lets the system rebuild state.

A `Definition<S>` tells the actor system how to build a fresh application for
one actor address. It registers updates, but it does not open resources or
register external effect handlers.

```java
import actors.Address;
import actors.Definition;
import application.Application;
import application.Effect;
import application.Message;
import application.Step;
import json.Json;

record SummaryState(String status, String summary, String error) {}

final class SummaryJobs implements Definition<SummaryState> {
    public String type() {
        return "note-summary";
    }

    public Application<SummaryState> instantiate(Address self) {
        return Application.of(new SummaryState("queued", "", ""))
                .on("run", (state, message) -> Step.of(
                        new SummaryState("running", "", ""),
                        Effect.of("summary.generate")
                                .with("jobId", self.id())
                                .with("noteId", message.body().string("noteId", ""))))
                .on("summary.generated", (state, message) -> Step.of(
                        new SummaryState("done",
                                message.body().string("summary", ""), "")))
                .on("error", (state, message) -> Step.of(
                        new SummaryState("failed", "",
                                message.body().string("reason", "error"))));
    }

    public Json inspect(SummaryState state) {
        return Json.Object.of()
                .with("status", state.status())
                .with("summary", state.summary())
                .with("error", state.error());
    }
}
```

The definition is a pure fold of state and recorded messages. It does not read
a clock, create a random value, call a database, or call an HTTP service. An
actor stamps each delivered message with a recorded time; use `message.at()`
when an update needs that time.

`self` identifies this actor instance. It is safe to put `self.id()` in an
effect as a stable idempotency key. `inspect` chooses the JSON that operators
and HTTP status handlers can read. The actor's Java state type does not leave
the actor system.

## 8. Make the actor durable and connect its effect

Create one actor system for the process. `rooted` stores durable logs below a
directory. Register the definition and the external effect handler at startup:

```java
import actors.ActorSystem;
import actors.Spawn;
import java.nio.file.Path;

var summaryJobs = new SummaryJobs();
var actorSystem = ActorSystem.named("notes")
        .rooted(Path.of("var/actors"))
        .define(summaryJobs, Spawn.durable().redelivers(true))
        .effect("summary.generate", (effect, emit) -> {
            var summary = Summaries.generateOnce( // app-owned, idempotent I/O
                    effect.string("jobId", ""),
                    effect.string("noteId", ""));
            emit.emit(application.Message.of("summary.generated")
                    .with("summary", summary));
        });
```

The effect handler belongs to the system because it owns long-lived resources.
It emits `summary.generated`, which enters the actor mailbox as another
message. The log therefore records both the command and what the outside world
returned.

Durable state and effect delivery are separate choices:

1. The actor appends a command before it advances the application.
2. It records the next state by recording the command, not by serializing the
   state.
3. It runs the returned effects after the advance.
4. On summon or restart, it builds a fresh application and advances recorded
   messages to rebuild the state.
5. It suppresses effects for applied history, so replay does not repeat old
   work.

`Spawn.durable()` uses at-most-once effects by default. A crash after the
command append but before an effect finishes can lose that effect.
`redelivers(true)` instead repeats the complete effect list for an unapplied
tail. This gives at-least-once effects, so the effect can happen twice.
`Summaries.generateOnce` must deduplicate the stable `jobId`. Do not enable
redelivery for a non-idempotent payment, email, or other irreversible action.

## 9. Hand work from HTTP to the actor

Create a new address for each job. `Definition.at(id)` makes an address with
the definition's type, so callers do not repeat the `note-summary` string.

```java
import actors.DeliveryStatus;
import java.util.UUID;
import web.Handler;

Handler startSummary = (request, response) -> {
    var noteId = id.get(request);
    var jobId = UUID.randomUUID().toString();
    var job = summaryJobs.at(jobId);
    var delivery = actorSystem.tell(job,
            application.Message.of("run").with("noteId", noteId));
    if (delivery != DeliveryStatus.accepted) {
        web.Responses.empty(503, response);
        return;
    }
    web.Responses.redirect(
            routes.path(summaryJob.with(id, jobId)), response);
};

Handler summaryStatus = (request, response) -> {
    var jobId = id.get(request);
    web.Responses.json(actorSystem.inspect(summaryJobs.at(jobId)), response);
};
```

The handler does not wait for the summary. It returns a `303` status URL after
the runtime accepts the message. `tell` returning `accepted` means immediate
mailbox admission. It does not mean that the command is processed, appended to
the log, or complete. A process failure can occur before the append. Use a
bounded application protocol with a reply or a separate durable ingress when
the HTTP response must certify durable admission.

`inspect(address)` returns the JSON from `Definition.inspect`. It does not add
a query command to the actor log. Use `ask` only when the actor protocol needs
a bounded reply; an ask is a message and is logged.

This is the complete relationship:

```text
HTTP request -> tell(address, Message) -> mailbox -> command log
                                                    |
                                                    v
                                  Application.advance -> Step -> effects
                                          ^                       |
                                          +---- emitted Message <-+

Replay: command log -> Application.advance, with old effects suppressed
```

## 10. Add authentication and CSRF checks

This is session handling, not a user model. `Sessions` signs a small JSON
object in a cookie. It does not verify passwords and it does not provide a
session store. `Login.verifyCredentials` below is an application-owned
placeholder for your password or identity provider check.

Create one policy and wrap the complete routing handler. Keep the secret out
of source control.

```java
import java.time.Duration;
import web.sessions.Csrf;
import web.sessions.Sessions;

var sessions = Sessions.of(System.getenv("APP_SESSION_SECRET"))
        .lasting(Duration.ofDays(14))
        .sameSite("Lax")
        .secured(true);                 // production HTTPS
var csrf = Csrf.of(System.getenv("APP_SESSION_SECRET"))
        .secured(true);
```

Use `secured(false)` for plain local HTTP. Secure cookies do not travel over a
plain HTTP connection.

After CSRF middleware runs, a page can render its token:

```java
var token = Csrf.token(request);
var form = web.ui.Tags.form(
        web.ui.Attributes.method("post"),
        web.ui.Attributes.action(routes.path(createNote)),
        web.ui.Attributes.enctype("application/x-www-form-urlencoded"),
        web.ui.Tags.input(web.ui.Attributes.type("hidden"),
                web.ui.Attributes.name(Csrf.FIELD), web.ui.Attributes.value(token)),
        web.ui.Tags.input(web.ui.Attributes.name("title")));
```

A normal form may also submit `_csrf` as a field. Turbo and `fetch` should send
the same token as the `X-CSRF-Token` header. Safe methods (`GET`, `HEAD`,
`OPTIONS`, and `TRACE`) do not need a token. A missing or wrong token is `403`.

Guard only the routes that need a signed-in session:

```java
var signedInOnly = sessions.required(routes.path(login));
web.Handler guardedCreate = notes::create;
web.Handler guardedSummary = startSummary.wrappedBy(signedInOnly);
web.Handler guardedStatus = summaryStatus.wrappedBy(signedInOnly);
var securedRoutes = routes
        .on(notesRoute, notesPage)
        .on(noteRoute, notes::show)
        .on(createNote, guardedCreate.wrappedBy(signedInOnly))
        .on(summarizeNote, guardedSummary)
        .on(summaryJob, guardedStatus)
        .on(login, Login::handle)
        .on(logout, Login::logout);

var protectedApp = securedRoutes
        .wrappedBy(csrf.middleware())
        .wrappedBy(sessions.middleware());
```

The last `wrappedBy` call adds the outer wrapper. Session middleware therefore
runs before CSRF middleware. It adds a `Session` attribute to every request.

The middleware redirects an HTML request to the login location with `303`. It
returns a non-HTML request as `401 Unauthorized`, so a JSON client does not
receive an HTML login page. A login handler writes the session after an
application-owned credential check. These methods belong in an application
type that owns the `sessions` policy:

```java
static void handle(web.Request request, web.Response response) throws Exception {
    var form = LOGIN_FORM.capture(request); // application-owned Form
    if (!form.ok() || !Login.verifyCredentials(form)) { // application-owned
        web.forms.Forms.reject(Login.view(form), response);
        return;
    }
    var session = Sessions.of(request).with("userId", Login.userId(form));
    sessions.write(response, session);
    web.Responses.redirect("/notes", response);
}

static void logout(web.Request request, web.Response response) throws Exception {
    sessions.clear(response);
    web.Responses.redirect("/login", response);
}
```

The middleware reads and verifies the cookie on every request. A missing,
expired, malformed, or wrongly signed cookie is `Session.NONE`. The cookie is
signed, not encrypted. Do not put passwords or other secrets in it.

## 11. Run and test the application

The server is a resource. Close it with the cable, assets, and other resources
that your application owns.

```java
try (actorSystem;
        var server = web.serve.Http.start(protectedApp, 8080)) {
    Thread.currentThread().join();
}
```

The actor system owns mailboxes, effect threads, timers, and log resources.
Close it during application shutdown. The server owns the HTTP binding and
must also close.

Test an actor definition as an ordinary application. `advance` proves the
state transition and the requested effect without running the effect:

```java
var job = summaryJobs.instantiate(summaryJobs.at("test-job"));
var step = job.advance(
        application.Message.of("run").with("noteId", "42"));
assert step.state().status().equals("running");
assert step.effects().size() == 1;
assert step.effects().getFirst().type().equals("summary.generate");
```

This test is why definitions contain updates and effect data, but no I/O.
Test `Summaries.generateOnce` separately against its owned resource. Then test
the same web handler without a socket:

```java
var result = web.serve.Memory.handle(
        protectedApp,
        web.serve.Memory.get("/notes"));
assert result.status() == 200;
```

`Memory` records status, headers, body, flushes, and handler failures. It is a
useful boundary test for every route.

## Next

- [howto.md](howto.md) gives task recipes for authentication, uploads,
  durable jobs, Turbo, assets, cable, and testing.
- [explanation.md](explanation.md) explains the Go-handler, TEA, and Hotwire
  design and its streaming rules.
- [reference.md](reference.md) is the factual API index. Use the focused
  [session reference](reference-sessions.md) and [upload reference](reference-uploads.md)
  for their failure and lifecycle rules.
