package reload;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.spi.ToolProvider;

/// Lists and runs JDK tools attached to the active generation.
///
/// The catalog reads provider instances only while one lease is open. It does
/// not return a provider instance to the caller.
public final class JdkToolCatalog {
    private final Reload reload;

    /// Creates a catalog backed by `reload`.
    public JdkToolCatalog(Reload reload) {
        this.reload = Objects.requireNonNull(reload, "reload");
    }

    /// Lists tool names and descriptions from the active generation.
    ///
    /// The result is empty when no generation is active. Duplicate names throw
    /// `IllegalStateException`; provider type order makes that failure stable.
    public List<Tool> list() {
        try (var lease = reload.lease().orElse(null)) {
            if (lease == null) return List.of();
            return discover(lease.generation()).stream()
                    .map(entry -> new Tool(entry.name(), entry.description())).toList();
        }
    }

    /// Runs `name` with `arguments` while one generation lease is open.
    ///
    /// The tool writes to `out` and `err` and returns its exit code. A missing
    /// active generation throws `IllegalStateException`. A missing name throws
    /// `IllegalArgumentException`. Null output writers or arguments throw
    /// `NullPointerException`.
    public int run(String name, PrintWriter out, PrintWriter err, String... arguments) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Objects.requireNonNull(arguments, "arguments");
        var args = arguments.clone();
        for (var argument : args) Objects.requireNonNull(argument, "argument");
        try (var lease = reload.lease().orElseThrow(() ->
                new IllegalStateException("no active reload generation"))) {
            for (var entry : discover(lease.generation())) {
                if (entry.name().equals(name)) return entry.provider().run(out, err, args);
            }
            throw new IllegalArgumentException("unknown JDK tool: " + name);
        }
    }

    /// Describes one tool without exposing its provider instance.
    public record Tool(String name, String description) {
        /// Records a tool name and its description.
        public Tool {
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
        }
    }

    private static List<Entry> discover(Generation generation) {
        var providers = generation.services(ToolProvider.class);
        var entries = new ArrayList<Entry>();
        for (var provider : providers) {
            var tool = Objects.requireNonNull(provider, "tool provider");
            var name = Objects.requireNonNull(tool.name(), "tool name");
            entries.add(new Entry(name, tool.description().orElse(""), tool));
        }
        entries.sort(Comparator.comparing(Entry::name).thenComparing(entry ->
                entry.provider().getClass().getName()));
        for (var index = 1; index < entries.size(); index++) {
            if (entries.get(index - 1).name().equals(entries.get(index).name())) {
                throw new IllegalStateException("duplicate JDK tool name: " + entries.get(index).name());
            }
        }
        return List.copyOf(entries);
    }

    private record Entry(String name, String description, ToolProvider provider) {}
}
