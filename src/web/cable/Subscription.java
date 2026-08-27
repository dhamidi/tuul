package web.cable;

import eventstream.Event;
import eventstream.EventStream;
import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/// One client, listening.
///
/// The queue between the broadcaster and the client is the whole design. A
/// broadcast puts an event in it and returns — it never writes to a socket, so
/// one client on a bad connection cannot hold up everybody else — and the
/// client's own thread takes events out and writes them.
///
/// The queue is bounded, and a client that fills it is disconnected rather than
/// allowed to grow it. Something has to give when a client cannot keep up, and
/// the choices are to drop the client, drop events silently, or run out of
/// memory. Dropping the client is the only one it can recover from: the browser
/// reconnects with the id of the last event it saw, and the backlog tells it
/// what it missed.
final class Subscription implements AutoCloseable {

    /// A comment, which is the cheapest thing that can be sent down a stream
    /// that has nothing to say. The text is for whoever is reading the wire.
    private static final String KEEP_ALIVE = "keep-alive";

    private final List<String> topics;
    private final Writer out;
    private final String run;
    private final BlockingQueue<Delivery> pending;

    private volatile boolean open = true;
    private volatile boolean overrun;
    private volatile Thread delivering;

    Subscription(List<String> topics, Writer out, String run, int queue) {
        this.topics = List.copyOf(topics);
        this.out = out;
        this.run = run;
        this.pending = new ArrayBlockingQueue<>(queue);
    }

    List<String> topics() {
        return topics;
    }

    boolean listensTo(String topic) {
        return topics.contains(topic);
    }

    boolean open() {
        return open;
    }

    /// Whether this client was dropped for falling behind, rather than leaving.
    boolean overrun() {
        return overrun;
    }

    /// Hands an event to this client without waiting for it. Answers whether it
    /// was taken; a client that cannot take it is closed on the spot.
    boolean offer(Delivery delivery) {
        if (!open) return false;
        if (pending.offer(delivery)) return true;
        overrun = true;
        close();
        return false;
    }

    /// Writes now, on the caller's thread. This is for what a client is told
    /// before its stream begins — what it missed, or that it is too far behind
    /// to be told.
    void write(Delivery delivery) throws IOException {
        EventStream.write(delivery.addressed(run), out);
    }

    void write(Event event) throws IOException {
        EventStream.write(event, out);
    }

    /// Takes events until the client goes away or the cable closes.
    ///
    /// The heartbeat is the timeout on the queue rather than a thread of its
    /// own: silence for that long is exactly the case where a comment is worth
    /// sending, and a queue that has something in it did not need one.
    void deliver(Duration heartbeat) {
        delivering = Thread.currentThread();
        var wait = Math.max(1, heartbeat.toMillis());
        try {
            while (open) {
                var next = pending.poll(wait, TimeUnit.MILLISECONDS);
                if (next == null) EventStream.comment(KEEP_ALIVE, out);
                else write(next);
            }
        } catch (IOException gone) {
            // the client stopped reading; there is nobody left to tell
        } catch (InterruptedException ending) {
            Thread.currentThread().interrupt();
        } finally {
            open = false;
            delivering = null;
        }
    }

    /// Ends this subscription. Safe from any thread, and from the broadcaster's
    /// in particular — that is how a client that fell behind is dropped.
    @Override
    public void close() {
        open = false;
        var thread = delivering;
        if (thread != null) thread.interrupt();
    }
}
