package web.reload;

import harness.Check;
import reload.Generation;
import reload.Reload;
import reload.Revision;
import web.Responses;
import web.serve.Memory;

/// Fast checks for the HTTP adapter around reload generation leases.
public final class ReloadHandlerTest {

    private ReloadHandlerTest() {}

    public static void run() {
        unavailableWithoutHandler();
        servesLatestHandler();
    }

    private static void unavailableWithoutHandler() {
        var reload = new Reload();
        var handler = new ReloadHandler(reload);
        Check.equal("no active generation answers unavailable", 503,
                Memory.handle(handler, Memory.get("/")).status());
        reload.submit(Revision.of("empty", Generation::empty));
        Check.equal("an active generation without a handler answers unavailable", 503,
                Memory.handle(handler, Memory.get("/")).status());
        reload.close();
        Check.equal("a closed coordinator answers unavailable", 503,
                Memory.handle(handler, Memory.get("/")).status());
    }

    private static void servesLatestHandler() {
        var reload = new Reload();
        var handler = new ReloadHandler(reload);
        reload.submit(Revision.of("one", () -> ReloadHandler.attach(Generation.empty(),
                (request, response) -> Responses.text("one", response))));
        Check.equal("the first generation handles the request", "one",
                Memory.handle(handler, Memory.get("/")).text());
        reload.submit(Revision.of("two", () -> ReloadHandler.attach(Generation.empty(),
                (request, response) -> Responses.text("two", response))));
        Check.equal("the next request uses the new generation", "two",
                Memory.handle(handler, Memory.get("/")).text());
        Check.equal("the adapter releases its lease after handling", 0,
                reload.status().leases().getOrDefault("two", -1));
        reload.close();
    }
}
