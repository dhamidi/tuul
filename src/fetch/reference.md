# Reference

This document is the contract for `fetch`. A caller uses this document to
choose a type or method.

## Glossary

| Term | Meaning |
|---|---|
| fetch client | A `Fetch` object. It owns a connection pool and an execution strategy. |
| execution | The `Execution` that runs transport actions and callbacks. |
| session | A `Session` object. It holds cookies and default request settings. |
| request | An immutable `Request` bound to a session. |
| response | A `Response` with status, headers, and a one-shot body. |
| body | A `Body` that supplies request bytes or consumes response bytes. |
| origin | The URI scheme, host, and effective port. |
| connection pool | Persistent connections owned by one `Fetch`. |
| hop | One response in a redirect chain. |
| repeatable | A body can create the same bytes for another request. |
| back-pressure | A subscriber controls how many body chunks the client sends. |
| transport failure | A failure before the client receives a complete response. |
| HTTP result | A response status, including 4xx and 5xx statuses. |

## Public types

The package exposes these types:

- `Fetch` owns transport resources and creates sessions.
- `Execution` selects where concurrent transport work runs.
- `Session` holds cookies and defaults for one user agent.
- `Request` describes one outbound exchange.
- `Response` describes one inbound exchange.
- `Body` represents a stream of bytes.
- `Form` represents repeated URL-encoded fields.
- `Headers` represents case-insensitive, repeated headers.
- `CookieJar` stores session cookies.
- `Redirects` names redirect policies.
- `FetchException` reports transport failure.
- `HttpException` reports an HTTP status rejected by `requireSuccess()`.

## `Execution`

`Execution` is the concurrency resource supplied to `Fetch`.

```java
public interface Execution extends AutoCloseable {
    void execute(Runnable action);

    @Override
    void close();

    static Execution of(Executor executor);
    static Execution currentThread();
    static Execution flow();
    static Execution virtualThreads();
}
```

`execute` schedules transport state changes, timers, completion actions, and
body callbacks. An execution must not run one action at the same time as
another action when it promises single-threaded execution.

| Factory | Contract |
|---|---|
| `currentThread()` | Runs work on the caller thread. It adds no worker executor and supports one active exchange. |
| `flow()` | Runs work on one Flow-based event loop. It supports concurrent exchanges and body back-pressure. |
| `virtualThreads()` | Runs blocking exchange work on virtual threads. It supports many concurrent exchanges. |

`of(Executor)` adapts an application-owned JDK executor. The adapter borrows
the executor and does not close it.

The factory methods return owned resources. The caller closes them. A
`Fetch.of(Execution)` call borrows the supplied execution and does not close
it.

An application can implement `Execution` around its own event loop. The
application must provide that resource before it constructs `Fetch`.

Do not call blocking `send`, `writeTo`, `reader`, `text`, or `bytes` from a
Flow event-loop action. Use `sendAsync` and a non-blocking body subscriber on
that loop. A caller on another thread may use the blocking methods while the
Flow loop runs the exchange.

## `Fetch`

`Fetch` is the transport boundary.

```java
public final class Fetch implements AutoCloseable {
    public static Fetch sequential();
    public static Fetch sequential(Options options);
    public static Fetch flow();
    public static Fetch flow(Options options);
    public static Fetch virtualThreads();
    public static Fetch virtualThreads(Options options);
    public static Options options();

    public static Fetch of(Execution execution);
    public static Fetch of(Execution execution, Options options);

    public Session session();
    public Session session(CookieJar cookies);
    public void close();
}
```

The convenience factories create the corresponding execution and own it.
Each convenience factory has an overload that accepts `Options`. `of` accepts
application-owned resources. `Fetch.close()` stops new requests, cancels
active calls, waits for owned execution work to stop, closes response bodies,
and closes owned resources.

One `Fetch` owns one connection pool. The pool is shared by all sessions from
that client. It reuses HTTP/1.1 keep-alive connections and multiplexes HTTP/2
streams when the peer and TLS negotiation allow HTTP/2. The pool keys a
connection by origin and transport settings. A `Session` never owns a socket.

### `Fetch.Options`

`Options` is a construction-time builder. Its defaults are suitable for a
small client.

