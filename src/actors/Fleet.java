package actors;

import application.Message;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Gatherers;
import java.util.stream.Stream;

/// A fan-out over many actors of one type.
///
/// ## What it is for
///
/// Spinning up a hundred actors and coordinating them should be a few lines and
/// should not involve an executor. A fleet names the addresses, sends to all of
/// them, and hands back a [Stream] so that the JDK's own combinators finish the
/// job:
///
/// ```
/// var total = system.fleet("counter")
///         .over(IntStream.rangeClosed(1, 100).mapToObj(String::valueOf))
///         .tell(address -> Message.of("increment"))
///         .ask(address -> Message.of("total"))
///         .mapToLong(reply -> reply.number("value"))
///         .sum();
/// ```
///
/// ## Why it terminates in a Stream
///
/// Every combinator a fleet could grow — filter, map, group, collect, take —
/// already exists on [Stream] and is better tested than a new one would be.
/// This type does the part streams cannot do, which is naming addresses and
/// running the asks at the same time, and then gets out of the way.
///
/// The concurrency comes from [Gatherers#mapConcurrent(int, Function)], which
/// runs on virtual threads and bounds how many asks are in flight. There is no
/// executor to own and none to shut down.
///
/// ## The trade-off
///
/// An ask per actor is one waiting answer per actor. Over a hundred actors that
/// costs nothing. Over ten thousand it is ten thousand deadlines running at
/// once, and the right shape then is one collector actor that the fleet tells,
/// rather than ten thousand asks the caller waits on. The bound on
/// [#concurrency(int)] is what keeps the middle of that range sensible.
public final class Fleet {

    private final ActorSystem system;
    private final String type;
    private List<Address> addresses = List.of();
    private int concurrency = 16;
    private Duration deadline = Duration.ofSeconds(5);

    Fleet(ActorSystem system, String type) {
        this.system = system;
        this.type = type;
    }

    /// The instances to address, by id.
    public Fleet over(Stream<String> ids) {
        addresses = ids.map(id -> Address.of(type, id)).toList();
        return this;
    }

    public Fleet over(Collection<String> ids) {
        return over(ids.stream());
    }

    /// Every instance of this type that has a log, which is how a backfill
    /// names its subjects.
    public Fleet all() {
        addresses = system.known()
                .map(Registry.Entry::address)
                .filter(address -> address.type().equals(type))
                .toList();
        return this;
    }

    /// Every instance whose id starts with this prefix. Hierarchical ids make
    /// this the way to name the children of one parent.
    public Fleet under(String prefix) {
        addresses = system.known()
                .map(Registry.Entry::address)
                .filter(address -> address.type().equals(type) && address.id().startsWith(prefix))
                .toList();
        return this;
    }

    /// How many asks may be waiting at once.
    public Fleet concurrency(int concurrency) {
        this.concurrency = concurrency;
        return this;
    }

    /// How long each ask waits.
    public Fleet deadline(Duration deadline) {
        this.deadline = deadline;
        return this;
    }

    public List<Address> addresses() {
        return addresses;
    }

    /// Sends one message to every actor and does not wait. Answers with this
    /// fleet so that a `tell` can be followed by an `ask`.
    public Fleet tell(Function<Address, Message> message) {
        addresses.forEach(address -> system.tell(address, message.apply(address)));
        return this;
    }

    /// Sends one message to every actor and waits for all the answers.
    ///
    /// The answers come back in the order the addresses were named, whatever
    /// order they arrived in, because a caller that has to sort them again has
    /// been given a worse tool than it needed. An ask that fails or times out
    /// comes back as a [Reply] with a null message rather than an exception, so
    /// one slow actor cannot lose the ninety-nine answers that did arrive.
    public Stream<Reply> ask(Function<Address, Message> message) {
        return addresses.stream()
                .gather(Gatherers.mapConcurrent(concurrency, address ->
                        new Reply(address, settle(system.ask(address, message.apply(address), deadline)))));
    }

    private static Message settle(CompletableFuture<Message> answer) {
        try {
            return answer.join();
        } catch (RuntimeException failed) {
            return null;
        }
    }

    /// One actor's answer. The message is null when the ask failed or ran out
    /// of time.
    public record Reply(Address address, Message message) {

        public boolean answered() {
            return message != null;
        }

        /// A numeric field of the answer, or zero when there was no answer.
        public double number(String field) {
            if (message == null) return 0;
            return message.get(field) instanceof json.Json.Num(var value) ? value : 0;
        }
    }
}
