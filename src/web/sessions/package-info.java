/// Signed-cookie sessions and CSRF checks for [web] handlers.
///
/// [Signature] signs a value with HMAC-SHA256. [Sessions] stores a small JSON
/// object in a signed cookie. [Csrf] issues a token in a signed cookie. It
/// checks that an unsafe request sends the token back. After both checks, the
/// server can trust that the request came from a page it issued.
///
/// The package does not define a user model or a session store. The browser
/// carries the session value. The server verifies it on each request.
///
/// ## A first session
///
/// Create a session policy with a secret of at least 16 bytes. Wrap the handler
/// with its middleware. The middleware puts a [Session] on every request.
///
/// ```
/// var sessions = Sessions.of("a-secret-with-at-least-16-bytes");
/// web.Handler handler = (request, response) -> {
///     var session = Sessions.of(request);
///     if (session.present()) {
///         sessions.write(response, session);
///     }
/// };
/// var application = handler.wrappedBy(sessions.middleware());
/// ```
///
/// A missing, expired, malformed, or wrongly signed cookie becomes
/// [Session#NONE]. The handler can test [Session#present()] without handling a
/// null value.
///
/// Call [Sessions#required(String)] when a route needs a session. It redirects
/// an HTML request to the supplied location. It answers other requests with
/// 401.
///
/// ## Protecting form submissions
///
/// Create [Csrf] with the signing secret and wrap the application with
/// [Csrf#middleware()]. Render [Csrf#token(web.Request)] in each form or meta
/// tag. Send the token in the `X-CSRF-Token` header, the `_csrf` query value,
/// or the `_csrf` form field.
///
/// ```
/// var csrf = Csrf.of("a-secret-with-at-least-16-bytes");
/// var protectedApplication = application.wrappedBy(csrf.middleware());
/// ```
///
/// The middleware permits `GET`, `HEAD`, `OPTIONS`, and `TRACE` without a
/// token. It checks the token for other methods. A missing or wrong token
/// answers 403. A form body larger than the configured read limit answers 413.
/// A multipart request must send the token in a header or in the query because
/// the middleware does not buffer a multipart body to find a form field.
///
/// ## Defaults and configuration
///
/// [Sessions#of(String)] uses the cookie name `session`, an age of 14 days,
/// `SameSite=Lax`, and no `Secure` attribute. [Sessions#named(String)],
/// [Sessions#lasting(java.time.Duration)], [Sessions#sameSite(String)], and
/// [Sessions#secured(boolean)] return a changed policy without changing the
/// original.
///
/// [Csrf#of(String)] uses the cookie name `csrf`, no `Secure` attribute, and a
/// one-megabyte form-body read limit. [Csrf#named(String)],
/// [Csrf#secured(boolean)], and [Csrf#reading(long)] return a changed policy.
///
/// [Signature#verify(String)] returns an empty value for a wrong tag, missing
/// tag, malformed Base64, or a null input. It does not report which case
/// occurred.
///
/// ## The types in this package
///
///   - [Signature] signs and verifies payloads. Keep its secret outside source
///     control and use at least 16 bytes.
///   - [Session] holds JSON values and an expiry. [Session#NONE] means that no
///     valid session arrived.
///   - [Sessions] reads and writes sessions and supplies middleware.
///   - [Csrf] issues and checks tokens with middleware.
///
/// ## Surprises worth knowing
///
///   - **The cookie is not encrypted.** A client can read the JSON in a
///     session. Do not put passwords, tokens, or other secrets in its values.
///   - **The expiry is signed.** A client cannot extend a session by changing
///     its cookie or its `Max-Age` value.
///   - **The middleware writes a CSRF cookie before it checks a request.** A
///     rejected request can therefore receive the cookie that the next page
///     needs.
///   - **A form token consumes the request body.** The middleware restores an
///     ordinary form body before it calls the next handler. It does not read a
///     multipart body to find a token.
package web.sessions;
