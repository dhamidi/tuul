# Tutorial: reload a web application

Build one reloadable web application. Run it with `tuul dev`. Change its Java
source and observe the next request use the new generation.

For exact adapter behavior, read [reference.md](reference.md).

## Define a Tuul-aware module

Create a named application module and provide one `reload.Program` service. The
host loads this service from each fresh module layer:

```java
module example.web {
    requires tuul;
    provides reload.Program with example.WebProgram;
}
```

Implement the provider in `src/example/WebProgram.java`:

```java
package example;

import reload.Generation;
import reload.Program;
import web.Responses;
import web.RouteRef;
import web.Router;
import web.reload.ReloadHandler;

public final class WebProgram implements Program {

    private static final RouteRef HOME = RouteRef.of("home", "/");

    @Override
    public Generation define() {
        var routes = Router.of().get(HOME,
                (request, response) -> Responses.text("hello\n", response));
        return ReloadHandler.attach(Generation.empty(), routes);
    }
}
```

`define` returns values. It does not open the listening socket. Add resources
to the generation when the values own something that must close after drain.

## Start the development host

Run the single named module on port 8080:

```sh
tuul dev --port 8080
```

The command compiles the complete named module closure and snapshots resources
in memory. It prints the
active generation and listening URL. It then watches project sources and
resources.

Request the page:

```sh
curl http://localhost:8080/
```

The response is `hello`.

## Use an external JDK-only module

An application with no Tuul imports can use one named source module. Create
this tree (there is no `entrypoints/` directory):

```text
src/
├── module-info.java
└── example/
    └── ExternalHandler.java
vendor/
```

Declare the JDK HTTP service in `src/module-info.java`:

```java
module example.external {
    requires jdk.httpserver;
    provides com.sun.net.httpserver.HttpHandler with example.ExternalHandler;
}
```

Implement `example.ExternalHandler` in `src/example/ExternalHandler.java`:

```java
package example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ExternalHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        var body = "hello\\n".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
```

Start the host without an entrypoint name:

```sh
tuul dev --port 8080
```

`tuul dev` compiles this single named module in-process. It discovers exactly
one JDK HTTP provider from the fresh module layer and keeps the listener
stable. If the provider implements `AutoCloseable`, the generation closes it
after the last request admitted to that layer drains.

## Change the application

Change `hello` to `hello again` and save the file. The watcher submits the
complete project revision. The host compiles and validates it before
activation.

Request the page again:

```sh
curl http://localhost:8080/
```

The response is `hello again`. The server keeps its port. A request
that already started finishes on the prior generation.

## Keep the last valid generation

Remove a closing parenthesis and save the file. The host prints the compiler
problem and rejects that revision. Requests still use `hello again`.

Fix the source and save it. The watcher submits and activates the corrected
revision.

## Stop the host

Press Control-C. The host closes the watcher, coordinator, and HTTP server.

Read [howto.md](howto.md) for HTTP uploads and manual adapter setup. Read the
[reload guide](../../reload/guide.md) for generation and drain semantics.
