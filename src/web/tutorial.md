# Tutorial: a small notes application

This tutorial builds a small hypermedia application. It serves a list of
notes, accepts a form, and runs the same handlers in memory tests and in an
HTTP server. You add routing, a page, a form, and a session guard in that
order.

You need JDK 27. The types named `Note`, `Notes`, `NotesStore`, `Login`, and
`Views` below are application-owned placeholders. Replace them with your own
types. All other types come from Tuul.

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

Use one `Router` for recognising requests and building links. A route name is
the same in both directions.

```java
import web.dispatch.Router;

var routes = Router.of()
        .get("notes", "/notes")
        .get("note", "/notes/{id}")
        .post("create-note", "/notes")
        .post("login", "/login")
        .post("logout", "/logout");

var noteUrl = routes.path("note", java.util.Map.of("id", "42"));
// /notes/42
```

`Router` chooses the most specific matching template. A path that exists with
the wrong method is a `405`; a path that does not exist is a `404`.

## 3. Connect handlers to routes

`Routing` dispatches the recognised route. The handler receives route data in
the request attributes. `Notes` and `Views` are application-owned placeholders.

```java
import web.Routing;
import web.Responses;
import web.Status;

final class Notes {
    private final web.dispatch.Router routes;

    Notes(web.dispatch.Router routes) {
        this.routes = routes;
    }

    void index(web.Request request, web.Response response) throws Exception {
        var html = Views.notesPage(routes.path("notes")); // application-owned
        Responses.html(html, response);
    }

    void show(web.Request request, web.Response response) throws Exception {
        var id = web.Routing.variables(request).first("id", "");
        Responses.html(Views.notePage(id), response); // application-owned
    }

    void create(web.Request request, web.Response response) throws Exception {
        // Application-owned persistence and validation go here.
        Responses.redirect(routes.path("notes"), response);
    }

    void save(String title, String body) throws Exception {
        NotesStore.save(title, body); // application-owned persistence
    }
}

var notes = new Notes(routes);
var app = Routing.of(routes)
        .on("notes", notes::index)
        .on("note", notes::show)
        .on("create-note", notes::create)
        .on("login", Login::handle)       // application-owned
        .on("logout", Login::logout)      // application-owned
        .otherwise((request, response) -> Responses.empty(Status.NOT_FOUND, response));
```

`Routing.variables(request)` returns path variables. `Routing.route(request)`
returns the matched route name. A handler can use either value without parsing
the path itself.

## 4. Render a page

`Page` gives each request fresh state. It turns the request into an
`application.Message`, applies registered `Update` functions, runs effects,
and renders the resulting state. `NoteState` and `Views.notePage` are
application-owned placeholders.

```java
record NoteState(java.util.List<String> notes, String error) {}

var notesPage = Page.of(() -> new NoteState(java.util.List.of(), ""))
        .on("notes", (state, message) ->
                application.Step.of(state, application.Effect.of("notes.read")))
        .effect("notes.read", NotesStore::read)       // application-owned I/O
        .on("notes.loaded", NotesStore::loaded)      // application-owned update
        .on("error", (state, message) ->
                application.Step.of(new NoteState(state.notes(), message.body().string("reason", "error"))))
        .render((state, request, response) ->
                Responses.html(Views.notePage(state), response)); // app-owned
```

Use the page as the route handler:

```java
var app = Routing.of(routes)
        .on("notes", notesPage)
        .on("note", notes::show)
        .on("create-note", notes::create)
        .on("login", Login::handle)
        .on("logout", Login::logout);
```

`NotesStore.read` handles the `notes.read` effect and emits `notes.loaded`.
`NotesStore.loaded` applies that message to the state. Both methods are
application-owned placeholders. The update does not read storage.

An update must return a `Step`. `Step.state()` is request-local. Long-lived
state belongs in an application or actor and is reached by an effect. An
update failure leaves the state unchanged and becomes an `error` message.

## 5. Define and show a form

Define fields once. The definition draws the form and validates a submission.

```java
import web.forms.Field;
import web.forms.Form;
import web.forms.Forms;
import web.forms.Rules;

var noteForm = Form.at(routes.path("create-note"))
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
Responses.redirect(routes.path("notes"), response); // 303
```

`Forms.reject` writes the submission with status `422`. Turbo uses that status
to replace the form with its errors. A successful form redirect uses `303 See
Other`, which makes the next request a `GET`.

## 6. Add authentication and CSRF checks

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
        web.ui.Attributes.action(routes.path("create-note")),
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
var signedInOnly = sessions.required(routes.path("login"));
web.Handler guardedCreate = notes::create;
var securedRoutes = Routing.of(routes)
        .on("notes", notesPage)
        .on("note", notes::show)
        .on("create-note", guardedCreate.wrappedBy(signedInOnly))
        .on("login", Login::handle)
        .on("logout", Login::logout);

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

## 7. Run and test the application

The server is a resource. Close it with the cable, assets, and other resources
that your application owns.

```java
try (var server = web.serve.Http.start(protectedApp, 8080)) {
    Thread.currentThread().join();
}
```

Run the same handler without a socket:

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