```java
var options = Fetch.options()
        .connectTimeout(Duration.ofSeconds(10))
        .maxConnectionsPerOrigin(32)
        .maxRedirects(10)
        .proxy(ProxySelector.getDefault())
        .sslContext(SSLContext.getDefault());

try (var execution = Execution.virtualThreads();
     var fetch = Fetch.of(execution, options)) {
    // use fetch
}
```

The caller declares `execution` before `fetch` so try-with-resources closes the
fetch client before it closes the execution.

```java
public static final class Options {
    public Options connectTimeout(Duration timeout);
    public Options maxConnectionsPerOrigin(int maximum);
    public Options maxRedirects(int maximum);
    public Options proxy(ProxySelector selector);
    public Options sslContext(SSLContext context);
}
```

| Member | Contract |
|---|---|
| `options()` | Return an options builder with the package defaults. |
| `connectTimeout(Duration)` | Set the limit for opening a connection. The duration must be positive. |
| `maxConnectionsPerOrigin(int)` | Bound HTTP/1.1 connections for one origin. The value must be positive. HTTP/2 streams can share one connection. |
| `maxRedirects(int)` | Bound automatic redirect hops. The value must not be negative. |
| `proxy(ProxySelector)` | Set the JDK proxy selector. |
| `sslContext(SSLContext)` | Set the TLS context for HTTPS connections. |

Options do not contain cookies or request headers. Those belong to a session.
Options do not contain an executor. The execution is a separate required
construction argument.

## `Session`

```java
public final class Session implements AutoCloseable {
    public Session header(String name, String value);
    public Session headers(Headers defaults);
    public Session redirects(Redirects policy);
    public Session cookies(CookieJar jar);

    public Request request(String method, URI uri);
    public Request get(URI uri);
    public Request head(URI uri);
    public Request post(URI uri, Body body);

    public void close();
}
```

The default session has no default headers, `Redirects.NEVER`, and an
in-memory cookie jar. `get` and `head` use an empty body. `post` uses the
provided body.

Configuration methods return the same session. Call them before the first
request. After the first request, configuration is fixed and a configuration
method throws `IllegalStateException`. A configured session is safe for
concurrent requests. The cookie jar remains synchronized while requests run.

The session adds its default headers first. Request headers replace a default
with the same name. The session adds cookies from its jar unless the request
already has a `Cookie` header. The session gives every response header block,
including redirect hops, to the jar before it continues.

Closing a session rejects new requests and cancels its active requests. It
does not close the `Fetch` connection pool.

## `Request`

`Request` is immutable. Each modifier returns a new request.

```java
public final class Request {
    public String method();
    public URI uri();
    public Headers headers();
    public Body body();

    public Request header(String name, String value);
    public Request headers(Headers headers);
    public Request body(Body body);
    public Request text(String value);
    public Request text(String value, Charset charset);
    public Request timeout(Duration timeout);
    public Request form(Form form);
    public Request form(Map<String, ?> fields);

    public Response send() throws IOException, InterruptedException;
    public CompletableFuture<Response> sendAsync();
}
```

Header names and values cannot contain a CR, LF, or NUL. The method is upper
cased. The URI must be absolute and must use HTTP or HTTPS.

`text(value, charset)` sets the body and sets `Content-Type` to
`text/plain; charset=<charset>`. `text(value)` uses UTF-8.

`form(form)` sets the body to `Body.form(form)` and sets the content type to
`application/x-www-form-urlencoded; charset=UTF-8`. The map overload first
calls `Form.of(fields)`. A later `header` call can replace that content type.

`send` waits for response headers and is interruptible. `sendAsync` returns a
`CompletableFuture` that completes when response headers arrive. It does not
wait for the response body. The caller must consume or close the body.

A request with a repeatable body can be sent again. A request with a
publisher body can be sent once. A redirect that needs to resend a one-shot
body stops at the redirect response.

## `Form`

`Form` is the logical form model. One key can have several values. The wire
format writes one field pair for each value.

```java
public final class Form {
    public Form(Map<String, String[]> fields);
    public static Form of(Map<String, ?> fields);
    public static Form of(String name, String... values);

    public Form with(String name, String... values);
    public Map<String, String[]> fields();
}
```

