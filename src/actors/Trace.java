package actors;

import json.Json;

/// Something the runtime did, published as it happens.
///
/// A trace is an observation and never a command. Nothing in this package reads
/// traces or changes behaviour because of one, so a subscriber that is slow, or
/// absent, or broken cannot alter what the actors do.
///
/// ## Why this is a bus and not a recording
///
/// The obvious way to export these is to write JDK Flight Recorder events at
/// every site that produces one. That would make JFR the only way to see the
/// system, and a JFR repository is the wrong shape for anything interactive: it
/// flushes about once a second, it is read-only, and reading it means parsing a
/// recording rather than receiving an event.
///
/// So the events go on an in-process bus first, and JFR subscribes to it like
/// anything else. [Flight] is that subscriber. A live inspector is another one,
/// and it gets sub-millisecond events instead of a file that is written every
/// second.
///
/// @param at      when it happened, in epoch milliseconds
/// @param address which actor it concerns
/// @param kind    what happened
/// @param detail  whatever else is worth knowing, which differs by kind
public record Trace(long at, Address address, Kind kind, Json detail) {

    /// What happened.
    public enum Kind {

        /// An actor was loaded and its log replayed. The detail carries
        /// `commands` and `millis`, which is the measurement that degrades
        /// invisibly as a log grows.
        summoned,

        /// An actor's thread finished and it left the loaded map. The detail
        /// says whether it was `settled` and how many messages it `handled`.
        evicted,

        /// An actor died often enough to be quarantined. The detail carries the
        /// `reason` and the `restarts` count.
        quarantined,

        /// A message did not arrive. The detail carries the `cause` and the
        /// `type` of the message that was refused.
        undeliverable,

        /// The step gave up waiting for an effect and left the thread running.
        /// The detail carries the running `total` for this actor.
        abandoned,

        /// An effect finished after its step had ended and its message was
        /// thrown away. The detail carries the running `total`.
        fenced,

        /// One message was handled. This is off unless
        /// [System#tracingMessages(boolean)] turns it on, because an actor
        /// doing ten thousand messages a second would drown every other event.
        handled
    }

    static Trace of(Address address, Kind kind, Json detail) {
        return new Trace(java.lang.System.currentTimeMillis(), address, kind, detail);
    }

    /// This trace as JSON, which is what an inspector sends to a browser.
    public Json.Object json() {
        return Json.Object.of()
                .with("at", at)
                .with("address", address.toString())
                .with("kind", kind.name())
                .with("detail", detail);
    }
}
