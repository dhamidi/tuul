package project;

import application.Message;
import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import json.Json;
import web.Handler;
import web.serve.Http;

/// `tuul hyperspec` — the command that runs specs from the filesystem against
/// something already listening.
///
/// The specs it runs are exercised elsewhere; what is tested here is everything
/// around them, which is where a command goes wrong: what a host may look like,
/// which mistakes are refused before anything runs, and whether a spec that
/// does not hold actually fails the command. The last of those is the whole
/// reason to have this in a script.
public final class SpecsTest {

    private static final String PAGE = """
            <!DOCTYPE html><html lang="en"><body>
            <a href="/elsewhere">Somewhere</a>
            </body></html>
            """;

    private SpecsTest() {}

    public static void run() throws Exception {
        hosts();
        sources();
        running();
        commanding();
    }

    /// A host is the one argument somebody types by hand every time, so it
    /// takes the three shapes people actually type and refuses the ones that
    /// could only be a guess.
    private static void hosts() {
        Check.equal("a whole URI is taken as it is",
                URI.create("http://localhost:8099"), Specs.host("http://localhost:8099"));
        Check.equal("https too", URI.create("https://example.com"), Specs.host("https://example.com"));
        Check.equal("a host and a port mean http", URI.create("http://localhost:8099"), Specs.host("localhost:8099"));
        Check.equal("a bare port means this machine", URI.create("http://localhost:8099"), Specs.host(":8099"));
        Check.equal("a trailing slash is not a path", URI.create("http://localhost:8099/"), Specs.host("http://localhost:8099/"));
        Check.equal("and spaces around it are somebody's shell",
                URI.create("http://localhost:8099"), Specs.host("  localhost:8099  "));

        Check.throwing("a bare number is refused rather than guessed at", () -> Specs.host("8099"));
        Check.throwing("so is a scheme nothing here speaks", () -> Specs.host("ftp://localhost:8099"));
        Check.throwing("so is nothing at all", () -> Specs.host(""));
        Check.throwing("and so is a path, which resolving would throw away",
                () -> Specs.host("http://localhost:8099/browse"));

        try {
            Specs.host("8099");
        } catch (IllegalArgumentException refused) {
            Check.that("a refused host says what to type instead: " + refused.getMessage(),
                    refused.getMessage().contains(":8099"));
        }
    }

    /// A file that is not there is a mistake in the command, not a spec that
    /// fails — so it stops everything before the first request.
    private static void sources() throws IOException {
        var directory = Files.createTempDirectory("tuul-specs");
        directory.toFile().deleteOnExit();
        var spec = directory.resolve("one.hyperspec");
        Files.writeString(spec, "visit /\nexpect status 200\n");

        var found = Specs.files(List.of(spec.toString()));
        Check.equal("a named spec is read", 1, found.size());
        Check.equal("and keeps its path as its name", spec.toString(), found.getFirst().name());
        Check.that("and its text", found.getFirst().text().startsWith("visit /"));

        try {
            Specs.files(List.of(spec.toString(), directory.resolve("absent.hyperspec").toString()));
            Check.that("a spec that is not there is refused", false);
        } catch (IOException refused) {
            Check.that("a spec that is not there is refused by name: " + refused.getMessage(),
                    refused.getMessage().startsWith("no such spec:") && refused.getMessage().contains("absent"));
        }
    }

    /// Against a live server, because that is the only way a hyperspec runs.
    private static void running() throws Exception {
        try (var server = Http.start(page(), 0)) {
            var service = URI.create("http://localhost:" + server.port());

            var held = new StringWriter();
            var holds = Specs.run(service, List.of(spec("visit /\nexpect link \"Somewhere\"\n")), false, held);
            Check.equal("a spec that holds is not a failure", 0, holds.failed());
            Check.equal("and it ran", 1, holds.ran());
            Check.that("and said so, in the shape the suite prints: " + held,
                    held.toString().contains("  ok   default link \"Somewhere\"") && held.toString().contains("1 met"));

            var broke = new StringWriter();
            var breaks = Specs.run(service, List.of(spec("visit /\nexpect link \"Nowhere\"\n")), false, broke);
            Check.equal("a spec that does not hold is counted", 1, breaks.failed());
            Check.that("and says what the page offered instead: " + broke,
                    broke.toString().contains("FAIL") && broke.toString().contains("offers"));

            var quiet = new StringWriter();
            Specs.run(service, List.of(spec("visit /\nexpect status 200\n")), true, quiet);
            Check.equal("quiet says nothing at all about a spec that holds", "", quiet.toString());

            var loud = new StringWriter();
            Specs.run(service, List.of(spec("visit /\nexpect status 404\n")), true, loud);
            Check.that("but still says what failed: " + loud, loud.toString().contains("FAIL"));

            var unreadable = new StringWriter();
            var refused = Specs.run(service, List.of(spec("client alice {\nexpect status 200\n")), false, unreadable);
            Check.equal("a spec that cannot be parsed is a failure, not a crash", 1, refused.failed());
            Check.that("and is named as unreadable rather than unmet: " + unreadable,
                    unreadable.toString().contains("unreadable"));

            try {
                Specs.run(service, List.of(), false, new StringWriter());
                Check.that("being given nothing to run is refused", false);
            } catch (IOException nothing) {
                Check.that("being given nothing to run is refused: " + nothing.getMessage(),
                        nothing.getMessage().contains("nothing to run"));
            }
        }
    }

    /// The command, dispatched the way the command line dispatches it — the
    /// wiring is the part most likely to be wrong, and an exit status is the
    /// only thing a script reads.
    private static void commanding() throws Exception {
        try (var server = Http.start(page(), 0)) {
            var host = "localhost:" + server.port();
            var directory = Files.createTempDirectory("tuul-hyperspec");
            directory.toFile().deleteOnExit();
            var spec = directory.resolve("holds.hyperspec");
            Files.writeString(spec, "visit /\nexpect link \"Somewhere\"\n");

            Check.equal("naming a file holds, and exits cleanly",
                    0, exit(Json.Object.of().with("host", host)
                            .with("specs", Json.Array.strings(List.of(spec.toString())))));

            Check.equal("so does --eval, with the host after it",
                    0, exit(Json.Object.of().with("host", host).with("eval", "visit /\nexpect status 200\n")));

            Check.equal("a spec that does not hold fails the command",
                    1, exit(Json.Object.of().with("host", host).with("eval", "visit /\nexpect status 404\n")));

            Check.equal("so does a spec that is not there",
                    1, exit(Json.Object.of().with("host", host)
                            .with("specs", Json.Array.strings(List.of("nowhere.hyperspec")))));

            Check.equal("and so does a host with nothing behind it",
                    1, exit(Json.Object.of().with("host", ":1").with("eval", "visit /\n")));

            var said = new StringWriter();
            dispatch(Json.Object.of().with("host", ":1").with("eval", "visit /\n"), new StringWriter(), said);
            Check.that("which says so rather than failing every spec the same way: " + said,
                    said.toString().contains("nothing is listening"));
        }
    }

    private static Specs.Source spec(String text) {
        return new Specs.Source("(test)", text);
    }

    private static Handler page() {
        return (request, response) -> {
            response.header("Content-Type", "text/html; charset=utf-8");
            response.writer().write(PAGE);
        };
    }

    private static int exit(Json.Object values) {
        return dispatch(values, new StringWriter(), new StringWriter());
    }

    private static int dispatch(Json.Object values, Writer out, Writer err) {
        return App.of(State.of(Path.of(".")), out, err)
                .dispatch(Message.of("project.hyperspec", values))
                .exit();
    }
}
