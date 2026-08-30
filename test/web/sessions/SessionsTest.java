package web.sessions;

import harness.Check;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import web.Handler;
import web.Headers;
import web.Request;
import web.Responses;
import web.Status;
import web.serve.Memory;

public final class SessionsTest {

    private static final String SECRET = "a secret that is comfortably long enough";

    private SessionsTest() {}

    public static void run() throws Exception {
        signing();
        sessions();
        csrf();
    }

    private static void signing() {
        var signature = Signature.of(SECRET);
        var signed = signature.sign("{\"user\":\"ada\"}");
        Check.equal("what was signed comes back", "{\"user\":\"ada\"}", signature.verify(signed).orElse(""));
        Check.that("a payload somebody edited does not",
                signature.verify(signed.replace(signed.charAt(3), 'X')).isEmpty());
        Check.that("nor does a tag somebody edited",
                signature.verify(signed.substring(0, signed.length() - 2) + "AA").isEmpty());
        Check.that("nor does something that is not signed at all", signature.verify("plain").isEmpty());
        Check.that("nor does a value signed with another key",
                Signature.of("a different secret that is long enough").verify(signed).isEmpty());
        Check.throwing("a secret too short to be one is refused", () -> Signature.of("short"));
    }

    private static void sessions() throws Exception {
        var now = Instant.parse("2026-08-28T12:00:00Z");
        var sessions = Sessions.of(SECRET).clocked(Clock.fixed(now, ZoneOffset.UTC)).lasting(Duration.ofHours(1));

        var written = Memory.handle((request, response) -> {
            sessions.write(response, Session.NONE.with("user", "ada"));
            Responses.text("hello", response);
        }, Memory.get("/"));
        var cookie = written.header("Set-Cookie").orElse("");
        Check.that("a session is written as a cookie", cookie.startsWith("session="));
        Check.that("that a script cannot read", cookie.contains("HttpOnly"));
        Check.that("and that does not travel on a cross-site post", cookie.contains("SameSite=Lax"));

        var carried = request("GET", "/", "Cookie", value(cookie));
        Check.equal("and comes back as what was put in it", "ada", sessions.read(carried).text("user", ""));
        Check.that("a session that is there says so", sessions.read(carried).present());

        var jdk = request("GET", "/", "Cookie",
                "$Version=\"1\"; session=\"" + value(cookie).substring("session=".length())
                        + "\";$Path=\"/\";$Domain=\"localhost.local\"");
        Check.equal("a JDK cookie manager keeps the signed session", "ada", sessions.read(jdk).text("user", ""));

        var tampered = request("GET", "/", "Cookie", "session=" + value(cookie).substring(8, 40) + "x");
        Check.that("one that was edited is no session at all", !sessions.read(tampered).present());

        var later = sessions.clocked(Clock.fixed(now.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        Check.that("and one that has expired is no session either", !later.read(carried).present());
        Check.that("even though its signature is perfectly good",
                Signature.of(SECRET).verify(value(cookie).substring("session=".length())).isPresent());

        Handler secret = (request, response) -> Responses.text("secret", response);
        var guarded = secret.wrappedBy(sessions.required("/sign-in"));
        Check.equal("a browser with no session is sent somewhere to get one",
                Status.SEE_OTHER, Memory.handle(guarded, request("GET", "/", "Accept", "text/html")).status());
        Check.equal("a fetch call is told it is unauthorised instead",
                Status.UNAUTHORIZED,
                Memory.handle(guarded, request("GET", "/", "Accept", "application/json")).status());
        Check.equal("and one with a session is let through",
                "secret", Memory.handle(guarded, carried).text());

        var cleared = Memory.handle((request, response) -> {
            sessions.clear(response);
            Responses.empty(Status.NO_CONTENT, response);
        }, Memory.get("/"));
        Check.that("signing out expires the cookie", cleared.header("Set-Cookie").orElse("").contains("Max-Age=0"));
    }

    private static void csrf() throws Exception {
        var csrf = Csrf.of(SECRET);
        Handler handler = (request, response) -> Responses.text(Csrf.token(request) + "|" + body(request), response);
        var guarded = handler.wrappedBy(csrf.middleware());

        var issued = Memory.handle(guarded, Memory.get("/form"));
        var token = issued.text().split("\\|")[0];
        var cookie = value(issued.header("Set-Cookie").orElse(""));
        Check.that("a page is given a token", !token.isEmpty());
        Check.that("and the same one in a cookie it cannot read",
                issued.header("Set-Cookie").orElse("").contains("HttpOnly"));

        var accepted = Memory.handle(guarded, form("POST", "/form", cookie, "_csrf=" + token + "&body=hello"));
        Check.equal("a form that sends it back is accepted", Status.OK, accepted.status());
        Check.that("and the handler still gets the body it was reading for",
                accepted.text().contains("body=hello"));

        Check.equal("a form that sends none is refused",
                Status.FORBIDDEN, Memory.handle(guarded, form("POST", "/form", cookie, "body=hello")).status());
        Check.equal("a form that sends the wrong one is refused",
                Status.FORBIDDEN,
                Memory.handle(guarded, form("POST", "/form", cookie, "_csrf=guessed&body=hello")).status());
        Check.equal("a token that is merely the beginning of the right one is refused",
                Status.FORBIDDEN,
                Memory.handle(guarded, form("POST", "/form", cookie, "_csrf=" + token.substring(0, 10))).status());
        Check.equal("and an empty one is not a token at all",
                Status.FORBIDDEN, Memory.handle(guarded, form("POST", "/form", cookie, "_csrf=")).status());
        Check.equal("and so is one with a token and no cookie to compare it against",
                Status.FORBIDDEN, Memory.handle(guarded, form("POST", "/form", "", "_csrf=" + token)).status());

        var header = Request.of("POST", "/form",
                Headers.of("Cookie", cookie).with(Csrf.HEADER, token), Request.body(""));
        Check.equal("the header Turbo sends is accepted too", Status.OK, Memory.handle(guarded, header).status());

        var stolen = Memory.handle(guarded, form("POST", "/form",
                "csrf=" + Signature.of("another secret that is long enough").sign(token), "_csrf=" + token));
        Check.equal("a cookie signed by somebody else is not a cookie this server wrote",
                Status.FORBIDDEN, stolen.status());

        var large = Memory.handle(handler.wrappedBy(Csrf.of(SECRET).reading(16).middleware()),
                form("POST", "/form", cookie, "_csrf=" + token + "&body=" + "x".repeat(100)));
        Check.equal("a form too large to read for a token is refused rather than read",
                413, large.status());
    }

    private static Request request(String method, String path, String name, String value) {
        return Request.of(method, path, Headers.of(name, value), Request.body(""));
    }

    private static Request form(String method, String path, String cookie, String body) {
        var headers = Headers.of("Content-Type", "application/x-www-form-urlencoded");
        return Request.of(method, path, cookie.isEmpty() ? headers : headers.with("Cookie", cookie),
                Request.body(body));
    }

    /// The body as the handler downstream sees it — read once, because reading
    /// it is what consumes it.
    private static String body(Request request) throws IOException {
        var form = request.form();
        return form.names().stream().map(name -> name + "=" + form.first(name, ""))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String value(String setCookie) {
        return setCookie.split(";")[0];
    }
}
