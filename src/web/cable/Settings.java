package web.cable;

import java.time.Duration;

/// What a cable is willing to spend on a client.
///
/// Every one of these is a bound on something that would otherwise be
/// unbounded, which is the only reason they exist: a queue that grows until
/// memory runs out, a backlog that remembers everything ever said, a connection
/// held open by a proxy that has already stopped listening.
///
/// @param queue how many events may be waiting for one client before it is
///     dropped — a client that cannot keep up is disconnected, and reconnects
///     with a last event id, which is what the backlog is for
/// @param backlog how many events each topic remembers, so a client that
///     reconnects can be told what it missed
/// @param heartbeat how long the connection may be silent; a comment goes out
///     when it is, because a proxy closes an idle connection and a comment
///     costs one line
/// @param refreshOnGap whether a client that missed more than the backlog holds
///     is asked to refresh itself, rather than left showing a page that quietly
///     stopped being true
public record Settings(int queue, int backlog, Duration heartbeat, boolean refreshOnGap) {

    public Settings {
        if (queue < 1) throw new IllegalArgumentException("a queue of " + queue + " cannot hold an event");
        if (backlog < 0) throw new IllegalArgumentException("a backlog cannot be " + backlog);
        if (heartbeat.isNegative() || heartbeat.isZero()) {
            throw new IllegalArgumentException("a heartbeat of " + heartbeat + " is not a heartbeat");
        }
    }

    /// Twenty seconds is under every default proxy timeout worth naming, and
    /// sixty-four events is more than a page that is behind can usefully apply.
    public static Settings standard() {
        return new Settings(64, 64, Duration.ofSeconds(20), true);
    }

    public Settings queue(int queue) {
        return new Settings(queue, backlog, heartbeat, refreshOnGap);
    }

    public Settings backlog(int backlog) {
        return new Settings(queue, backlog, heartbeat, refreshOnGap);
    }

    public Settings heartbeat(Duration heartbeat) {
        return new Settings(queue, backlog, heartbeat, refreshOnGap);
    }

    public Settings refreshOnGap(boolean refreshOnGap) {
        return new Settings(queue, backlog, heartbeat, refreshOnGap);
    }
}