The constructor copies the map and every array. `fields()` returns a deep copy.
The map must contain non-null `String` keys. Each array must be non-null and
must contain non-null strings.

`Form.of(Map<String, ?>)` is the convenient map initializer. A `String` value
becomes one value. A `String[]` value becomes the values for one key. Any other
value type is an error. An empty array writes no pair. An empty string writes
`name=`.

`Body.form(form)` encodes the fields as
`application/x-www-form-urlencoded`. It encodes each key and value as UTF-8
bytes. It leaves ASCII letters, digits, `-`, `.`, `_`, and `*` unchanged. It
uses `+` for spaces and percent-encodes every other byte with uppercase hex.
A literal `+` becomes `%2B`. Array order is wire order. Map order is wire key
order.

`Body.form` creates only the encoded bytes. It does not change request
headers. `Request.form` creates the same body and adds the matching
`Content-Type` header.

## `Body`

`Body` is a one-shot byte source or byte stream.

```java
public interface Body {
    static Body empty();
    static Body text(String value);
    static Body text(String value, Charset charset);
    static Body bytes(byte[] value);
    static Body file(Path path);
    static Body form(Form form);
    static Body form(Map<String, ?> fields);
    static Body publisher(Flow.Publisher<ByteBuffer> source,
            OptionalLong length);

    boolean repeatable();
    OptionalLong length();
    Flow.Publisher<ByteBuffer> publisher();
    void writeTo(OutputStream out) throws IOException;
    void writeTo(Path path) throws IOException;
    Reader reader(Charset charset) throws IOException;
    String text(Charset charset) throws IOException;
    byte[] bytes() throws IOException;
}
```

`empty`, `text`, `bytes`, `file`, and `form` create repeatable bodies.
`publisher` creates a one-shot body unless the source itself can be replayed.
`length()` is empty when the client must use chunked HTTP/1.1 transfer or an
HTTP/2 data stream.

`file(path)` is the large-upload API. It opens the file when the request sends
and reads it through a stream. It does not call `readAllBytes`. Its length is
the file size when the client can read that size. A repeatable file body opens
a new file stream for a redirect or a caller retry. The caller must not change
the file during the exchange.

`publisher(source, length)` is the API for a generated or externally supplied
large upload. The client requests data from the publisher as the connection
accepts it. The publisher must honor demand, signal errors, and release its
source when the subscription is cancelled. The caller must provide a new
publisher for each retry unless the source is repeatable.

The response body is one-shot. Only one of `publisher`, `writeTo`, `reader`,
`text`, or `bytes` can consume it. `writeTo(OutputStream)` leaves `out` open.
`writeTo(Path)` creates or truncates the file and closes its file stream.

`text` and `bytes` are for bodies known to be small. A caller must use a
publisher or a file for an unbounded or large response.

Each publisher buffer is read-only. The client can reuse its storage after
`onNext` returns. A subscriber must consume the buffer before it returns or
copy the bytes it needs to keep.

## `Response`

```java
public final class Response implements AutoCloseable {
    public int status();
    public URI uri();
    public Request request();
    public Headers headers();
    public Charset charset();
    public Reader reader() throws IOException;
    public String text() throws IOException;
    public Body body();
    public List<Hop> history();
    public boolean successful();
    public Response requireSuccess() throws HttpException;
    public void close();

    public record Hop(URI uri, int status, Headers headers) {}
}
```

`uri()` is the final URI after redirects. `history()` contains earlier hops in
wire order. A hop has no body because the client consumes or closes each
intermediate body before it follows the next location.

`charset()` reads the `charset` parameter from `Content-Type`. If the response
does not declare a charset, it returns UTF-8. `reader()` and `text()` use that
charset. `body().reader(charset)` and `body().text(charset)` are explicit
overrides. `reader()` closes the response body when the caller closes the
reader.

If `Content-Type` declares a charset that the JDK does not support,
`charset()` throws `UnsupportedCharsetException`. A malformed or missing
charset parameter uses UTF-8.

