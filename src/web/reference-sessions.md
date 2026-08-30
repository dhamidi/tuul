# Reference: sessions and CSRF

This page is the factual reference for `web.sessions`. Use the
[tutorial](tutorial.md) to learn the flow, [howto.md](howto.md) for tasks,
[explanation.md](explanation.md) for the design, and [reference.md](reference.md)
for the web index.

## Signature

`Signature.of(byte[])` and `Signature.of(String)` create an HMAC-SHA256 signer.
The secret must contain at least 16 bytes. `sign(payload)` returns URL-safe
Base64 payload and tag text separated by a dot. `verify(signed)` returns the
payload only when the tag, Base64, and shape are valid. Missing, malformed,
null, and wrongly signed values all return `Optional.empty()`.

The signature authenticates data. It does not encrypt it. Keep the secret out
of source control and use a secret with enough entropy for production.

## Session

`Session` is a record of `Json.Object values` and `Instant expires`.
`Session.NONE` means no valid session. `present`, `expired`, `text`, `with`,
`without`, and `until` are its public operations. Values in a session cookie
are readable by the browser. Do not store passwords, bearer tokens, or other
secrets.

## Sessions policy

Create a policy with `Sessions.of(Signature)` or `Sessions.of(String)`. Defaults
are:

- cookie name `session`;
- max age 14 days;
- `SameSite=Lax`;
- `Secure=false`.

`named`, `lasting`, `secured`, `sameSite`, and `clocked` return changed policies.
`clocked` is useful in tests. `middleware()` reads the cookie and adds a
`Session` under `Sessions.ATTRIBUTE`, using `Session.NONE` when absent or
invalid. `Sessions.of(request)` reads that attribute and returns `NONE` when
the middleware did not run. `read(request)` verifies and parses the cookie
without middleware. Expired sessions are absent.

`write(response, session)` signs JSON containing the expiry and values, then
adds a cookie with `Path=/`, `HttpOnly`, the policy's `Secure` and `SameSite`
attributes, and the policy age as `Max-Age`. It computes a new expiry from the
policy clock and age; it does not preserve `session.expires()`. The computed
expiry is signed in the payload as well as represented by `Max-Age`.
`clear(response)` expires the policy cookie at `/`.

`required(location)` is middleware. It reads the session. If absent, it sends a
`303` redirect to `location` only when the request accepts HTML and the
location is not empty. Otherwise it sends an empty `401`. A present session is
added to the request before the next handler runs.

`required(otherwiseHandler)` lets the application choose the refusal response.
The handler receives the original request when no session exists and a request
with the verified session when it does.

This package does not define a user record, credential check, password hashing,
identity provider, database, or session store. The application owns those
decisions.

## CSRF policy

Create a policy with `Csrf.of(Signature)` or `Csrf.of(String)`. Defaults are:

- cookie name `csrf`;
- `Secure=false`;
- one-megabyte normal form-body read limit.

`named`, `secured`, and `reading` return changed policies. `Csrf.middleware()`
issues a random token in a signed cookie when the request has no valid token
cookie. The cookie uses `Path=/`, `HttpOnly`, `SameSite=Lax`, no max age, and
the policy's `Secure` value. The middleware adds the token under
`Csrf.ATTRIBUTE`. `Csrf.token(request)` reads it, or returns an empty string if
the middleware did not run.

The token names are `Csrf.FIELD` (`_csrf`) and `Csrf.HEADER`
(`X-CSRF-Token`). Safe methods are `GET`, `HEAD`, `OPTIONS`, and `TRACE`. Other
methods must send the token in the header, query, or a normal
`application/x-www-form-urlencoded` body field. A missing or unequal token
writes `403` text. A normal form larger than the configured limit writes `413`.

The middleware restores an ordinary form body after reading it. It does not
buffer multipart data to find `_csrf`; a multipart request must send the token
in the header or query string. The CSRF cookie may be issued even when the
request is rejected, so the next page can use it.

## Secure deployment checklist

1. Use a secret of at least 16 bytes and keep it outside source control.
2. Set both `sessions.secured(true)` and `csrf.secured(true)` behind HTTPS.
3. Keep `SameSite=Lax` unless a tested requirement needs another policy.
4. Use `SameSite=None` only with `Secure=true` and a deliberate cross-site
   design.
5. Treat sessions as signed, readable data. Store only small identifiers and
   non-secret claims.
6. Keep `required` in front of handlers that need authentication and keep CSRF
   middleware around all unsafe state-changing routes.
