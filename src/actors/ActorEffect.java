package actors;

import application.Effect;
import application.Message;
import java.time.Duration;

/// Creates effects that an actor runtime executes directly.
///
/// These factories keep routing outside the message payload. They also remove
/// actor protocol strings from definitions.
public final class ActorEffect {

    private ActorEffect() {}

    /// Requests delivery of `message` to `to`. A local delivery does not wait
    /// for mailbox room. A foreign delivery runs as an external effect.
    public static Effect tell(Address to, Message message) {
        return Effect.sending(ActorSystem.TELL, message).about(ActorSystem.TO, to.json());
    }

    /// Replies to the current delivery. The runtime drops this effect when the
    /// current delivery has no reply address.
    public static Effect reply(Message message) {
        return Effect.sending(ActorSystem.REPLY, message);
    }

    /// Sends `message` to this actor after `after`.
    public static Effect schedule(Duration after, Message message) {
        return schedule(null, after, message);
    }

    /// Sends `message` to `to` after `after`. A null destination means this
    /// actor.
    public static Effect schedule(Address to, Duration after, Message message) {
        if (after.isNegative()) throw new IllegalArgumentException("a schedule delay cannot be negative: " + after);
        var effect = Effect.sending(ActorSystem.SCHEDULE, message)
                .about(ActorSystem.AFTER, after.toMillis());
        return to == null ? effect : effect.about(ActorSystem.TO, to.json());
    }

    /// Summons `address` with its configured spawn options.
    public static Effect spawn(Address address) {
        return Effect.of(ActorSystem.SPAWN).with("address", address.json());
    }

    /// Evicts the actor that requests this effect.
    public static Effect evict() {
        return Effect.of(ActorSystem.EVICT);
    }

    /// Evicts `address` after it processes its accepted messages.
    public static Effect evict(Address address) {
        return Effect.of(ActorSystem.EVICT).with("address", address.json());
    }
}