`Content-Type` selects character decoding. `Content-Encoding` selects a wire
content decoder. They are different headers. The transport decodes supported
content encodings before it exposes the response body. It fails with
`FetchException` for an encoding it cannot decode. `charset()` never treats a
content encoding as a character set.

`headers()` keeps the wire header values. If decoding changes the body length,
the wire `Content-Length` is not the decoded body length. Use the body stream
as the source of truth.

`successful()` is true for status codes from 200 through 299. `requireSuccess`
returns this response for a successful result. It throws `HttpException` for
any other status. It does not read the body. A caller must close the response
when the exception leaves its scope.

Closing a response is idempotent. It cancels an unread body. The transport may
reuse a connection after it drains the body. It closes the connection when it
cannot safely drain it.

## `Headers`

```java
public record Headers(Map<String, List<String>> values) {
    public static final Headers NONE;
    public static Headers of();
    public static Headers of(String name, String value);
    public Optional<String> first(String name);
    public String first(String name, String fallback);
    public List<String> all(String name);
    public boolean has(String name);
    public Set<String> names();
    public Headers with(String name, String value);
    public Headers add(String name, String value);
    public Headers without(String name);
}
```

Header names compare without regard to case. Values retain their order.
`with` replaces all values under a name. `add` appends one value.
`Headers` is immutable. The record copies its input map and lists.

## `CookieJar`

```java
public interface CookieJar {
    static CookieJar memory();
    static CookieJar none();
    static CookieJar of(CookieStore store);

    Headers request(URI uri);
    void response(URI uri, Headers headers);
}
```

`memory()` uses a thread-safe in-memory store. It follows cookie domain, path,
secure, expiry, and `Set-Cookie` rules. `none()` never sends or stores a
cookie. `of` adapts a JDK `CookieStore`.

`request` returns either an empty header set or a `Cookie` header. `response`
reads every `Set-Cookie` value. A custom jar can persist cookies, apply a
browser privacy policy, or record them for a test.

## `Redirects`

```java
public enum Redirects {
    NEVER,
    SAME_ORIGIN,
    BROWSER
}
```

`NEVER` returns the redirect response. `SAME_ORIGIN` follows redirects that
stay on the same origin. `BROWSER` follows cross-origin redirects, follows an
HTTPS upgrade, and refuses an HTTPS downgrade. The fetch option
`maxRedirects` bounds all policies.

The policy processes 301, 302, 303, 307, and 308. `BROWSER` changes POST to
GET for 301, 302, and 303. It preserves the method and body for 307 and 308
only when the body is repeatable. It strips authorization and request-specific
headers when the origin changes. Each hop updates the cookie jar.

## Errors

`send()` declares `IOException` for a transport failure and
`InterruptedException` when the caller is interrupted. `sendAsync()` completes
exceptionally with the same failure wrapped in `FetchException` when the
transport has no HTTP response.

```java
public final class FetchException extends IOException {
    public URI uri();
    public Phase phase();
    public boolean retryable();

    public enum Phase { CONNECT, SEND, RECEIVE, CLOSE }
}

public final class HttpException extends IOException {
    public int status();
    public URI uri();
    public Headers headers();
}
```

`FetchException` extends `IOException` and contains the request URI, a phase,
and a `retryable` flag. The phases are `CONNECT`, `SEND`, `RECEIVE`, and
`CLOSE`. The cause contains the JDK failure.

`HttpException` extends `IOException` and contains the rejected status, final
URI, and response headers. It represents a server result, not a transport
failure. `requireSuccess` never hides the status as a generic I/O error.

Closing `Fetch` or `Session` makes later sends fail with
`IllegalStateException`. Cancelling a future produces
`CancellationException` for the caller that joins it.

## Protocol boundaries

The client sends HTTP/1.1 and HTTP/2 over JDK TLS. It does not implement
HTTP/3. Unless a request sets `Accept-Encoding`, it advertises and decodes
`gzip` and `deflate`. A request can set `Accept-Encoding: identity` to disable
compression. It does not advertise Brotli. `Response.headers()` keeps the
wire `Content-Encoding` and `Content-Length` values.

The client does not retry requests automatically. It does not cache responses.
It does not parse HTML, JSON, XML, or Maven metadata. Those layers consume the
stream exposed by `Body`.
