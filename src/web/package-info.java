/// A web framework for hypermedia applications, built on `jdk.httpserver`.
///
/// A handler takes a [web.Request] and writes a [web.Response]. That is the
/// whole contract. Everything else in this package builds on those two types,
/// and a server is one more implementation of them.
///
/// The shape comes from Go's `net/http`, for Go's reason. A handler that takes
/// an interface runs in a test with no socket. It runs on another server
/// without a rewrite. Middleware wraps it and knows about neither.
///
/// ## A first server
///
/// A handler is a lambda. [web.Responses] writes the common answers.
///
/// ```
/// Handler hello = (request, response) -> Responses.text("hello", response);
///
/// try (var server = Http.start(hello, 8080)) {
///     Thread.currentThread().join();
/// }
/// ```
///
/// Test the same handler with no port. [web.serve.Memory] runs it and records
/// everything it said.
///
/// ```
/// var recorded = Memory.handle(hello, Memory.get("/"));
/// recorded.status();     // 200
/// recorded.text();       // hello
/// recorded.failure();    // empty when the handler threw nothing
/// ```
///
/// ## Routing
///
/// A [web.dispatch.Router] holds named routes. [web.Routing] is that router as
/// a handler. Routes are named because a name works in both directions: it
/// recognises a path, and it builds one.
///
/// ```
/// var routes = Router.of()
///         .get("posts", "/posts")
///         .get("post", "/posts/{slug}")
///         .post("create", "/posts");
///
/// var app = Routing.of(routes)
///         .on("posts", Posts::index)
///         .on("post", Posts::show)
///         .on("create", Posts::create);
/// ```
///
/// A handler reads what the path carried with
/// [web.Routing#variables(web.Request)]. It reads the name of the route that
/// matched with [web.Routing#route(web.Request)]. Write
/// `routes.path("post", Map.of("slug", "hello"))` for a link, and no handler
/// writes a URL as a string.
///
/// ## Request values and answers
///
/// [web.Headers] and [web.Parameters] read the values that arrive with a
/// request. [Cookie] builds one `Set-Cookie` value. [Cookies] reads the
/// `Cookie` header and adds or clears response cookies. These cookie helpers
/// live in `web` because handlers use them without a session policy.
///
/// [Accept] reads the media ranges in an `Accept` header. [Negotiate] chooses
/// one server offer or writes the Turbo Stream or redirect answer for a form
/// submission. These types also live in `web` because content negotiation is
/// part of the request path.
///
/// ## Features
///
/// A package can bring routes, handlers, files and import-map pins with it. A
/// [web.Feature] is all four as one value, and [web.Features] composes the
/// application's own routes with the features it uses.
///
/// ```
/// var wiring = Features.of(routes, Ui.feature(), cable.feature(Topics.fixed("posts")));
///
/// var app = wiring.routing()
///         .on("posts", Posts::index)
///         .otherwise(NotFound::page);
/// ```
///
/// `wiring.assets()` and `wiring.importmap()` are what a page needs to link
/// what the features shipped. The URL that answers for a file comes with them,
/// built from [web.assets.Assets#prefix()] rather than written out again.
///
/// A feature is a value and nothing is discovered from the class path. An
/// application names what it uses, which is the only way to read a program and
/// know what it serves.
///
/// ## Wrappers
///
/// A [web.Middleware] is a handler that wraps a handler.
///
/// ```
/// var stack = app.wrappedBy(Middlewares.methodOverride());
/// ```
///
/// A wrapper that a package needs is declared by that package rather than
/// installed by hand — see [web.Feature#wrappedBy(web.Middleware)], which puts
/// it around everything the application answers, and
/// [web.Handler#wrappedBy(web.Middleware)], which guards one handler.
///
/// [web.Middlewares#methodOverride()] believes a form that says it meant PUT
/// or DELETE, because a browser sends only GET and POST. It reads the body to
/// find `_method` and then puts the body back, so the handler still reads the
/// form.
///
/// ## A page
///
/// A handler can also be an application. [web.Page] turns the request into a
/// message, runs the update functions, and renders the state it produced.
///
/// ```
/// var page = Page.of(Post::none)
///         .on("post", Posts::look)
///         .effect("posts.read", Posts::find)
///         .render((state, request, response) -> Responses.html(Views.post(state), response));
/// ```
///
/// The state belongs to one request. The state that outlives a request belongs
/// to an application or an actor, and an effect reaches it. An update that
/// throws leaves the state alone and becomes an `error` message, so a page
/// renders what it has.
///
/// ## What is in the subpackages
///
///   - [web.ui] renders HTML to a writer. `Tags` builds elements, and `Turbo`
///     builds Turbo Streams.
///   - [web.dispatch] recognises a path and builds one, from URI templates.
///   - [web.forms] defines a form once. The definition draws the form, reads
///     what somebody sent, and draws the form again with the errors in it.
///   - [web.assets] digests a file name, serves the file as immutable, and
///     pins ES modules in an import map. There is no bundler and no build step.
///   - [web.sessions] provides signed-cookie sessions and CSRF middleware. The
///     server trusts cookie contents only after their signature verifies.
///   - [web.uploads] streams multipart parts to disk. It keeps the client
///     filename as a label and chooses the stored filename.
///   - [web.cable] sends events to connected clients over `text/event-stream`.
///   - [web.serve] binds a server. `Http` is `jdk.httpserver`, and `Memory` is
///     the same interfaces with no socket.
///   - [web.hyperspec] drives a whole application through its affordances, and
///     asserts against what the client can see and do.
///
/// ## The types in this package
///
///   - [web.Handler] answers a request. [web.Middleware] wraps a handler.
///   - [web.Request] is what arrived. [web.Response] is what a server is
///     writing. [web.Headers] and [web.Parameters] are the parts of both.
///   - [web.Responses] writes an answer. [web.Status] names the codes.
///   - [web.Routing] dispatches. [web.Requests] turns a request into a message.
///   - [web.Page] is a handler that is an application.
///
/// ## Why a request is a record and a response is an interface
///
/// A request is data. A middleware rewrites one field of it and passes the
/// result on, so the `_method` override and the mount prefix are one call and
/// not a wrapper class. Use [web.Request#body(java.io.InputStream)] to put
/// back a body that you read.
///
/// A response is not data, because it is being written. A page renders as it
/// is built. An asset goes from disk to socket and is never in memory. An
/// event stream stays open for hours. A value that holds a finished body
/// cannot do any of those.
///
/// The headers go out exactly once. [web.Response#body()],
/// [web.Response#writer()] and [web.Response#close()] send them.
/// [web.Response#sent()] says whether they are gone. A status set after that
/// throws, because a header that is lost without a sound is a fault that only
/// shows itself under load.
///
/// ## Surprises worth knowing
///
///   - **The body is a stream, and you read it once.** An upload is not
///     something to hold in memory. Middleware that looks inside a body puts
///     the body back with [web.Request#body(java.io.InputStream)].
///   - **A redirect after a form must be 303.** A browser turns 302 into a
///     GET, but Turbo obeys the specification and sends the POST again. See
///     [web.Status#SEE_OTHER].
///   - **A form that failed validation answers 422.** Turbo replaces the page
///     on a 422 and ignores a 200, so the status is what makes the errors
///     appear. See [web.Status#UNPROCESSABLE].
///   - **`jdk.httpserver` speaks HTTP/1.1 and has no WebSocket.** Turbo
///     Streams arrive over `text/event-stream`, which is [web.cable]. Deploy
///     behind Caddy or nginx for TLS and for HTTP/2.
///   - **A browser allows about six connections per origin over HTTP/1.1.** An
///     event stream holds one of them for as long as the client is there.
///   - **A server binding sets the timeouts.** [web.serve.Http] sets them,
///     because a slow client holds a server that does not.
package web;
