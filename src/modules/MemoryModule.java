package modules;

import java.lang.module.ModuleDescriptor;
import java.util.Map;
import java.util.Objects;

/// One named module whose class and resource entries are held in memory.
///
/// Entries use module-resource names: classes end in `.class` and use `/` as
/// the separator. The descriptor is the module identity; the map is the
/// immutable byte snapshot used by the layer loader.
public record MemoryModule(ModuleDescriptor descriptor, Map<String, byte[]> entries) {

    public MemoryModule {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        entries = entries == null ? Map.of() : entries.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> name(entry.getKey()), entry -> bytes(entry.getValue())));
    }

    /// The JPMS name of this module.
    public String name() {
        return descriptor.name();
    }

    /// Returns a deep snapshot of module entries.
    @Override
    public Map<String, byte[]> entries() {
        return entries.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().clone()));
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || value.startsWith("/")
                || value.contains("//") || value.contains("\\")
                || java.util.Arrays.stream(value.split("/", -1))
                        .anyMatch(part -> part.equals(".") || part.equals("..") || part.isEmpty())) {
            throw new IllegalArgumentException("module entry name must be relative: " + value);
        }
        return value;
    }

    private static byte[] bytes(byte[] value) {
        return value == null ? throwNull() : value.clone();
    }

    private static byte[] throwNull() {
        throw new NullPointerException("module entry bytes");
    }
}
