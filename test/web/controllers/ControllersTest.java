package web.controllers;

import harness.Check;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import web.Handler;
import web.Headers;
import web.Request;
import web.Responses;
import web.Status;
import web.serve.Memory;
import web.serve.Recorded;
import web.ui.Tags;
import web.ui.Turbo;

public final class ControllersTest {

    private static final String SECRET = "a secret that is comfortably long enough";

    private ControllersTest() {}

    public static void run() throws Exception {
        negotiation();
        turbo();
        cookies();
        signing();
        sessions();
        csrf();
        multipart();
        uploads();
    }

    private static void negotiation() {
        var accept = Accept.parse("text/html, application/xml;q=0.9, */*;q=0.8");
        Check.equal("what was named outright is preferred", 1.0, accept.quality("text/html"));
        Check.equal("what was named with a quality has it", 0.9, accept.quality("application/xml"));
        Check.equal("anything else falls to the wildcard", 0.8, accept.quality("image/png"));
        Check.equal("and the best on offer is the one it likes most",
                "text/html", accept.best("application/xml", "text/html").orElse(""));

        Check.equal("the most specific range decides, not the last one",
                1.0, Accept.parse("text/*;q=0.2, text/html").quality("text/html"));
        Check.equal("and a type range still covers its subtypes",
                0.2, Accept.parse("text/*;q=0.2, text/html").quality("text/plain"));

        Check.equal("a tie goes to what the server offered first",
                "text/html", Accept.parse("*/*").best("text/html", "application/json").orElse(""));
        Check.equal("and the server's order is the only thing that breaks it",
                "application/json", Accept.parse("*/*").best("application/json", "text/html").orElse(""));

        Check.that("a quality of zero is a refusal, not a preference",
                !Accept.parse("*/*, image/gif;q=0").accepts("image/gif"));
        Check.that("something refused is never the best",
                Accept.parse("image/gif;q=0").best("image/gif").isEmpty());

        Check.equal("no Accept header means anything will do", 1.0, Accept.parse("").quality("text/html"));
        Check.equal("and so does a header nobody can read", 1.0, Accept.parse("text/html;q=banana").quality("text/html"));
        Check.equal("a quoted comma is not a separator",
                1, Accept.parse("text/html;label=\"a,b\"").ranges().size());
    }

