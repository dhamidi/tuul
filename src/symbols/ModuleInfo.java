package symbols;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/// The part of a module descriptor that is useful to a symbol reader.
///
/// Names are kept as strings because this is a documentation model, not a
/// resolver. The lists are sorted by the JDK descriptor API's natural order so
/// JSON and text output stay stable between processes.
public record ModuleInfo(
        String name,
        String kind,
        String origin,
        List<Requires> requires,
        List<Export> exports,
        List<Open> opens,
        List<String> uses,
        List<Provides> provides) {

    public ModuleInfo {
        name = Objects.requireNonNull(name, "name");
        kind = Objects.requireNonNull(kind, "kind");
        origin = Objects.requireNonNull(origin, "origin");
        requires = List.copyOf(requires);
        exports = List.copyOf(exports);
        opens = List.copyOf(opens);
        uses = List.copyOf(uses);
        provides = List.copyOf(provides);
    }

    public record Requires(String name, List<String> modifiers) {
        public Requires {
            modifiers = List.copyOf(modifiers);
        }
    }

    public record Export(String packageName, List<String> targets) {
        public Export {
            targets = List.copyOf(targets);
        }
    }

    public record Open(String packageName, List<String> targets) {
        public Open {
            targets = List.copyOf(targets);
        }
    }

    public record Provides(String service, List<String> providers) {
        public Provides {
            providers = List.copyOf(providers);
        }
    }

    /// Reads a `module-info.class` without loading any class from its module.
    public static ModuleInfo read(byte[] classFile, String origin) throws IOException {
        return of(ModuleDescriptor.read(ByteBuffer.wrap(classFile)), origin);
    }

    /// Reads a descriptor from a class-file stream.
    public static ModuleInfo read(java.io.InputStream classFile, String origin) throws IOException {
        return of(ModuleDescriptor.read(classFile), origin);
    }

    public static ModuleInfo of(ModuleDescriptor descriptor, String origin) {
        var requires = descriptor.requires().stream().sorted((left, right) -> left.name().compareTo(right.name()))
                .map(required -> new Requires(required.name(), required.modifiers().stream()
                        .map(ModuleDescriptor.Requires.Modifier::name).sorted().toList()))
                .toList();
        var exports = descriptor.exports().stream().sorted((left, right) -> left.source().compareTo(right.source()))
                .map(exported -> new Export(exported.source(), exported.targets().stream().sorted().toList()))
                .toList();
        var opens = descriptor.opens().stream().sorted((left, right) -> left.source().compareTo(right.source()))
                .map(open -> new Open(open.source(), open.targets().stream().sorted().toList()))
                .toList();
        var provides = descriptor.provides().stream().sorted((left, right) -> left.service().compareTo(right.service()))
                .map(provided -> new Provides(provided.service(), provided.providers().stream().sorted().toList()))
                .toList();
        var kind = descriptor.modifiers().contains(ModuleDescriptor.Modifier.AUTOMATIC) ? "automatic" : "explicit";
        return new ModuleInfo(descriptor.name(), kind, origin, requires, exports,
                opens, descriptor.uses().stream().sorted().toList(), provides);
    }
}
