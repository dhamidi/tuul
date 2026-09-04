# How-to: connect HTTP to reload

## Serve a reloadable handler

Create one adapter for the coordinator and mount it on the HTTP server:

```java
var reload = new reload.Reload();
var http = new ReloadHandler(reload);
Http.start(http, 8080);
```

Store the handler in each generation with `ReloadHandler.attach`:

```java
public Generation define() {
    var routes = Router.of().get(RouteRef.of("home", "/"),
            (request, response) -> Responses.text("hello\n", response));
    return ReloadHandler.attach(Generation.empty(), routes);
}
```

The adapter answers `503` before activation and when the active generation
does not contain `ReloadHandler.HANDLER`. It closes the reload lease after the
handler returns or throws.

## Serve an external named module

An application that does not import Tuul can provide the JDK service
`com.sun.net.httpserver.HttpHandler` from its named module:

```java
module example.app {
    requires jdk.httpserver;
    provides com.sun.net.httpserver.HttpHandler with example.App;
}
```

The host supplies `new JdkGenerationFactory()` to its named revision compiler.
The factory reads the compiled root descriptor in each fresh `ModuleLayer`.
The root must provide exactly one HTTP contribution. Providers in dependency
modules do not count. `JdkReloadHandler` keeps the listener stable, leases the
active generation for each exchange, and closes an `AutoCloseable` provider
after its last admitted exchange drains.

## Accept a revision upload

Create a private staging path, an upload policy, and a source:

```java
var source = new HttpRevisionSource(staging, limits, policy);
source.map(compiler::compile).start(reload::submit);
Http.start(source.handler(), 8081);
```

The source accepts `POST multipart/form-data`. Authenticate before the source
reads the body. The source writes files below its staging path, validates the
manifest and relative names, and submits one `reload.Revision` after parsing.

The callback decides whether and how to compile the revision. The source owns
successful staging trees until `close`; close the source after compilation and
activation finish to remove them and stop future uploads.
