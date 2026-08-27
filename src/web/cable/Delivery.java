package web.cable;

import eventstream.Event;

/// One broadcast, on its way.
///
/// The sequence is the cable's, not the topic's: a client listening to several
/// topics has one position in one stream, which is the whole reason a page can
/// hold a single connection and hear about everything.
record Delivery(long sequence, String topic, Event event) {

    /// The event as the client sees it, carrying the id it will send back if it
    /// has to reconnect.
    Event addressed(String run) {
        return event.withId(run + "-" + sequence);
    }
}
