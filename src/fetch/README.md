# fetch

`fetch` is the outbound HTTP client for tuul. It uses only the JDK.

The package has one front door, `Fetch`. A `Fetch` owns an HTTP connection pool
and one execution strategy. A `Session` holds cookies and request defaults. A
`Request` describes one exchange. A `Response` exposes status, headers and a
one-shot body.

The connection pool belongs to `Fetch`, not to `Session`. Several sessions can
share persistent HTTP/1.1 connections and HTTP/2 connections. Session state
does not leak between sessions.

Concurrency is selected when the caller creates `Fetch`:

```java
try (var fetch = Fetch.virtualThreads();
     var session = fetch.session()) {
    try (var response = session.get(URI.create("https://example.com/")).send()) {
        response.requireSuccess();
        response.body().writeTo(Path.of("example.html"));
    }
}
```

Use `Fetch.sequential()` for one synchronous exchange on the current thread.
Use `Fetch.flow()` for a single event loop with back-pressure. Use
`Fetch.virtualThreads()` for many blocking exchanges at the same time. Use
`Fetch.of(Execution)` when the application already owns its execution
resources.

The body is a stream. `Body.file(Path)` uploads a large file without holding
it in memory. `Body.writeTo(Path)` downloads without holding the response in
memory. `Body.publisher()` exposes the same bytes as a
`Flow.Publisher<ByteBuffer>` for an incremental parser. `Response.text()` uses
the response `Content-Type` charset. `Body.text(Charset)` is an explicit
override for small bodies.

`Form` keeps repeated fields as `String[]` values. `Body.form(Map<String, ?>)`
accepts a `String` or a `String[]` for each key as a convenient initializer.

## Documents

Each document answers one kind of question.

| You want | Read |
|---|---|
| To learn the client with a first download | [tutorial.md](tutorial.md) |
| To complete a browser or download task | [howto.md](howto.md) |
| A fact, a signature, or a protocol rule | [reference.md](reference.md) |
| To know why the design has these boundaries | [explanation.md](explanation.md) |

The API is intentionally small. Start with the tutorial. Use the reference
before you write an adapter or a transport integration.
