# Tutorial: reload a web application

Build one reloadable web application. Run it with `tuul dev`. Change its Java
source and observe the next request use the new generation.

For exact adapter behavior, read [reference.md](reference.md).

## Define the program

Create `src/web/main.java`. Implement `reload.Program` so the development host
can build one complete generation without starting a second server:

```java
import reload.Generation;
import reload.Program;
import web.Responses;
import web.RouteRef;
import web.Router;
import web.reload.ReloadHandler;

public final class main implements Program {

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

Run the web entrypoint on port 8080:

```sh
tuul dev web --port 8080
```

The command compiles classes and snapshots resources in memory. It prints the
active generation and listening URL. It then watches project sources and
resources.

Request the page:

```sh
curl http://localhost:8080/
```

The response is `hello`.

## Change the application

Change `hello` to `hello again` and save the file. The watcher submits the
complete project revision. The host compiles and validates it before
activation.

Request the page again:

```sh
curl http://localhost:8080/
```

The response is `hello again`. The server keeps its port and process. A request
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
