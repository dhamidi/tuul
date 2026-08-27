package web.controllers;

import java.time.Duration;
import java.util.Optional;

/// A cookie on its way out.
///
/// The attributes are the security ones, and they default to the safe answer
/// rather than the permissive one: a cookie is `HttpOnly` so a script cannot
/// read it, `SameSite=Lax` so it does not travel on a cross-site POST, and
/// scoped to the whole site because that is what a session is for. Every one of
/// those is a decision that is invisible when it is right and a vulnerability
/// when it is wrong, which is why none of them is left to a caller who has not
/// asked.
///
/// `Secure` is the exception and is off by default, because a cookie marked
/// secure never arrives over the plain HTTP that a developer is running on. Set
/// it in production, where ARCHITECTURE.md says there is a proxy terminating
/// TLS.
public record Cookie(
        String name,
        String value,
        String path,
        Optional<Duration> age,
        boolean httpOnly,
        boolean secure,
        String sameSite) {

    public Cookie {
        refuse(name, "name");
        refuse(value, "value");
    }

    public static Cookie of(String name, String value) {
        return new Cookie(name, value, "/", Optional.empty(), true, false, "Lax");
    }

    public Cookie lasting(Duration age) {
        return new Cookie(name, value, path, Optional.of(age), httpOnly, secure, sameSite);
    }

    public Cookie at(String path) {
        return new Cookie(name, value, path, age, httpOnly, secure, sameSite);
    }

    public Cookie secured(boolean secure) {
        return new Cookie(name, value, path, age, httpOnly, secure, sameSite);
    }

    public Cookie sameSite(String sameSite) {
        return new Cookie(name, value, path, age, httpOnly, secure, sameSite);
    }

    public Cookie readableByScript() {
        return new Cookie(name, value, path, age, false, secure, sameSite);
    }

    /// The value of one `Set-Cookie` header.
    public String header() {
        var header = new StringBuilder(name).append('=').append(value).append("; Path=").append(path);
        age.ifPresent(duration -> header.append("; Max-Age=").append(duration.toSeconds()));
        if (httpOnly) header.append("; HttpOnly");
        if (secure) header.append("; Secure");
        if (!sameSite.isEmpty()) header.append("; SameSite=").append(sameSite);
        return header.toString();
    }

    /// A cookie whose name or value could end the header it is written into is
    /// not a cookie, it is a way to write any header at all. Refusing is the
    /// only answer: escaping would change what the caller asked for, and
    /// truncating would store something they did not.
    private static void refuse(String text, String what) {
        for (var character : text.toCharArray()) {
            if (character < 0x21 || character > 0x7e || character == ';' || character == ',' || character == '"') {
                throw new ControllerException("a cookie " + what + " cannot contain " + describe(character)
                        + " — encode it first");
            }
        }
    }

    private static String describe(char character) {
        return switch (character) {
            case '\r' -> "a carriage return";
            case '\n' -> "a newline";
            case ' ' -> "a space";
            default -> character < 0x21 || character > 0x7e ? "a control character" : "'" + character + "'";
        };
    }
}
