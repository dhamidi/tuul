package actors;

import application.Message;

/// A message that did not reach the actor it was addressed to, and the reason.
///
/// ## Why the sender is told with a message
///
/// A failed delivery is a fact the sender may need to act on: retry, compensate,
/// or mark an order stuck. That is work for an update function, so it arrives
/// the way every other fact arrives. A behavior handles it with
/// `on(Undeliverable.MESSAGE, …)`.
///
/// The notice carries the original message, so a retry is one effect and needs
/// no bookkeeping on the sender's side.
///
/// ## It is logged, and that has a cost
///
/// A durable sender records the notice like any other message, so a
/// compensating decision replays. The honest cost is that a transient failure
/// becomes permanent history: a mailbox that was full for two seconds last
/// March is still in the log. That is the same trade every fact from outside
/// makes, and it is the price of a replay that does not have to guess.
///
/// ## The one rule that stops a loop
///
/// A notice that cannot itself be delivered is dropped and counted. Two full
/// mailboxes addressed at each other would otherwise answer each other forever.
///
/// @param to      the actor the message could not reach
/// @param cause   why it could not
/// @param message the message that did not arrive
public record Undeliverable(Address to, Cause cause, Message message) {

    /// The message type a sender handles to hear about a failed delivery.
    public static final String TYPE = "handle-delivery-error";

    /// The declaration a behavior uses to handle failed deliveries.
    public static final MessageType MESSAGE = MessageType.command(TYPE);

    /// Why a message did not arrive.
    public enum Cause {
        /// The actor does not declare this message type.
        unsupported,
        /// The message payload does not satisfy its declared JSON Schema.
        invalid,
        /// The inbound mailbox is full. The actor exists and is running.
        busy,
        /// No definition is registered for that type. This is a misspelled
        /// address, not congestion, so it fails at once instead of blocking.
        unknown,
        /// The address names another system and the transport refused it, or
        /// there is no transport.
        unreachable,
        /// The actor died too often too quickly and the system stopped
        /// restarting it. It takes a person to revive it.
        quarantined,
        /// The actor died while this message was waiting in its mailbox.
        died,
        /// The actor has been inside one update for longer than anybody expects
        /// and is not taking messages.
        stuck
    }

    /// This failure as the message the sender receives.
    public Message notice() {
        return Message.of(TYPE)
                .with("to", to.json())
                .with("cause", cause.name())
                .with("message", message.json());
    }

    /// Reads the failed message back out of a notice, which is what a retry
    /// needs.
    ///
    /// A notice always carries one, because [#notice()] is the only thing that
    /// builds one, so a notice without a message is malformed rather than
    /// empty. Answering with a typeless message would hand a retry something to
    /// send that nothing would handle.
    ///
    /// The message is nested here, and that is right where it would be wrong in
    /// an effect: this reports a message rather than sending one, so the
    /// message is the payload's subject. An effect that sends carries the
    /// payload itself — see [application.Effect#sending(String,
    /// application.Message)].
    public static Message messageOf(Message notice) {
        if (notice.get("message") instanceof json.Json.Object document) return Message.from(document);
        throw new IllegalArgumentException("not an " + TYPE + " notice: it carries no message");
    }

    /// Reads the address back out of a notice.
    public static Address toOf(Message notice) {
        return Address.from(notice.get("to"));
    }

    /// Reads the cause back out of a notice.
    public static Cause causeOf(Message notice) {
        try {
            return Cause.valueOf(notice.string("cause", ""));
        } catch (IllegalArgumentException e) {
            return Cause.unreachable;
        }
    }
}
