/// An outbound HTTP client with sessions, persistent connections, and
/// stream-first bodies.
///
/// [Fetch] owns a connection pool and one [Execution]. [Session] owns cookies
/// and request defaults. A [Request] produces a [Response]. The response body
/// is a one-shot [Body], so a caller can write it to a file or consume it as a
/// `Flow.Publisher<ByteBuffer>` without first creating a byte array.
/// [Form] keeps repeated form fields as `String[]` values before encoding.
/// `Request.text` and `Request.form` set their content types. `Response.text`
/// selects its character set from the response `Content-Type`.
/// [Request#eventStream] writes event stream signals as UTF-8.
/// [Response#eventStream()] parses event stream signals lazily.
///
/// ```
/// import java.net.URI;
/// import java.nio.file.Path;
///
/// try (var fetch = Fetch.virtualThreads(); var session = fetch.session()) {
///     try (var response = session.get(URI.create("https://example.com/")).send()) {
///         response.requireSuccess();
///         response.body().writeTo(Path.of("example.html"));
///     }
/// }
/// ```
///
/// Choose [Fetch#sequential()] for one synchronous request on the current
/// thread. Choose [Fetch#flow()] for a Flow-based event loop. Choose
/// [Fetch#virtualThreads()] for many blocking requests. Pass an application
/// owned [Execution] to [Fetch#of(Execution)] when the application must own
/// the concurrency resources.
///
/// The connection pool belongs to [Fetch], so sessions can share persistent
/// connections without sharing cookies. Close every [Response] after the
/// caller consumes or cancels its body. Close [Session] when its requests are
/// no longer needed. Close [Fetch] after its sessions and requests finish.
package fetch;
