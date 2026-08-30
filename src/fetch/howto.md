# How-to

Each section is one task. Follow the steps in order. API facts are in
[reference.md](reference.md).

## Build a browser session

1. Create one `Fetch` for the browser process with a redirect limit:
   `var fetch = Fetch.flow(Fetch.options().maxRedirects(10))`.
2. Create one session: `var browser = fetch.session()`.
3. Set a user agent and browser redirects before the first request.
4. Keep the session while the user navigates.
5. Send each navigation with `browser.get(uri).send()`.
6. Read the final URI, response status, headers, redirect history, and body.
7. Close each response after the browser consumes or cancels its body.
8. Close the session and fetch when the browser exits.

The session keeps cookies across navigation. The fetch client keeps persistent
connections across sessions. The browser owns history, a cache, a DOM, and
HTML parsing. `fetch` does not own those concerns.

## Follow redirects with browser rules

1. Configure `session.redirects(Redirects.BROWSER)` before the first request.
2. Set a finite redirect limit when creating `Fetch`, for example
   `Fetch.flow(Fetch.options().maxRedirects(10))`.
3. Use `response.uri()` for the final URI.
4. Use `response.history()` for the response metadata of earlier hops.
5. Treat a redirect response as a normal response when the policy refuses it.

The browser policy changes POST to GET for 301, 302, and 303. It preserves the
method and body for 307 and 308 when the body is repeatable. It strips
authorization and request-specific headers when a redirect changes origin.
It does not follow a redirect from HTTPS to HTTP.

## Submit a form

1. Build the logical form as `Map<String, String[]>`, or use `String` for one
   value.
2. Use `Form.of(map)` when the source is a map.
3. Create a POST request with `session.request("POST", uri).form(form)`.
4. Send the request.
5. Close the response after the browser handles its result.

```java
var form = Form.of(Map.of(
        "q", "java http client",
        "tag", new String[] {"http", "client"}));
try (var response = browser.request("POST", searchUri).form(form).send()) {
    response.requireSuccess();
    // Hand response.body().publisher() to the HTML parser.
}
```

`Form` keeps both values for `tag`. The request sends two `tag` field pairs.
`Request.form` calls `Body.form` and adds
`application/x-www-form-urlencoded; charset=UTF-8`.

`Body.form(Map.of("q", "java http client"))` remains a convenient initializer
when every field has one value. Use `String[]` values when a field repeats.

## Decode text from its content type

1. Send the request and receive response headers.
2. Call `response.charset()` to read the selected character set.
3. Call `response.reader()` or `response.text()` for a small text body.
4. Use `response.body().reader(charset)` when the caller must override it.

The response reads the `charset` parameter from `Content-Type`. It uses UTF-8
when the header has no charset. `Content-Encoding` is a separate wire
encoding. A transport decoder must process it before a caller decodes text.

## Upload a large file

1. Create a `Body.file(path)` body.
2. Set the request content type.
3. Send the request with `PUT` or `POST`.
4. Consume or close the response.
5. Do not call `Files.readAllBytes(path)`.

```java
var request = session.request("PUT", uploadUri)
        .header("Content-Type", "application/octet-stream")
        .body(Body.file(path));

try (var response = request.send()) {
    response.requireSuccess();
}
```

`Body.file` opens the file when the request sends. It reads the file in
chunks and supplies its length when the file system provides it. It can open
the file again for a 307 or 308 redirect or for a caller retry. Keep the file
unchanged until the exchange ends.

Use `Body.publisher(source, length)` for a generated large body. The source
must honor demand and stop when the client cancels the subscription. Use
`sendAsync` with `Fetch.flow()` when the source and the request must stay
non-blocking.

## Download one Maven artifact

1. Create a `Fetch` with `Fetch.virtualThreads()`.
2. Create one session for the repository and enable browser redirects.
3. Resolve the artifact URI from its group, artifact, version, and classifier.
4. Send a GET request.
5. Call `requireSuccess()` before creating the final file.
6. Stream the body to a temporary file.
7. Verify the checksum if the repository metadata provides one.
8. Move the temporary file into the vendor directory.

