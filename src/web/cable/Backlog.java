package web.cable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/// What one topic still remembers.
///
/// A client that reconnects says where it got to, and this answers with what it
/// missed — or admits it cannot. Both answers matter: replaying what is still
/// held is how a page catches up without reloading, and knowing that something
/// was dropped is how it learns to reload rather than showing a page that
/// silently stopped being true.
///
/// Bounded, because the alternative is a server that remembers every event ever
/// broadcast for the sake of a client that may never come back.
final class Backlog {

    private final Deque<Delivery> retained = new ArrayDeque<>();
    private final int depth;
    private long evicted;

    Backlog(int depth) {
        this.depth = depth;
    }

    void add(Delivery delivery) {
        if (depth == 0) {
            evicted = delivery.sequence();
            return;
        }
        retained.addLast(delivery);
        while (retained.size() > depth) evicted = retained.removeFirst().sequence();
    }

    /// Everything after `position` that is still here. The upper bound is what
    /// keeps a subscriber from being told twice: anything past it is already
    /// waiting in that subscriber's queue.
    List<Delivery> since(long position, long upTo) {
        var missed = new ArrayList<Delivery>();
        for (var delivery : retained) {
            if (delivery.sequence() > position && delivery.sequence() <= upTo) missed.add(delivery);
        }
        return missed;
    }

    /// Whether something after `position` has already been forgotten, which is
    /// the question a reconnecting client is really asking.
    boolean gapAfter(long position) {
        return position < evicted;
    }
}
