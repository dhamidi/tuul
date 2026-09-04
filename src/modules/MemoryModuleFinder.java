package modules;

import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// A strict, immutable module finder backed by in-memory byte snapshots.
///
/// This finder has no unnamed-module behavior. Every entry has a real JPMS
/// descriptor and duplicate module names are rejected when the finder is built.
public final class MemoryModuleFinder implements ModuleFinder {

    private final Map<String, ModuleReference> modules;

    /// Creates a finder for the supplied named modules.
    public MemoryModuleFinder(Collection<MemoryModule> modules) {
        Objects.requireNonNull(modules, "modules");
        var found = new LinkedHashMap<String, ModuleReference>();
        for (var module : modules) {
            Objects.requireNonNull(module, "module");
            var prior = found.putIfAbsent(module.name(), new MemoryModuleReference(module));
            if (prior != null) throw new IllegalArgumentException("duplicate module: " + module.name());
        }
        this.modules = Map.copyOf(found);
    }

    /// Creates a finder for one named module.
    public static MemoryModuleFinder of(MemoryModule module) {
        return new MemoryModuleFinder(java.util.List.of(module));
    }

    /// Creates a finder for a non-empty or empty collection of named modules.
    public static MemoryModuleFinder of(Collection<MemoryModule> modules) {
        return new MemoryModuleFinder(modules);
    }

    @Override
    public Optional<ModuleReference> find(String name) {
        return Optional.ofNullable(modules.get(name));
    }

    @Override
    public Set<ModuleReference> findAll() {
        return Set.copyOf(modules.values());
    }
}
