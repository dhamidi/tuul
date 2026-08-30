# Tutorial: download one artifact

This tutorial creates a client, opens a session, sends a GET request, checks
the HTTP result, and streams the response to a file.

You need JDK 27. You do not need another HTTP library.

## Create the client

`Fetch.virtualThreads()` creates a client with an execution strategy for many
requests at the same time. The client owns the virtual-thread executor and
its connection pool.

```java
var fetch = Fetch.virtualThreads();
```

Use try-with-resources. Closing `fetch` waits for its calls and closes its
transport resources.

## Open a session

A session is the state of one user agent. It holds cookies and default request
headers. A session does not own a connection pool.

```java
var session = fetch.session()
        .header("User-Agent", "tuul-fetch/1")
        .redirects(Redirects.BROWSER);
```

The default session has an in-memory cookie jar. The browser redirect policy
follows normal browser redirects. Configure a session before its first
request. After the first request, its configuration is fixed. The session is
then safe to use from concurrent calls.

## Send a request

`session.get(uri)` creates an immutable request bound to the session.
`send()` waits for response headers and returns a response.

```java
var uri = URI.create(
        "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar");
var response = session.get(uri).send();
```

The response does not throw because the server returned 404 or 500. Those are
HTTP results. Inspect them or call `requireSuccess()`.

## Stream the body

The body is one-shot. `writeTo(Path)` opens and closes the file. It does not
load the response into a byte array.

```java
try (fetch; session; response) {
    response.requireSuccess();
    response.body().writeTo(Path.of("slf4j-api-2.0.17.jar"));
}
```

The response must close even when the body is not read to its end. Closing a
response cancels the body. The client can then discard the remaining bytes or
close the socket, according to the transport state.

## Read a small response as text

Use `Response.text()` when the body is known to be small. It reads the
character set from `Content-Type` and consumes the body.

```java
try (var fetch = Fetch.sequential(); var session = fetch.session()) {
    try (var response = session.get(URI.create("https://example.com/")).send()) {
        response.requireSuccess();
        var html = response.text();
        System.out.println(html);
    }
}
```

Use `response.body().text(StandardCharsets.UTF_8)` when the caller must select
the character set itself. Use `response.charset()` to inspect the selected
character set without consuming the body.

For a large HTML document, pass `response.body().publisher()` to an
incremental parser. For a file, use `writeTo(Path)`.

## What happened

1. `Fetch.virtualThreads()` created the execution resources and the transport.
2. `fetch.session()` created a cookie jar and a session.
3. `session.get(uri)` built a GET request with no body.
4. `send()` ran the request through the shared connection pool.
5. The session accepted `Set-Cookie` headers before `send()` returned.
6. `body().writeTo(path)` requested each byte chunk and wrote it to the file.
7. Closing the response released or closed the persistent connection.

## Next

- [howto.md](howto.md) shows concurrent Maven downloads, form submission,
  custom execution, and Flow body consumption.
- [reference.md](reference.md) lists every public type and rule.
- [explanation.md](explanation.md) explains why sessions do not own sockets
  and why bodies are one-shot.
