package actors;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/// The FIFO queues of one actor.
///
/// ## Two ways in, one way out
///
/// **Ordinary messages** come from outside and are bounded. An enqueue does not
/// wait. It returns `busy` when the queue is full.
///
/// **Actor continuations** use a second unbounded queue. The actor is the only
/// producer. The actor reads continuations in effect order before it reads the
/// next inbound message.
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
        /// The inbound queue is full.
        busy,
        /// The deadline had already passed when the message arrived.
        expired
    }

    private final Object lock = new Object();
    private final ArrayDeque<Delivery> continuations = new ArrayDeque<>();
    private final ArrayDeque<Delivery> inbound = new ArrayDeque<>();
    private final int capacity;
    private boolean accepting = true;

    Mailbox(Spawn spawn) {
        capacity = spawn.mailbox();
    }

    /// Adds one inbound message without waiting for room.
    Admission offer(Delivery delivery) {
        if (delivery.expired(Instant.now())) return Admission.expired;
        synchronized (lock) {
            if (!accepting) return Admission.busy;
            if (inbound.size() >= capacity) return Admission.busy;
            inbound.addLast(delivery);
            lock.notify();
            return Admission.accepted;
        }
    }

    /// Adds one actor continuation in effect order.
    void self(Delivery delivery) {
        synchronized (lock) {
            continuations.addLast(delivery);
            lock.notify();
        }
    }

    /// Stops admission and adds the stop message after accepted inbound work.
    void stop(Delivery delivery) {
        synchronized (lock) {
            if (!accepting) return;
            accepting = false;
            inbound.addLast(delivery);
            lock.notify();
        }
    }

    /// Adds an inspection after all inbound messages that were already accepted.
    /// Returns false after this mailbox stops admission.
    boolean inspect(Delivery delivery) {
        synchronized (lock) {
            if (!accepting) return false;
            inbound.addLast(delivery);
            lock.notify();
            return true;
        }
    }

    /// Returns the next message. Continuations have priority over inbound
    /// messages.
    Delivery take() throws InterruptedException {
        synchronized (lock) {
            while (continuations.isEmpty() && inbound.isEmpty()) lock.wait();
            if (!continuations.isEmpty()) return continuations.removeFirst();
            return inbound.removeFirst();
        }
    }

    /// How many messages are waiting. An inspector reads this to find where
    /// backpressure is biting.
    int depth() {
        synchronized (lock) {
            return continuations.size() + inbound.size();
        }
    }

    /// Everything still waiting, so that a dying actor can tell the senders
    /// their messages were never handled.
    List<Delivery> drain() {
        synchronized (lock) {
            var deliveries = new ArrayList<Delivery>(
                    continuations.size() + inbound.size());
            deliveries.addAll(continuations);
            deliveries.addAll(inbound);
            continuations.clear();
            inbound.clear();
            return List.copyOf(deliveries);
        }
    }
}
