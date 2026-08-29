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
/// An update never holds a delivery, and it does not need one: the timestamp is
/// a field of the message's envelope, so [application.Message#at()] answers it
/// and [#at()] here reads the same place. It used to be a field of this record
/// that [Actor] copied into the message before every update, because the
/// envelope did not exist and the message had nowhere to keep it.
///
/// ## Why this is still a type
///
/// Every field left here is metadata travelling beside a payload, which is what
/// an envelope is for, so this looks like the next wrapper to collapse — the
/// way `Log.Entry` collapsed once its sequence number, timestamp and command
/// were all things a message already carried.
///
/// It is the opposite case. `Log.Entry` held nothing that was not already an
/// envelope field, so it was a box around a message. This holds exactly what an
/// envelope must **not** carry. A message is the thing that gets written to a
/// log; a delivery is the routing that gets it there and then stops. Put
/// `replyTo` in the envelope and the reply address is inside the object the
/// journal writes, with nothing between it and the disk but the list of columns
/// that journal happens to have — see [Log#append(application.Message)] for why
/// that rule is written down rather than left to the schema.
///
/// So the split is the point of the type. Collapsing it would not remove a box;
/// it would remove the only thing keeping a caller's address out of a permanent
/// record of intent.
///
/// @param command  the message the actor will handle, stamped with when it
///                 arrived
/// @param to       the actor it is addressed to
/// @param from     the actor that sent it, or null when it came from outside
/// @param replyTo  where an `actor.reply` should go, or null
/// @param deadline when this message stops being worth handling, or null
public record Delivery(Message command, Address to, Address from, Address replyTo, Instant deadline) {

    /// A delivery with no routing beyond its destination, stamped now.
    public static Delivery of(Address to, Message command) {
        return new Delivery(command.at(System.currentTimeMillis()), to, null, null, null);
    }

    /// A delivery replayed out of a log. The command carries the recorded
    /// timestamp already, and no routing at all, because none of the routing
    /// was recorded.
    public static Delivery replayed(Address to, Message command) {
        return new Delivery(command, to, null, null, null);
    }

    /// When this message arrived, in epoch milliseconds.
    public long at() {
        return command.at();
    }

    public Delivery from(Address from) {
        return new Delivery(command, to, from, replyTo, deadline);
    }

    public Delivery replyTo(Address replyTo) {
        return new Delivery(command, to, from, replyTo, deadline);
    }

    public Delivery deadline(Instant deadline) {
        return new Delivery(command, to, from, replyTo, deadline);
    }

    public Delivery to(Address to) {
        return new Delivery(command, to, from, replyTo, deadline);
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
