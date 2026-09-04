package modules;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;

/// A resolved JPMS graph and the descriptors that produced it.
///
/// Maven coordinates are deliberately not part of this graph's identity. A
/// caller may attach a coordinate to [Origin], but module names, descriptors,
/// packages, and readability are the facts used for resolution. Every input
/// must be an explicit named module artifact. A class directory or archive
/// without `module-info.class` is refused rather than silently becoming a
/// class path or an automatic module.
public final class ModuleGraph {

    /// A module's stable identity. JPMS resolves by name; the descriptor
    /// version is provenance and does not create a second module identity.
    public record Id(String name, Optional<String> version) {
        public Id {
            name = require(name, "module name");
            version = version == null ? Optional.empty() : version;
        }

        static Id of(ModuleDescriptor descriptor) {
            return new Id(descriptor.name(), descriptor.rawVersion());
        }
    }

    /// Where a descriptor came from. A JDK module normally has only a URI;
    /// project and dependency modules normally also have an input path.
    public record Origin(Optional<Path> path, Optional<String> coordinate, Optional<URI> location) {
        public Origin {
            path = path == null ? Optional.empty() : path;
            coordinate = coordinate == null ? Optional.empty() : coordinate;
            location = location == null ? Optional.empty() : location;
        }

        public static Origin of(Path path, String coordinate, URI location) {
            return new Origin(Optional.ofNullable(path), Optional.ofNullable(coordinate),
                    Optional.ofNullable(location));
        }

        public static Origin system(URI location) {
            return of(null, null, location);
        }
    }

    /// One required module and the modifiers on its `requires` declaration.
    public record Requires(String source, String target, Set<ModuleDescriptor.Requires.Modifier> modifiers) {
        public Requires {
            source = require(source, "requires source");
            target = require(target, "requires target");
            modifiers = Set.copyOf(Objects.requireNonNull(modifiers, "requires modifiers"));
        }

