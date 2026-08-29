/// An implementation of JSON-RPC 2.0, the remote call protocol specified at
/// <https://www.jsonrpc.org/specification>.
///
/// A caller sends a call that names a method. The peer runs that method and
/// sends back a response holding either a result or an error. This package
/// implements that exchange and stops there. It never opens a socket and it
/// never decides where one message ends and the next one starts. A [Transport]
/// does both, and you either write one or take one from
/// `jsonrpc2.transport`.
///
/// ## One document
///
/// A [Server] holds named methods. A [Method] takes the arguments as JSON and
/// returns JSON. [Server#handle(java.io.Reader, java.io.Writer)] reads one
/// document and writes the answer. That is one HTTP body, or one test.
///
/// ```
/// var server = Server.of()
///         .method("greet", params -> switch (params) {
///             case Json.Object arguments -> Json.of("hello, " + arguments.string("name", "world"));
///             case Json.Null _ -> Json.of("hello, world");
///             default -> throw Rejection.of(Failure.invalidParams("expected an object"));
///         });
/// server.handle(in, out);
/// ```
///
/// Nothing binds arguments to parameters for you. The `params` value arrives
/// as the caller wrote it: a `Json.Array` when the caller passed arguments by
/// position, a `Json.Object` when the caller named them, and [json.Json#NULL]
/// when the call carried no arguments at all. Each method reads what it needs,
/// because only the method knows the shape it wants.
///
/// Match on the shape. Do not cast to it. A cast that fails throws a
/// `ClassCastException`, the server reports that as -32603, and -32603 tells
/// the caller that your server broke. Arguments of the wrong shape are the
/// caller's mistake, and the code for that is -32602. Pattern matching is what
/// lets you answer with the right one.
///
/// ## A live connection
///
/// A [Conn] is both ends of the protocol on one transport. It sends calls, it
/// answers calls, and it matches each response to the call that asked for it.
/// There is no separate client type.
///
/// [jsonrpc2.transport.Pipe] joins two connections inside one process. The
/// tests use it. A test that binds a real port can fail because the port was
/// busy.
///
/// ```
/// var pipe = jsonrpc2.transport.Pipe.open();
/// try (var remote = Conn.of(pipe.right()).answering(server);
///         var client = Conn.of(pipe.left())) {
///     Thread.startVirtualThread(() -> {
///         try { remote.listen(); } catch (java.io.IOException ignored) {}
///     });
///     var greeting = client.call("greet", Json.Object.of().with("name", "ada"));
///     client.notify("log", Json.of("greeted ada"));
/// }
/// ```
///
/// [Conn#call(String, json.Json)] waits for the answer and returns the
/// result. When the peer answers with an error instead, `call` throws a
/// [Rejection] holding that [Failure], so the code you chose on the server
/// arrives at the caller as `rejection.failure().code()`.
///
/// [Conn#notify(String, json.Json)] sends a call with no id. A peer must
/// never answer a notification, so `notify` returns nothing and there is
/// nothing to wait for. A notification that fails fails silently, which is the
/// price of not waiting. A notify the peer did not register a method for is
/// also silent. Register the method on the receiving [Conn] if you need to
/// see it.
///
/// The receive loop never runs a method. It forks a virtual thread for every
/// inbound call. A [Handler] may therefore [Conn#call(String, json.Json)] or
/// [Conn#notify(String, json.Json)] before it returns, and it will not
/// deadlock. Handlers run at the same time. A method that needs order takes
/// its own lock.
///
/// ## Reporting an error
///
/// A method that returns a value gives the caller a result. A method that
/// throws gives the caller an error. Throw a [Rejection] to choose the code:
///
/// ```
/// .method("add", params -> {
///     if (params instanceof Json.Array(var items)
///             && items.size() == 2
///             && items.get(0) instanceof Json.Num(var left)
///             && items.get(1) instanceof Json.Num(var right)) {
///         return Json.of(left + right);
///     }
///     throw Rejection.of(Failure.invalidParams("expected two numbers"));
/// })
/// ```
///
/// One chain of patterns states every condition the method requires. A call
/// that fails any of them gets the same error. The bindings `items`, `left`
/// and `right` exist only inside the branch where their patterns matched, so
/// there is no way to read one that was never assigned.
///
/// Any other exception becomes -32603, an internal error. The server does not
/// copy the exception message into the response, because a caller has no use
/// for a stack trace and an exception message often says more about your
/// server than you want to publish.
///
/// [Failure] holds the five codes the protocol reserves and the
/// -32099 to -32000 range that a server may use for codes of its own.
/// [Failure#server(int, String)] refuses a code outside that range.
///
/// ## Batches
///
/// A caller can send several calls in one document. The answers come back in
/// the order the calls were made, whatever order the peer wrote them in.
///
/// ```
/// var answers = client.batch(List.of(
///         new Call.Request(client.next(), "greet", Json.Object.of().with("name", "ada")),
///         new Call.Request(client.next(), "greet", Json.Object.of().with("name", "grace")),
///         new Call.Notification("log", Json.of("two greetings"))));
/// ```
///
/// That returns two responses. Notifications are not answered, so they take no
/// place in the list. The members of a batch run at the same time, one virtual
/// thread for each, and the batch is finished only when every member is.
///
/// ## The types in this package
///
///   - [Conn] is the live session. [Server] is the method table and the
///     one-document fold. Most code needs only these.
///   - [Method] is what you write when the method only returns a value.
///     [Handler] is what you write when the method must talk on the [Conn]
///     before it returns. [Rejection] is how either picks its own error code.
///   - [Call], [Response], [Id] and [Failure] are the messages. [Incoming]
///     classifies one member as a call, a response, or neither. [Call] is
///     either a `Request` or a `Notification`. [Response] is either a `Result`
///     or a `Failed`. Neither can be both.
///   - [Transport] is what you supply to reach the outside world.
///   - [Jsonrpc2] reads and writes one document. Code that uses this package
///     rarely touches it.
///
/// ## Surprises worth knowing
///
/// These follow from the protocol rather than from this implementation, and
/// each one has caught somebody out:
///
///   - A notification is never answered, even when it fails. Send a
///     notification naming a method that does not exist and the peer stays
///     silent.
///   - A batch of nothing but notifications produces no document at all. The
///     peer does not answer with an empty array. It answers with nothing,
///     and the transport sends nothing.
///   - An empty batch, the document `[]`, is an invalid request. The answer to
///     it is a single object rather than an array of one.
///   - Ids carry their type. The id `"1"` and the id `1` are different ids,
///     and [Id] keeps text, number and absent apart so that a caller cannot
///     confuse two calls.
///   - A number id survives the round trip as a whole number while it stays
///     below 1e15. Above that, [json.JsonWriter] writes the double form.
///   - A timeout on [Conn#call(String, json.Json, java.time.Duration)] is
///     local. It is not a JSON-RPC error. A late answer increments
///     [Conn#fenced] and is dropped.
///   - A tight [Conn#notify(String, json.Json)] loop delays every other
///     document in that direction, including a result. Yield.
///
/// ## Writing a transport
///
/// A real transport is a socket, a pipe, a `Content-Length` header, or an HTTP
/// body. Implement [Transport] and keep its three rules. Read
/// [jsonrpc2.transport.Pipe] first. It is under a hundred lines and it keeps
/// them.
package jsonrpc2;
