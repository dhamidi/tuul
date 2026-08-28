package actors;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/// A store of SQLite logs, one file per actor, under one root directory.
///
/// The layout is `root/<type>/<id>.sqlite`, with both parts percent-encoded by
/// [Address#path(Path)]. Because a slash inside an id is encoded rather than
/// kept, an id never becomes a directory and one type is always one directory.
/// That keeps [#catalogue(String)] a single listing and [#catalogue(String,
/// String)] a listing with a prefix test.
///
/// A log is opened once and shared, because one actor owns its log and opening
/// the same SQLite file twice for writing is a way to meet `SQLITE_BUSY` for no
/// reason. The map is what makes [System#inspectAt(Address, long)] able to read
/// the history of an actor that is currently running.
final class Journals implements Logs {

    private final Path root;
    private final ConcurrentHashMap<Address, Journal> open = new ConcurrentHashMap<>();

    Journals(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot make the log directory " + root, e);
        }
    }

    @Override
    public Log open(Address address) {
        return open(address, Durability.normal);
    }

    @Override
    public Log open(Address address, Durability durability) {
        var journal = open.computeIfAbsent(address.here(), at -> {
            var file = at.path(root);
            try {
                Files.createDirectories(file.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot make the log directory for " + at, e);
            }
            return new Journal(file, at, durability);
        });
        journal.synchronous(durability);
        return journal;
    }

    @Override
    public Stream<Address> catalogue() {
        return types().flatMap(this::catalogue);
    }

    @Override
    public Stream<Address> catalogue(String type) {
        return catalogue(type, "");
    }

    @Override
    public Stream<Address> catalogue(String type, String prefix) {
        var directory = root.resolve(Address.encode(type));
        if (!Files.isDirectory(directory)) return Stream.of();
        return list(directory)
                .map(Path::getFileName)
                .map(Path::toString)
                .filter(name -> name.endsWith(".sqlite"))
                .map(name -> Address.decode(name.substring(0, name.length() - ".sqlite".length())))
                .filter(id -> id.startsWith(prefix))
                .map(id -> Address.of(type, id))
                .sorted();
    }

    @Override
    public void erase(Address address) {
        var at = address.here();
        var journal = open.remove(at);
        if (journal != null) journal.close();
        try {
            Files.deleteIfExists(at.path(root));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot erase the log of " + at, e);
        }
    }

    @Override
    public void close() {
        open.values().forEach(Journal::close);
        open.clear();
    }

    private Stream<String> types() {
        return list(root)
                .filter(Files::isDirectory)
                .map(Path::getFileName)
                .map(Path::toString)
                .map(Address::decode);
    }

    /// Reads a directory into memory rather than streaming it.
    ///
    /// A directory stream holds a file handle, and a caller that abandons the
    /// stream half way leaks it. A directory of log files is small enough that
    /// the list costs nothing, and the trade is worth making at this size.
    private static Stream<Path> list(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + directory, e);
        }
    }
}
