package actors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/// The queue of one actor, with the rules that decide what gets in.
///
/// ## Three ways in, one way out
///
/// **Ordinary messages** come from outside and are bounded. A sender meeting a
/// full mailbox blocks for [Spawn#patience()] and is then told the actor is
/// busy. Blocking is the right default because it makes backpressure real: a
/// fan-out over a hundred actors that all feed one slow actor slows down
/// instead of building an unbounded queue in memory. The cost is that one slow
/// actor becomes a pause somewhere else, which is why the block is bounded and
/// the sender is told which actor it was.
///
/// **Control messages** — everything in the `actors.` namespace — go to the
/// front and ignore the bound. A bounded mailbox that could not accept
/// `actors.stop` would be impossible to shut down once full, which is exactly
/// when shutting it down matters.
///
/// **Messages an actor produced for itself**, which is what an effect emits, go
/// to the front and ignore the bound as well. Two reasons. Making the actor's
/// own thread wait on the actor's own mailbox would deadlock, because that
/// thread is the only consumer. And handling the feedback before new external
/// work preserves what [application.Application#dispatch(application.Message...)]
/// does, where the consequences of a step are finished before anything else
/// starts. The trade-off is that an actor that emits without end grows its own
/// queue without end, which is the same runaway a dispatch loop already allows.
///
/// ## Shedding
///
/// A delivery whose deadline has already passed is refused without being
/// queued. Under overload the first work to drop is the work whose sender has
/// already stopped waiting, which is the only kind of shedding that is never
/// wrong. Deadlines are opt-in and default to none, so a command that must be
/// handled is never dropped for being late.
final class Mailbox {

    /// What happened to a message offered to a mailbox.
    enum Admission {
        /// The message is queued.
        accepted,
        /// The mailbox stayed full for the whole patience.
        busy,
        /// The deadline had already passed when the message arrived.
        expired
    }

    /// One place in the queue. `metered` records whether the message took a
    /// permit on the way in, so that only those release one on the way out.
    private record Slot(Delivery delivery, boolean metered) {}

    private final LinkedBlockingDeque<Slot> queue = new LinkedBlockingDeque<>();
    private final Semaphore room;
    private final long patience;

    Mailbox(Spawn spawn) {
        room = new Semaphore(spawn.mailbox());
        patience = spawn.patience().toMillis();
    }

    /// Offers a message from outside. Blocks while the mailbox is full, up to
    /// the patience.
    Admission offer(Delivery delivery) throws InterruptedException {
        if (delivery.expired(Instant.now())) return Admission.expired;
        if (!room.tryAcquire(patience, TimeUnit.MILLISECONDS)) return Admission.busy;
        queue.addLast(new Slot(delivery, true));
        return Admission.accepted;
    }

    /// Puts a control message at the front, past the bound.
    void control(Delivery delivery) {
        queue.addFirst(new Slot(delivery, false));
    }

    /// Puts a message the actor produced for itself at the front, past the
    /// bound.
    void self(Delivery delivery) {
        queue.addFirst(new Slot(delivery, false));
    }

    /// The next message, waiting until there is one.
    Delivery take() throws InterruptedException {
        var slot = queue.takeFirst();
        if (slot.metered()) room.release();
        return slot.delivery();
    }

    /// How many messages are waiting. An inspector reads this to find where
    /// backpressure is biting.
    int depth() {
        return queue.size();
    }

    /// Everything still waiting, so that a dying actor can tell the senders
    /// their messages were never handled.
    List<Delivery> drain() {
        var slots = new ArrayList<Slot>();
        queue.drainTo(slots);
        return slots.stream().map(Slot::delivery).toList();
    }
}
