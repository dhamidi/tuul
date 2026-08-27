package web.controllers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import web.Middleware;
import web.Parameters;
import web.Request;
import web.Response;
import web.Responses;
import web.Status;

/// Proof that a request came from a page this server sent.
///
/// A browser attaches cookies to a form submission whoever wrote the form — so
/// a page on another site can post to this one as a signed-in user, and the
/// only thing that distinguishes it is that it could not have read anything
/// this server rendered. So the check is: something the page was told, sent
/// back where a cross-site form cannot put it.
///
/// The token lives in a cookie and in the page. A cross-site attacker can cause
/// the cookie to be sent but cannot read it, so cannot put its value in their
/// form. The cookie is signed as well, which closes the one hole in that
/// argument: cookies are not isolated by origin, so a page on a sibling
/// subdomain can *write* one — but not one that verifies here.
///
/// Where the token may travel back: the `X-CSRF-Token` header, which is what
/// Turbo and every fetch call use, the query string, or a form field. Reading
/// the field means reading the body, so only an ordinary form body is read that
/// way, and a multipart upload has to use the header or the query — a limit
/// stated rather than a surprise, since the alternative is buffering an upload
/// to check a token.
public final class Csrf {

    /// Where the token for this request is left, for a page to render.
    public static final String ATTRIBUTE = "web.csrf";

    public static final String FIELD = "_csrf";

    public static final String HEADER = "X-CSRF-Token";

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final String FORM = "application/x-www-form-urlencoded";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Signature signature;
    private final String cookie;
    private final boolean secure;
    private final long bodyBytes;

    private Csrf(Signature signature, String cookie, boolean secure, long bodyBytes) {
        this.signature = signature;
        this.cookie = cookie;
        this.secure = secure;
        this.bodyBytes = bodyBytes;
    }

    public static Csrf of(Signature signature) {
        return new Csrf(signature, "csrf", false, 1024 * 1024);
    }

    public static Csrf of(String secret) {
        return of(Signature.of(secret));
    }

    public Csrf named(String cookie) {
        return new Csrf(signature, cookie, secure, bodyBytes);
    }

    public Csrf secured(boolean secure) {
        return new Csrf(signature, cookie, secure, bodyBytes);
    }

    /// How much of a form body will be read to find a token. A form larger than
    /// this is refused rather than read, because a check that can be made
    /// expensive by whoever is being checked is not a check.
    public Csrf reading(long bodyBytes) {
        return new Csrf(signature, cookie, secure, bodyBytes);
    }

    /// The token this request should send back, for a form or a meta tag.
    public static String token(Request request) {
        return request.attribute(ATTRIBUTE, String.class).orElse("");
    }

    /// Issues a token to anyone who has not got one, and refuses any request
    /// that changes something without sending it back.
    public Middleware middleware() {
        return next -> (request, response) -> {
            var known = Cookies.first(request, cookie).flatMap(signature::verify);
            var token = known.orElseGet(Csrf::mint);
            if (known.isEmpty()) issue(response, token);

            if (SAFE.contains(request.method())) {
                next.handle(request.with(ATTRIBUTE, token), response);
                return;
            }
            var offered = offered(request);
            if (!matches(token, offered.token())) {
                Responses.text("the form was not sent from a page this server issued", Status.FORBIDDEN, response);
                return;
            }
            next.handle(offered.request().with(ATTRIBUTE, token), response);
        };
    }

    private void issue(Response response, String token) {
        Cookies.set(response, Cookie.of(cookie, signature.sign(token)).secured(secure));
    }

    /// A request and the token it was carrying — a pair because finding the
    /// token may mean reading the body, and the request that goes on has to be
    /// one that still has it.
    private record Offered(Request request, String token) {}

    private Offered offered(Request request) throws IOException {
        var header = request.header(HEADER).orElse("");
        if (!header.isEmpty()) return new Offered(request, header);

        var query = request.query().first(FIELD, "");
        if (!query.isEmpty()) return new Offered(request, query);

        if (!request.type().equals(FORM)) return new Offered(request, "");
        var body = read(request);
        var restored = request.body(new ByteArrayInputStream(body));
        var form = Parameters.parse(new String(body, StandardCharsets.UTF_8));
        return new Offered(restored, form.first(FIELD, ""));
    }

    /// Reads the body, and refuses one larger than it agreed to read. Reading
    /// one byte past the limit is what tells the difference between a body that
    /// fits and one that claims to.
    private byte[] read(Request request) throws IOException {
        var body = request.body().readNBytes((int) Math.min(bodyBytes + 1, Integer.MAX_VALUE));
        if (body.length > bodyBytes) {
            throw new ControllerException("a form of more than " + bodyBytes + " bytes will not be read to find a "
                    + FIELD + " — send it in the " + HEADER + " header", 413);
        }
        return body;
    }

    private static boolean matches(String expected, String offered) {
        if (offered.isEmpty()) return false;
        return MessageDigest.isEqual(bytes(expected), bytes(offered));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String mint() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