```java
try (var fetch = Fetch.virtualThreads();
     var session = fetch.session().redirects(Redirects.BROWSER)) {
    var target = Path.of("vendor/slf4j-api-2.0.17.jar");
    var temporary = Files.createTempFile(target.getParent(), "slf4j-api-", ".part");
    try (var response = session.get(uri).send()) {
        response.requireSuccess();
        response.body().writeTo(temporary);
    }
    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
}
```

The client does not retry a request automatically. A caller can retry a GET.
The caller must not retry a one-shot request body unless it can create a new
body for every attempt.

## Download many artifacts at once

1. Create one virtual-thread fetch client.
2. Create one session for the repository.
3. Map each URI to `session.get(uri).sendAsync()`.
4. Keep every returned `CompletableFuture` until it completes.
5. For each response, check the status and stream the body.
6. Close every response.
7. Close the fetch client after all futures complete.

```java
try (var fetch = Fetch.virtualThreads();
     var session = fetch.session().redirects(Redirects.BROWSER)) {
    var responses = uris.stream()
            .map(uri -> session.get(uri).sendAsync())
            .toList();

    for (var future : responses) {
        try (var response = future.join()) {
            response.requireSuccess();
            response.body().writeTo(pathFor(response.uri()));
        }
    }
}
```

The connection pool limits connections per origin. HTTP/2 can carry several
requests on one connection. The execution strategy limits the threads that
run protocol work. The session does not create one executor per request.

## Consume a response with Flow

1. Request the response with `sendAsync()`.
2. Call `response.body().publisher()` once.
3. Subscribe a `Flow.Subscriber<ByteBuffer>`.
4. Request one or more buffers from `onSubscribe`.
5. Process each buffer before the next request.
6. Cancel the subscription when the parser stops.
7. Close the response after `onComplete` or `onError`.

```java
var future = browser.get(uri).sendAsync();
future.thenAccept(response -> {
    response.body().publisher().subscribe(new HtmlSubscriber(response));
});
```

The publisher applies back-pressure to the network read. A subscriber must not
retain a received `ByteBuffer` after `onNext` returns. Copy bytes that must
outlive that call.

## Use the current thread

1. Create `Fetch.sequential()`.
2. Send one request at a time with `send()`.
3. Consume or close the response before sending the next request.
4. Close the fetch client.

This mode creates no package-owned worker executor. It is useful for a small
command or a test. `sendAsync()` remains available for shared code, but this execution
strategy does not add request concurrency.

## Use an application-owned execution strategy

1. Create or obtain an `Execution`.
2. Pass it to `Fetch.of(execution)` at construction time.
3. Keep the execution alive while fetch calls run.
4. Close the fetch client before closing the execution.
5. Close the execution in the owner of that resource.

```java
try (var execution = MyExecution.open();
     var fetch = Fetch.of(execution);
     var session = fetch.session()) {
    // The application owns execution. Fetch only borrows it.
}
```

`Fetch.of` never creates a hidden executor. The convenience constructors create
and own their documented resources.

## Keep a persistent cookie jar

1. Create a `CookieJar` that the application owns.
2. Pass it to `fetch.session(jar)`.
3. Keep the session for the required user-agent lifetime.
4. Persist the jar from the application when the process exits.

`CookieJar.memory()` is concurrent and process-local. A persistent jar can
store the same cookie records in a database or a file. The client calls the
jar for each request and gives it every response header block.

## Cancel a request

1. Keep the `CompletableFuture` returned by `sendAsync()`.
2. Call `future.cancel(true)` when the caller no longer needs the response.
3. Close any response that completed before the cancellation.
4. Close the session when all calls from that user agent must stop.

Cancellation cancels the body subscription and releases the connection when
the transport can do so. Cancellation does not interrupt code that already
consumes a buffer.
