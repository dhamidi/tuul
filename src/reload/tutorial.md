# Tutorial: reload a web application

Build one reloadable web application. Run it with `tuul dev`. Change its Java
source and observe the next request use the new generation.

For exact behavior, read [reference.md](reference.md).

You need the `reload`, `web`, and `web.serve` packages. You do not need a build
plugin or a Java agent.

## Define the program

Create `src/web/main.java`. Implement `reload.Program` so the development host
can build one complete generation without starting a second server.

```java
import reload.Generation;
import reload.Program;
import web.Responses;
import web.RouteRef;
import web.Router;

public final class main implements Program {

    private static final RouteRef HOME = RouteRef.of("home", "/");

    @Override
    public Generation define() {
        var routes = Router.of().get(HOME,
                (request, response) -> Responses.text("hello\n", response));
        return Generation.of(routes);
    }
}
```

`define` returns values. It does not open the listening socket. Add resources
to the generation when the values own something that must close after drain.

The class name is `main` because Tuul entrypoints use `src/<name>/main.java`.
The development host loads that class from the selected entrypoint.

## Start the development host

Run the web entrypoint on port 8080:

```sh
tuul dev web --port 8080
```

The command compiles the project before it starts the server. It prints the
active generation and the listening URL. It then watches the project sources
and resources.

Open the application:

```sh
curl http://localhost:8080/
```

The response is:

```text
hello
```

## Change the application

Change `"hello\n"` to `"hello again\n"` and save the file.

The watcher submits a new revision. The host compiles the revision into a new
generation. It activates that generation only after `main.define()` succeeds.

Request the page again:

```sh
curl http://localhost:8080/
```

The response is now:

```text
hello again
```

The server kept its port. The host process did not restart. A request that was
already running when the generation changed completed on the prior code.

## Make a compiler error

Remove a closing parenthesis and save the file.

The host prints the compiler problem. It rejects the revision. Request the page
again. The last valid generation still answers with `hello again`.

Fix the source and save it. The watcher submits another revision. The host
activates it after compilation and validation succeed.

## Stop the host

Press Control-C. The host closes the watcher and HTTP server. The coordinator
stops new work and closes a generation when its current handler calls finish.

## Next

- [howto.md](howto.md) covers actors, applications, effects, HTTP submission,
  and validation.
- [reference.md](reference.md) specifies the public values and state machine.
- [guide.md](guide.md) explains why reload uses whole generations.
