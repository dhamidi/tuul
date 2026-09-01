/// Reads and writes UTF-8 `text/event-stream` data.
///
/// Use [EventStream#parse] for a lazy reader-backed stream. Use
/// [EventStream#body] as an HTTP response body handler.
///
/// ```
/// var reader = new java.io.StringReader("data: hello\n\n");
/// try (var signals = EventStream.parse(reader)) {
///     signals.forEach(signal -> {
///         switch (signal) {
///             case Event event -> System.out.println(event.data());
///             case Retry retry -> System.out.println(retry.milliseconds());
///         }
///     });
/// }
/// ```
///
/// Parsing emits values in wire order and dispatches an event only at its
/// blank-line terminator. A partial final event is discarded. The returned
/// stream is lazy. Closing it closes the reader or response body that it owns.
/// [EventStream#body] always uses UTF-8 and does not validate `Content-Type`.
/// Writing is immediate and flushes the supplied writer after each signal.
package eventstream;
