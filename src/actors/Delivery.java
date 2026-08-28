package actors;

import application.Message;
import java.time.Instant;

/// One message on its way to one actor, with the routing that came with it.
///
/// ## What is logged and what is not
///
/// The `command` and the `at` timestamp are written to the log. The `from`,
/// `replyTo` and `deadline` fields are not. That split is deliberate and it is
/// the reason this type exists instead of a message with more fields.
///
/// A reply address belongs to a caller that is waiting right now. Writing it to
/// a log would fill the log with the addresses of processes that exited months
/// ago, and it would make two identical intents produce two different entries,
/// which ruins reading and comparing a log. Losing a reply address on replay is
/// also correct rather than unfortunate: the caller that was waiting died with
/// the process, so there is nothing left to answer.
///
/// A deadline is a property of one request's urgency, not of the intent. The
/// same command sent again next week deserves a new deadline, not the old one.
///
/// The timestamp is the exception, and it is logged for a reason that decides
/// how durable timers work. An update function may not read a clock, because a
/// clock makes replay produce a different state every time. The only "now" an
/// update may read is [#at()], the moment its own message arrived. Logging that
/// value makes an actor a deterministic function of its input, and it lets a
/// business deadline computed from it survive replay exactly.
///
/// @param command  the message the actor will handle
/// @param to       the actor it is addressed to
/// @param from     the actor that sent it, or null when it came from outside
/// @param replyTo  where an `actor.reply` should go, or null
/// @param deadline when this message stops being worth handling, or null
/// @param at       when it arrived, in epoch milliseconds
public record Delivery(Message command, Address to, Address from, Address replyTo, Instant deadline, long at) {

    /// A delivery with no routing beyond its destination, stamped now.
    public static Delivery of(Address to, Message command) {
        return new Delivery(command, to, null, null, null, System.currentTimeMillis());
    }

    /// A delivery replayed out of a log. It carries the recorded timestamp and
    /// no routing at all, because none of the routing was recorded.
    public static Delivery replayed(Address to, Message command, long at) {
        return new Delivery(command, to, null, null, null, at);
    }

    public Delivery from(Address from) {
        return new Delivery(command, to, from, replyTo, deadline, at);
    }

    public Delivery replyTo(Address replyTo) {
        return new Delivery(command, to, from, replyTo, deadline, at);
    }

    public Delivery deadline(Instant deadline) {
        return new Delivery(command, to, from, replyTo, deadline, at);
    }

    public Delivery to(Address to) {
        return new Delivery(command, to, from, replyTo, deadline, at);
    }

    /// Whether this message is a control message. Control messages live in the
    /// `actors.` namespace, they are never written to a log, and they are never
    /// replayed.
    public boolean control() {
        return command.type().startsWith("actors.");
    }

    /// Whether the deadline has already passed. A delivery in this state is
    /// dropped before it is logged, because the caller has stopped waiting and
    /// handling it would only add a command nobody wanted to the history.
    public boolean expired(Instant now) {
        return deadline != null && now.isAfter(deadline);
    }
}
