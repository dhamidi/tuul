package modules;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/// A closeable reader over an in-memory module byte snapshot.
public final class MemoryModuleReader implements java.lang.module.ModuleReader {

    private final String moduleName;
    private final Map<String, byte[]> entries;
    private boolean closed;

    MemoryModuleReader(MemoryModule module) {
        moduleName = module.name();
        entries = module.entries();
    }

    @Override
    public Optional<URI> find(String name) throws IOException {
        checkOpen();
        return entries.containsKey(name)
                ? Optional.of(URI.create("memory:/" + moduleName + "/" + name))
                : Optional.empty();
    }

    @Override
    public Optional<InputStream> open(String name) throws IOException {
        checkOpen();
        var bytes = entries.get(name);
        return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public Optional<ByteBuffer> read(String name) throws IOException {
        checkOpen();
        var bytes = entries.get(name);
        return bytes == null ? Optional.empty() : Optional.of(ByteBuffer.wrap(bytes.clone()).asReadOnlyBuffer());
    }

    @Override
    public Stream<String> list() throws IOException {
        checkOpen();
        return entries.keySet().stream();
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("module reader is closed");
    }
}
