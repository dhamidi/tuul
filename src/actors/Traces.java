package actors;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/// The bus that carries [Trace] events out of a running system.
///
/// ## What it guarantees, and what it does not
///
/// It guarantees that publishing never blocks an actor. A trace is produced on
/// the thread that is running an actor, and that thread must not be made to wait
/// for a subscriber that has stopped reading. Everything else gives way to that.
///
/// It does not guarantee delivery. Each subscriber has a bounded queue, and a
/// subscriber that falls behind loses the oldest events it had not read yet. The
/// number lost is counted and reported by [#dropped()], because a gap nobody
/// knows about is worse than a gap.
///
/// Dropping the *oldest* rather than the newest is deliberate. A subscriber that
/// has fallen behind is nearly always watching a system that is misbehaving
/// right now, and the newest events are the ones that say why.
///
/// ## Cost when nobody is watching
///
/// [#publish(Trace)] returns immediately when there are no subscribers, and
/// callers use [#watched()] to avoid building a trace that nothing would read.
/// A system with no subscriber therefore pays one volatile read per event site.
final class Traces implements Flow.Publisher<Trace> {

    private final List<Feed> feeds = new CopyOnWriteArrayList<>();
    private final AtomicLong dropped = new AtomicLong();

    /// Whether anything is listening. A caller that would have to do work to
    /// build a trace checks this first.
    boolean watched() {
        return !feeds.isEmpty();
    }

    long dropped() {
        return dropped.get();
    }

    /// Hands a trace to every subscriber. Never blocks and never throws.
    void publish(Trace trace) {
        for (var feed : feeds) feed.offer(trace);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Trace> subscriber) {
        var feed = new Feed(subscriber, 1024);
        feeds.add(feed);
        subscriber.onSubscribe(feed);
    }

    /// Stops every subscriber. A system calls this on the way out so that a
    /// subscriber knows the events have ended rather than waiting for more.
    void close() {
        for (var feed : feeds) feed.finish();
        feeds.clear();
    }

    /// One subscriber's queue and the thread that drains it.
    ///
    /// The draining is done by a virtual thread of its own so that a slow
    /// subscriber slows down nothing but itself. Demand is honoured, so a
    /// subscriber that stops requesting stops receiving and its queue fills and
    /// starts dropping, which is the intended behaviour rather than unbounded
    /// growth.
    private final class Feed implements Flow.Subscription {

        private final Flow.Subscriber<? super Trace> subscriber;
        private final ArrayDeque<Trace> queue = new ArrayDeque<>();
        private final int capacity;
        private final Object lock = new Object();
        private long demand;
        private boolean cancelled;
        private Thread drain;

        private Feed(Flow.Subscriber<? super Trace> subscriber, int capacity) {
            this.subscriber = subscriber;
            this.capacity = capacity;
            this.drain = Thread.ofVirtual().name("traces").start(this::run);
        }

        private void offer(Trace trace) {
            synchronized (lock) {
                if (cancelled) return;
                if (queue.size() == capacity) {
                    queue.pollFirst();
                    dropped.incrementAndGet();
                }
                queue.addLast(trace);
                lock.notifyAll();
            }
        }

        private void run() {
            while (true) {
                Trace next;
                synchronized (lock) {
                    while (!cancelled && (demand == 0 || queue.isEmpty())) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (cancelled) return;
                    next = queue.pollFirst();
                    demand--;
                }
                try {
                    subscriber.onNext(next);
                } catch (RuntimeException e) {
                    cancel();
                    subscriber.onError(e);
                    return;
                }
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                cancel();
                subscriber.onError(new IllegalArgumentException("a subscriber must request a positive number"));
                return;
            }
            synchronized (lock) {
                demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
                lock.notifyAll();
            }
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                cancelled = true;
                queue.clear();
                lock.notifyAll();
            }
            feeds.remove(this);
        }

        private void finish() {
            var thread = drain;
            cancel();
            if (thread != null) thread.interrupt();
            try {
                subscriber.onComplete();
            } catch (RuntimeException e) {
                // a subscriber that fails on the way out cannot stop the system closing
            }
        }
    }
}
