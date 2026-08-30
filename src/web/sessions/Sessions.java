package web.sessions;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import json.Json;
import web.Cookie;
import web.Cookies;
import web.Handler;
import web.Middleware;
import web.Negotiate;
import web.Request;
import web.Response;
import web.Responses;
import web.Status;

/// Sessions in a signed cookie: read one, write one, insist on one.
///
/// This is the mechanics and not the policy. It does not know what a user is,
/// how a password is checked, or where either is kept — it knows how to carry a
/// small JSON object to a browser and recognise it coming back, which is the
/// part every application needs and nobody should write twice.
///
/// A session that fails to verify is treated exactly like one that was never
/// sent. There is no "invalid session" path for an application to handle,
/// because to a server there is no difference between a forged cookie and no
/// cookie, and pretending otherwise invites a handler to trust the difference.
public final class Sessions {

    /// Where the session is left for whoever handles the request.
    public static final String ATTRIBUTE = "web.session";

    private final Signature signature;
    private final String name;
    private final Duration age;
    private final boolean secure;
    private final String sameSite;
    private final Clock clock;

    private Sessions(Signature signature, String name, Duration age, boolean secure, String sameSite, Clock clock) {
        this.signature = signature;
        this.name = name;
        this.age = age;
        this.secure = secure;
        this.sameSite = sameSite;
        this.clock = clock;
    }

    public static Sessions of(Signature signature) {
        return new Sessions(signature, "session", Duration.ofDays(14), false, "Lax", Clock.systemUTC());
    }

    public static Sessions of(String secret) {
        return of(Signature.of(secret));
    }

    public Sessions named(String name) {
        return new Sessions(signature, name, age, secure, sameSite, clock);
    }

    public Sessions lasting(Duration age) {
        return new Sessions(signature, name, age, secure, sameSite, clock);
    }

    public Sessions secured(boolean secure) {
        return new Sessions(signature, name, age, secure, sameSite, clock);
    }

    public Sessions sameSite(String sameSite) {
        return new Sessions(signature, name, age, secure, sameSite, clock);
    }

    /// A clock of your choosing, so a test can prove a session expires without
    /// waiting two weeks for it.
    public Sessions clocked(Clock clock) {
        return new Sessions(signature, name, age, secure, sameSite, clock);
    }

    /// Reads the cookie and leaves whatever it found for the handlers inside.
    /// Always leaves something — [Session#NONE] when there was nothing — so a
    /// handler never has to know whether this middleware was installed.
    public Middleware middleware() {
        return next -> (request, response) -> next.handle(request.with(ATTRIBUTE, read(request)), response);
    }

    /// The session on this request, whether or not anything put one there.
    public static Session of(Request request) {
        return request.attribute(ATTRIBUTE, Session.class).orElse(Session.NONE);
    }

    /// Reads and verifies, without the middleware. Expired is the same as
    /// absent.
    public Session read(Request request) {
        return Cookies.first(request, name)
                .flatMap(signature::verify)
                .flatMap(this::parse)
                .filter(session -> !session.expired(clock.instant()))
                .orElse(Session.NONE);
    }

    /// Writes one, dated from now. The expiry travels inside the signature as
    /// well as in `Max-Age`, because the second is advice to a browser and the
    /// first is what this server will believe.
    public void write(Response response, Session session) {
        var expires = clock.instant().plus(age);
        var payload = Json.Object.of()
                .with("exp", expires.getEpochSecond())
                .with("data", session.values());
        var value = new StringWriter();
        try {
            payload.write(value);
        } catch (IOException impossible) {
            throw new IllegalStateException("a session that cannot be written: " + impossible.getMessage());
        }
        var cookie = Cookie.of(name, signature.sign(value.toString()))
                .lasting(age)
                .secured(secure)
                .sameSite(sameSite);
        Cookies.set(response, cookie);
    }

    public void clear(Response response) {
        Cookies.clear(response, name, "/");
    }

    /// Refuses a request that has no session.
    ///
    /// The answer is a redirect for a browser and a 401 for anything else,
    /// decided by what the request said it would accept — sending a sign-in page
    /// to a fetch call is how an application ends up with HTML in a JSON parser.
    public Middleware required(String location) {
        return required((request, response) -> {
            if (Negotiate.accepts(request, Negotiate.HTML) && !location.isEmpty()) {
                Responses.redirect(location, Status.SEE_OTHER, response);
                return;
            }
            Responses.empty(Status.UNAUTHORIZED, response);
        });
    }

    /// Refuses a request that has no session, in whatever way you like.
    public Middleware required(Handler otherwise) {
        return next -> (request, response) -> {
            var session = read(request);
            if (!session.present()) {
                otherwise.handle(request, response);
                return;
            }
            next.handle(request.with(ATTRIBUTE, session), response);
        };
    }

    private Optional<Session> parse(String payload) {
        if (!(Json.parse(new StringReader(payload)) instanceof Json.Object object)) return Optional.empty();
        if (!(object.get("exp") instanceof Json.Num(var seconds))) return Optional.empty();
        if (!(object.get("data") instanceof Json.Object values)) return Optional.empty();
        return Optional.of(new Session(values, Instant.ofEpochSecond((long) seconds)));
    }
}
