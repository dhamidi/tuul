package actors;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import json.Json;

/// What the runtime knows about the actors it has seen.
///
/// ## This is not domain truth
///
/// The registry answers operational questions: which actors are loaded, how
/// deep their mailboxes are, how often they have died. It does not answer
/// *which baskets exist*, because that is a question about the business and the
/// answer belongs to whichever actor recorded that a basket was opened. Asking
/// the registry a domain question gives an answer that is right until an actor
/// nobody has touched today is left out of it.
///
/// [Logs#catalogue()] answers a third question — which addresses have a log —
/// and that one is operational too. [System#known()] joins the two.
///
/// ## It is held in memory
///
/// Nothing here is written to disk. A restart forgets every count, and the set
/// of known actors is rebuilt from the log catalogue. Persisting it would make
/// a second record of facts the logs already hold, and a second record is a
/// second thing that can be wrong. The cost is that restart counts do not
/// survive a restart of the system itself, which is acceptable because a
/// quarantine is meant to stop a loop inside one run.
public final class Registry {

    /// What is known about one actor.
    ///
    /// @param address   who it is
    /// @param durable   whether its commands are logged
    /// @param loaded    whether it is in memory right now
    /// @param commands  how many entries its log holds
    /// @param mailbox   how many messages are waiting
    /// @param lastAt    when it last handled a message, in epoch milliseconds
    /// @param health    how its restarts are going
    public record Entry(Address address, boolean durable, boolean loaded, long commands, int mailbox,
            long lastAt, Health health) {

        public String type() {
            return address.type();
        }

        /// This entry as JSON, which is what an inspector prints.
        public Json.Object json() {
            return Json.Object.of()
                    .with("address", address.toString())
                    .with("type", address.type())
                    .with("durable", durable)
                    .with("loaded", loaded)
                    .with("commands", commands)
                    .with("mailbox", mailbox)
                    .with("lastAt", lastAt)
                    .with("restarts", health.restarts())
                    .with("quarantined", health.quarantined());
        }
    }

    /// How an actor's restarts are going.
    ///
    /// @param restarts    how many times it has died inside the window
    /// @param since       when the window began
    /// @param lastError   what killed it last, or an empty string
    /// @param quarantined whether the system has stopped restarting it
    public record Health(int restarts, Instant since, String lastError, boolean quarantined) {

        static final Health WELL = new Health(0, Instant.EPOCH, "", false);

        /// Counts one death and answers with the health that follows.
        ///
        /// The window slides: a death outside it starts the count again, so an
        /// actor that fails once a day is never quarantined and one that fails
        /// five times a minute is.
        Health died(String reason, Instant now, int allowed, Duration window) {
            var inside = !since.equals(Instant.EPOCH) && now.isBefore(since.plus(window));
            var count = inside ? restarts + 1 : 1;
            return new Health(count, inside ? since : now, reason, count >= allowed);
        }

        Health revived() {
            return WELL;
        }
    }

    private final Map<Address, Health> health = new ConcurrentHashMap<>();
    private final Map<Address, Long> lastAt = new ConcurrentHashMap<>();

    Registry() {}

    Health health(Address address) {
        return health.getOrDefault(address.here(), Health.WELL);
    }

    boolean quarantined(Address address) {
        return health(address).quarantined();
    }

    void died(Address address, String reason, int allowed, Duration window) {
        health.compute(address.here(), (ignored, existing) ->
                (existing == null ? Health.WELL : existing).died(reason, Instant.now(), allowed, window));
    }

    void revive(Address address) {
        health.remove(address.here());
    }

    void handled(Address address, long at) {
        lastAt.put(address.here(), at);
    }

    long lastAt(Address address) {
        return lastAt.getOrDefault(address.here(), 0L);
    }

    void forget(Address address) {
        lastAt.remove(address.here());
    }

    Stream<Address> troubled() {
        return health.entrySet().stream().filter(entry -> entry.getValue().quarantined()).map(Map.Entry::getKey);
    }
}