    /// The decision this package exists to make.
    private static void turbo() throws Exception {
        Handler handler = (request, response) ->
                Negotiate.stream(request, response, Turbo.replace("notes", Tags.div(Tags.text("saved"))), "/notes");

        var turbo = Memory.handle(handler, request("POST", "/notes",
                "Accept", "text/vnd.turbo-stream.html, text/html, application/xhtml+xml"));
        Check.equal("Turbo asked for a stream and gets one", Status.OK, turbo.status());
        Check.equal("with the content type that makes it a stream",
                Responses.TURBO_STREAM + "; charset=utf-8", turbo.header("Content-Type").orElse(""));
        Check.that("carrying the update", turbo.text().contains("<turbo-stream action=\"replace\""));

        var browser = Memory.handle(handler, request("POST", "/notes",
                "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
        Check.equal("a browser without Turbo is redirected instead", Status.SEE_OTHER, browser.status());
        Check.equal("to somewhere it can see what happened", "/notes", browser.header("Location").orElse(""));
        Check.equal("and a 303, or the form is submitted again", 303, browser.status());

        Check.that("a page request accepts */* but does not want a stream",
                !Negotiate.wantsStream(request("GET", "/notes", "Accept", "text/html,*/*;q=0.8")));
    }

    private static void cookies() {
        var request = request("GET", "/", "Cookie", "session=abc; csrf=def");
        Check.equal("cookies arrive in one header", "abc", Cookies.first(request, "session").orElse(""));
        Check.equal("all of them", "def", Cookies.first(request, "csrf").orElse(""));
        Check.that("and one nobody sent is absent", Cookies.first(request, "other").isEmpty());

        var cookie = Cookie.of("session", "value");
        Check.equal("a cookie defaults to the safe answers",
                "session=value; Path=/; HttpOnly; SameSite=Lax", cookie.header());
        Check.equal("an age is in seconds, as the header wants it",
                "session=value; Path=/; Max-Age=3600; HttpOnly; SameSite=Lax",
                cookie.lasting(Duration.ofHours(1)).header());

        Check.throwing("a value that would end the header is refused",
                () -> Cookie.of("session", "a\r\nSet-Cookie: admin=1"));
        Check.throwing("and so is a name that would",
                () -> Cookie.of("a\nb", "value"));
        Check.throwing("and a value with a semicolon in it, which would be two cookies",
                () -> Cookie.of("session", "a;b"));
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
                Status.ERROR, large.status());
        Check.that("saying so", large.failure().map(Exception::getMessage).orElse("").contains("will not be read"));
    }

    private static void multipart() throws Exception {
        var body = "--X\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "a note\r\n"
                + "--X\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"notes.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "line one\r\nline two\r\n"
                + "--X--\r\n";

        var parts = attempted(parse(body, 0));
        Check.equal("every part arrives", 2, parts.size());
        Check.equal("a field is a field", "title|a note", parts.get(0));
        Check.equal("a file keeps its name and its type", "file|notes.txt|text/plain", parts.get(1));

        var dripped = attempted(Multipart.of(request(body, "X", 1)));
        Check.equal("and a body arriving one byte at a time reads the same",
                parts, dripped);

        var split = attempted(Multipart.of(request(body, "X", 3)));
        Check.equal("as does one whose boundary straddles two reads", parts, split);

        Check.equal("a name that is not ASCII survives the encoding browsers use for it",
                "café.txt", named("filename*=UTF-8''caf%C3%A9.txt"));
        Check.equal("a quoted name with an escape in it is read as written",
                "a\"b.txt", named("filename=\"a\\\"b.txt\""));

        refuses("a body that ends in the middle of a part is refused",
                () -> read(parse("--X\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nunfinished", 0)));
        refuses("and one whose part has no name",
                () -> read(parse("--X\r\nContent-Type: text/plain\r\n\r\nx\r\n--X--\r\n", 0)));
        Check.throwing("a request that is not multipart at all is refused",
                () -> Multipart.of(Memory.post("/", "a=1")));
        Check.throwing("and one with no boundary to split on",
                () -> Multipart.of(Request.of("POST", "/",
                        Headers.of("Content-Type", "multipart/form-data"), Request.body(""))));

        refuses("more parts than the limit allows is refused",
                () -> read(Multipart.of(request(body, "X", 0), Limits.DEFAULT.parts(1))));
    }

    private static void uploads() throws Exception {
        var directory = Files.createTempDirectory("tuul-uploads");
        directory.toFile().deleteOnExit();

        var received = Uploads.into(request(upload("../../etc/passwd", "secrets"), "X", 0), directory);
        Check.equal("the file arrives", 1, received.files().size());
        var upload = received.file("file").orElseThrow();
        Check.equal("with what was in it", "secrets", Files.readString(upload.stored()));
        Check.equal("and how much of it there was", 7L, upload.size());
        Check.equal("the fields beside it arrive too", "a note", received.fields().first("title", ""));

        Check.that("a hostile name lands inside the directory it was given",
                upload.stored().getParent().equals(directory));
        Check.that("under a name this server chose",
                upload.stored().getFileName().toString().matches("[0-9a-f]{32}"));
        Check.that("and nothing called passwd exists anywhere near it",
                Files.list(directory).noneMatch(path -> path.getFileName().toString().contains("passwd")));
        Check.equal("while the client's name is kept as a label, and only that",
                "passwd", upload.suggested());

        var extension = Uploads.into(request(upload("holiday.JPEG", "bytes"), "X", 0), directory)
                .file("file").orElseThrow();
        Check.that("an ordinary extension is kept, rebuilt rather than taken",
                extension.stored().getFileName().toString().endsWith(".jpeg"));
        var odd = Uploads.into(request(upload("thing.p h p", "bytes"), "X", 0), directory)
                .file("file").orElseThrow();
        Check.that("and one that is not plainly an extension is dropped",
                odd.stored().getFileName().toString().matches("[0-9a-f]{32}"));

        var big = "x".repeat(200_000);
        var large = Uploads.into(request(upload("big.bin", big), "X", 0), directory).file("file").orElseThrow();
        Check.equal("a file larger than any buffer arrives whole", 200_000L, large.size());
        Check.equal("byte for byte", big, Files.readString(large.stored()));

        var refused = Files.createTempDirectory("tuul-refused");
        refused.toFile().deleteOnExit();
        var counted = new Counting(bytes(upload("big.bin", big)));
        refuses("a file over the limit is refused",
                () -> Uploads.into(request(counted, "X"), refused, Limits.DEFAULT.part(1000)));
        Check.that("before the rest of it has been read — " + counted.read + " bytes of 200,000",
                counted.read < 100_000);
        Check.that("and nothing it half-wrote is left behind",
                Files.list(refused).findAny().isEmpty());

        refuses("a body over the total is refused as well",
                () -> Uploads.into(request(upload("big.bin", big), "X", 0), directory,
                        Limits.DEFAULT.total(5000)));
    }

    // --- the plumbing ------------------------------------------------------

    private static Request request(String method, String path, String name, String value) {
        return Request.of(method, path, Headers.of(name, value), Request.body(""));
    }

    private static Request form(String method, String path, String cookie, String body) {
        var headers = Headers.of("Content-Type", "application/x-www-form-urlencoded");
        return Request.of(method, path, cookie.isEmpty() ? headers : headers.with("Cookie", cookie),
                Request.body(body));
    }

    private static Multipart parse(String body, int drip) {
        return Multipart.of(request(body, "X", drip));
    }

    private static Request request(String body, String boundary, int drip) {
        return request(drip == 0 ? new ByteArrayInputStream(bytes(body)) : new Drip(bytes(body), drip), boundary);
    }

    private static Request request(InputStream body, String boundary) {
        return new Request("POST", "/upload", web.Parameters.NONE,
                Headers.of("Content-Type", "multipart/form-data; boundary=" + boundary), body, "",
                java.util.Map.of());
    }

    /// What a browser sends for a form with a file in it.
    private static String upload(String filename, String content) {
        return "--X\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\na note\r\n"
                + "--X\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n" + content + "\r\n--X--\r\n";
    }

    /// Reads every part, or answers with what went wrong — so a regression in
    /// the parser fails one check rather than throwing out of the whole run and
    /// taking every package tested after this one with it.
    private static List<String> attempted(Multipart multipart) {
        try {
            return read(multipart);
        } catch (Exception failure) {
            return List.of("failed: " + failure.getMessage());
        }
    }

    private static List<String> read(Multipart multipart) throws IOException {
        var parts = new java.util.ArrayList<String>();
        multipart.each(part -> parts.add(part.file()
                ? part.name() + "|" + part.filename().orElse("") + "|" + part.type()
                : part.name() + "|" + part.text()));
        return parts;
    }

    private static String named(String disposition) throws IOException {
        var body = "--X\r\nContent-Disposition: form-data; name=\"file\"; " + disposition + "\r\n\r\nx\r\n--X--\r\n";
        var multipart = parse(body, 0);
        return multipart.next().orElseThrow().filename().orElse("");
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

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void refuses(String what, Body body) {
        try {
            body.run();
            Check.that(what + " — nothing was refused", false);
        } catch (ControllerException expected) {
            Check.that(what, true);
        } catch (Exception wrong) {
            Check.that(what + " — refused with " + wrong, false);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    /// A stream that answers in small pieces, so a boundary lands across two
    /// reads and the reader has to cope.
    private static final class Drip extends InputStream {

        private final byte[] bytes;
        private final int most;
        private int at;

        private Drip(byte[] bytes, int most) {
            this.bytes = bytes;
            this.most = most;
        }

        @Override
        public int read() {
            return at < bytes.length ? bytes[at++] & 0xff : -1;
        }

        @Override
        public int read(byte[] into, int offset, int length) {
            if (at >= bytes.length) return -1;
            var count = Math.min(Math.min(most, length), bytes.length - at);
            System.arraycopy(bytes, at, into, offset, count);
            at += count;
            return count;
        }
    }

    /// A stream that remembers how much of it was wanted, which is how a test
    /// tells enforcement from inspection.
    private static final class Counting extends InputStream {

        private final byte[] bytes;
        private int at;
        private int read;

        private Counting(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            if (at >= bytes.length) return -1;
            read++;
            return bytes[at++] & 0xff;
        }

        @Override
        public int read(byte[] into, int offset, int length) {
            if (at >= bytes.length) return -1;
            var count = Math.min(length, bytes.length - at);
            System.arraycopy(bytes, at, into, offset, count);
            at += count;
            read += count;
            return count;
        }
    }
}
