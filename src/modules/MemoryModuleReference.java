package modules;

import java.io.IOException;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.util.Objects;

/// A module reference that opens a fresh reader over one immutable snapshot.
public final class MemoryModuleReference extends ModuleReference {

    private final MemoryModule module;

    MemoryModuleReference(MemoryModule module) {
        super(Objects.requireNonNull(module, "module").descriptor(),
                URI.create("memory:/" + module.name()));
        this.module = module;
    }

    /// Returns the immutable module snapshot represented by this reference.
    public MemoryModule module() {
        return module;
    }

    @Override
    public MemoryModuleReader open() throws IOException {
        return new MemoryModuleReader(module);
    }
}