        public boolean transitive() { return modifiers.contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE); }
        public boolean staticPhase() { return modifiers.contains(ModuleDescriptor.Requires.Modifier.STATIC); }
    }

    /// An exported package and its optional qualified targets.
    public record Export(String source, String packageName, Set<String> targets) {
        public Export {
            source = require(source, "export source");
            packageName = require(packageName, "export package");
            targets = Set.copyOf(Objects.requireNonNull(targets, "export targets"));
        }

        public boolean qualified() { return !targets.isEmpty(); }
    }

    /// An opened package and its optional qualified targets.
    public record Open(String source, String packageName, Set<String> targets) {
        public Open {
            source = require(source, "open source");
            packageName = require(packageName, "open package");
            targets = Set.copyOf(Objects.requireNonNull(targets, "open targets"));
        }

        public boolean qualified() { return !targets.isEmpty(); }
    }

    /// A service implementation declaration.
    public record Provides(String source, String service, List<String> providers) {
        public Provides {
            source = require(source, "provides source");
            service = require(service, "service");
            providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        }
    }

    /// A descriptor plus its artifact provenance and derived JPMS facts.
    public record Node(Id id, ModuleDescriptor descriptor, Origin origin,
            Set<String> packages, List<Requires> requires, List<Export> exports,
            List<Open> opens, Set<String> uses, List<Provides> provides) {
        public Node {
            id = Objects.requireNonNull(id, "id");
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            if (descriptor.isAutomatic()) throw new IllegalArgumentException(
                    "automatic modules are not supported; artifact must contain module-info.class");
            origin = Objects.requireNonNull(origin, "origin");
            packages = Set.copyOf(Objects.requireNonNull(packages, "packages"));
            requires = List.copyOf(Objects.requireNonNull(requires, "requires"));
            exports = List.copyOf(Objects.requireNonNull(exports, "exports"));
            opens = List.copyOf(Objects.requireNonNull(opens, "opens"));
            uses = Set.copyOf(Objects.requireNonNull(uses, "uses"));
            provides = List.copyOf(Objects.requireNonNull(provides, "provides"));
        }

        static Node of(ModuleReference reference, Path input, String coordinate) {
            var descriptor = reference.descriptor();
            var source = descriptor.name();
            var requires = descriptor.requires().stream()
                    .map(value -> new Requires(source, value.name(), value.modifiers()))
                    .sorted(Comparator.comparing(Requires::target)).toList();
            var exports = descriptor.exports().stream()
                    .map(value -> new Export(source, value.source(), value.targets()))
                    .sorted(Comparator.comparing(Export::packageName)).toList();
            var opens = descriptor.opens().stream()
                    .map(value -> new Open(source, value.source(), value.targets()))
                    .sorted(Comparator.comparing(Open::packageName)).toList();
            var provides = descriptor.provides().stream()
                    .map(value -> new Provides(source, value.service(), value.providers()))
                    .sorted(Comparator.comparing(Provides::service)).toList();
            var origin = Origin.of(input, coordinate, reference.location().orElse(null));
            return new Node(Id.of(descriptor), descriptor, origin, descriptor.packages(), requires, exports, opens,
                    descriptor.uses(), provides);
        }

        static Node system(ModuleReference reference) {
            return of(reference, null, null);
        }
    }

    /// A machine-readable graph failure. All diagnostics are collected before
    /// the exception is thrown, so an agent can fix one invocation from one
    /// answer rather than rediscovering conflicts one at a time.
    public enum Code { INVALID_ARTIFACT, UNNAMED_ARTIFACT,
        DUPLICATE_MODULE, SPLIT_PACKAGE, MISSING_ROOT, UNRESOLVED_GRAPH }

    public record Diagnostic(Code code, String module, String packageName, Origin origin, String detail) {
        public Diagnostic {
            code = Objects.requireNonNull(code, "code");
            module = module == null ? "" : module;
            packageName = packageName == null ? "" : packageName;
            origin = Objects.requireNonNull(origin, "origin");
            detail = require(detail, "diagnostic detail");
        }
    }

    /// Thrown when module-path discovery or JPMS resolution cannot finish.
    public static final class Failure extends IOException {
        private final List<Diagnostic> diagnostics;

        public Failure(List<Diagnostic> diagnostics) {
            super(message(diagnostics));
            this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (this.diagnostics.isEmpty()) throw new IllegalArgumentException("diagnostics are empty");
        }

        public List<Diagnostic> diagnostics() { return diagnostics; }
    }

    private final Configuration configuration;
    private final Set<String> roots;
    private final Map<String, Node> modules;
    private final Map<String, String> packageOwners;

    private ModuleGraph(Configuration configuration, Collection<String> roots,
            Map<String, Node> modules, Map<String, String> packageOwners) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.roots = Set.copyOf(Objects.requireNonNull(roots, "roots"));
        this.modules = Map.copyOf(new TreeMap<>(modules));
        this.packageOwners = Map.copyOf(new TreeMap<>(packageOwners));
    }

    /// Resolves project and dependency module artifacts against the boot layer.
    /// Every path must identify an explicit named module with a
    /// `module-info.class` descriptor.
    public static ModuleGraph resolve(List<Path> paths, Collection<String> roots) throws IOException {
        Objects.requireNonNull(paths, "paths");
        var artifacts = new LinkedHashMap<Path, String>();
        for (var path : paths) artifacts.put(path, null);
        return resolve(artifacts, roots);
    }

    /// Resolves artifacts and retains their Maven coordinate as provenance.
    public static ModuleGraph resolve(Map<Path, String> artifacts, Collection<String> roots) throws IOException {
        Objects.requireNonNull(artifacts, "artifacts");
        var requested = roots == null ? List.<String>of() : roots.stream().filter(Objects::nonNull).distinct().toList();
        var diagnostics = new ArrayList<Diagnostic>();
        if (requested.isEmpty()) diagnostics.add(new Diagnostic(Code.MISSING_ROOT, "", "",
                Origin.of(null, null, null), "at least one root module is required"));
        var references = new ArrayList<Located>();
        for (var entry : artifacts.entrySet()) discover(entry.getKey(), entry.getValue(), references, diagnostics);
        var nodes = nodes(references, diagnostics);
        var system = ModuleFinder.ofSystem();
        var systemNodes = new LinkedHashMap<String, Node>();
        for (var reference : system.findAll()) systemNodes.put(reference.descriptor().name(), Node.system(reference));
        for (var node : nodes.values()) {
            if (systemNodes.containsKey(node.id().name())) diagnostics.add(new Diagnostic(Code.DUPLICATE_MODULE,
                    node.id().name(), "", node.origin(), "module name is already provided by the boot layer"));
        }
        var allNodes = new LinkedHashMap<String, Node>(systemNodes);
        allNodes.putAll(nodes);
        var names = new LinkedHashSet<>(nodes.keySet());
        names.addAll(systemNodes.keySet());
        for (var root : requested) {
            if (!names.contains(root)) diagnostics.add(new Diagnostic(Code.MISSING_ROOT, root, "",
                    Origin.of(null, null, null), "root module is not present on the module path or boot layer"));
        }
        if (!diagnostics.isEmpty()) throw new Failure(diagnostics);
        Configuration configuration;
        try {
            var finder = ModuleFinder.of(references.stream().map(Located::path).distinct().toArray(Path[]::new));
            // Resolve an application graph against the JDK, not against the
            // modules of the Tuul process that performs the resolution. The
            // host can itself contain `tuul`; it must not shadow a project's
            // vendored `tuul` artifact.
            configuration = Configuration.resolve(system, List.of(Configuration.empty()), finder, requested);
        } catch (RuntimeException failure) {
            diagnostics.add(new Diagnostic(Code.UNRESOLVED_GRAPH, "", "", Origin.of(null, null, null),
                    failure.getMessage() == null ? failure.toString() : failure.getMessage()));
            throw new Failure(diagnostics);
        }
        var resolved = reachable(configuration, nodes, systemNodes);
        var owners = packageOwners(resolved, diagnostics);
        if (!diagnostics.isEmpty()) throw new Failure(diagnostics);
        return new ModuleGraph(configuration, requested, resolved, owners);
    }

    private static Map<String, Node> reachable(Configuration configuration,
            Map<String, Node> input, Map<String, Node> system) {
        var names = new LinkedHashSet<String>();
        var pending = new java.util.ArrayDeque<java.lang.module.ResolvedModule>();
        for (var module : configuration.modules()) {
            names.add(module.name());
            pending.addAll(module.reads());
        }
        while (!pending.isEmpty()) {
            var resolved = pending.removeFirst();
            var name = resolved.name();
            if (!names.add(name)) continue;
            var node = input.containsKey(name) ? input.get(name) : system.get(name);
            pending.addAll(resolved.reads());
        }
        var result = new LinkedHashMap<String, Node>();
        for (var name : names) {
            var node = input.get(name);
            if (node == null) node = system.get(name);
            if (node != null) result.put(name, node);
        }
        return result;
    }

    /// The boot layer's system-module graph, useful for analysis and tooling.
    public static ModuleGraph system() {
        var nodes = new LinkedHashMap<String, Node>();
        var finder = ModuleFinder.ofSystem();
        for (var reference : finder.findAll()) nodes.put(reference.descriptor().name(), Node.system(reference));
        var owners = packageOwners(nodes, new ArrayList<>());
        var configuration = Configuration.resolve(finder, List.of(Configuration.empty()), ModuleFinder.of(), nodes.keySet());
        return new ModuleGraph(configuration, nodes.keySet(), nodes, owners);
    }

    public Configuration configuration() { return configuration; }
    public Set<String> roots() { return roots; }
    public Map<String, Node> modules() { return modules; }
    public Optional<Node> module(String name) { return Optional.ofNullable(modules.get(name)); }
    public Map<String, String> packageOwners() { return packageOwners; }
    public Optional<String> owner(String packageName) { return Optional.ofNullable(packageOwners.get(packageName)); }

    /// Returns the modules readable by `source`, including `source` itself.
    public Set<String> readable(String source) {
        var module = configuration.findModule(source).orElseThrow(() ->
                new IllegalArgumentException("unknown resolved module: " + source));
        var names = new TreeSet<String>();
        names.add(source);
        for (var read : module.reads()) names.add(read.name());
        return Set.copyOf(names);
    }

    /// Whether `source` may access a package according to its exports.
    public boolean exportedTo(String owner, String packageName, String target) {
        var node = module(owner).orElseThrow(() -> new IllegalArgumentException("unknown module: " + owner));
        return node.exports().stream().filter(export -> export.packageName().equals(packageName))
                .anyMatch(export -> export.targets().isEmpty() || export.targets().contains(target));
    }

    /// Whether `source` may use deep reflection into a package.
    public boolean openedTo(String owner, String packageName, String target) {
        var node = module(owner).orElseThrow(() -> new IllegalArgumentException("unknown module: " + owner));
        return node.opens().stream().filter(open -> open.packageName().equals(packageName))
                .anyMatch(open -> open.targets().isEmpty() || open.targets().contains(target));
    }

    private record Located(Path path, String coordinate, ModuleReference reference) {}

    private static void discover(Path path, String coordinate, List<Located> into,
            List<Diagnostic> diagnostics) {
        var origin = Origin.of(path, coordinate, null);
        if (path == null || !java.nio.file.Files.exists(path)) {
            diagnostics.add(new Diagnostic(Code.INVALID_ARTIFACT, "", "", origin, "module artifact does not exist"));
            return;
        }
        try {
            var found = ModuleFinder.of(path).findAll();
            if (found.isEmpty()) {
                diagnostics.add(new Diagnostic(Code.UNNAMED_ARTIFACT, "", "", origin,
                        "artifact has no module-info.class and is not a named module"));
                return;
            }
            for (var reference : found) {
                if (reference.descriptor().isAutomatic()) {
                    diagnostics.add(new Diagnostic(Code.INVALID_ARTIFACT, reference.descriptor().name(), "", origin,
                            "automatic modules are not supported; artifact must contain module-info.class"));
                } else {
                    into.add(new Located(path, coordinate, reference));
                }
            }
        } catch (RuntimeException failure) {
            diagnostics.add(new Diagnostic(Code.INVALID_ARTIFACT, "", "", origin,
                    failure.getMessage() == null ? failure.toString() : failure.getMessage()));
        }
    }

    private static Map<String, Node> nodes(List<Located> references, List<Diagnostic> diagnostics) {
        var nodes = new LinkedHashMap<String, Node>();
        for (var located : references) {
            var node = Node.of(located.reference(), located.path(), located.coordinate());
            var prior = nodes.putIfAbsent(node.id().name(), node);
            if (prior != null) diagnostics.add(new Diagnostic(Code.DUPLICATE_MODULE, node.id().name(), "",
                    node.origin(), "module name also provided by " + describe(prior.origin())));
        }
        return nodes;
    }

    private static Map<String, String> packageOwners(Map<String, Node> nodes, List<Diagnostic> diagnostics) {
        var owners = new LinkedHashMap<String, String>();
        for (var node : nodes.values()) {
            for (var packageName : node.packages()) {
                var prior = owners.putIfAbsent(packageName, node.id().name());
                if (prior != null && !prior.equals(node.id().name())) diagnostics.add(new Diagnostic(
                        Code.SPLIT_PACKAGE, node.id().name(), packageName, node.origin(),
                        "package is also in module " + prior));
            }
        }
        return owners;
    }

    private static String describe(Origin origin) {
        return origin.path().map(Path::toString).orElseGet(() -> origin.location().map(URI::toString).orElse("unknown artifact"));
    }

    private static String message(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(diagnostic -> diagnostic.code() + ": " + diagnostic.detail()
                + (diagnostic.module().isEmpty() ? "" : " [" + diagnostic.module() + "]")
                + (diagnostic.packageName().isEmpty() ? "" : " " + diagnostic.packageName()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
