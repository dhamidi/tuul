package actors;

import java.nio.file.Path;
import java.util.stream.Stream;

/// The store that holds every actor's log, and the only place that knows how
/// logs are kept.
///
/// ## Why the catalogue is here
///
/// Two different questions look alike and are not the same. *Which baskets
/// should exist* is domain truth, and it is answered by whichever actor
/// records that a basket was opened. *Which addresses have a log on disk* is
/// an operational question, and it is answered here. A backfill or an audit
/// needs the second one, and it cannot be answered by walking a directory
/// because a store is not always a directory.
///
/// ## Logs are kept
///
/// Nothing in this package deletes a log. An actor reaching a terminal state is
/// a judgement of today's rules, and tomorrow's rules may reopen it — a returns
/// policy that lets a closed basket be amended is an ordinary change, and it
/// works only because the commands are still there. [#erase(Address)] exists
/// for legal erasure and for tests. The system never calls it.
public interface Logs extends AutoCloseable {

    /// The log of one actor, opened for reading and appending.
    Log open(Address address);

    /// Every address that has a log.
    Stream<Address> catalogue();

    /// Every address of one type that has a log.
    Stream<Address> catalogue(String type);

    /// Every address of one type whose id starts with `prefix`.
    ///
    /// Hierarchical ids make this useful without a second index. The baskets of
    /// customer 42 are `basket/42/1`, `basket/42/2`, and so on, so the prefix
    /// `42/` lists exactly them.
    Stream<Address> catalogue(String type, String prefix);

    /// Destroys a log. For legal erasure and for tests. The system never calls
    /// this.
    void erase(Address address);

    @Override
    void close();

    /// A store for a system with no durable actors. Opening any log answers
    /// with [Log#none()] and the catalogue is empty.
    static Logs none() {
        return new Logs() {

            @Override
            public Log open(Address address) {
                return Log.none();
            }

            @Override
            public Stream<Address> catalogue() {
                return Stream.of();
            }

            @Override
            public Stream<Address> catalogue(String type) {
                return Stream.of();
            }

            @Override
            public Stream<Address> catalogue(String type, String prefix) {
                return Stream.of();
            }

            @Override
            public void erase(Address address) {
                // there is nothing to erase
            }

            @Override
            public void close() {
                // there is nothing open
            }
        };
    }

    /// A store that keeps one SQLite file per actor under `root`.
    static Logs at(Path root) {
        return new Journals(root);
    }
}
