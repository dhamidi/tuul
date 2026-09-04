package reload;

import compiler.ClassSink;
import compiler.Compiler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import modules.MemoryModule;
import modules.MemoryModuleFinder;
import modules.MemoryModuleLoader;

/// Compiles a named source-module closure and defines it in a fresh JPMS layer.
///
/// The host factory chooses and constructs the generation entrypoint from the
/// resolved layer supplied to it. Compilation writes no output in the revision
/// tree. Class bytes and module resources remain in memory for the generation
/// lifetime.
public final class RevisionCompiler {

    private final Compiler compiler;
    private final List<Path> parentModulePath;
    private final GenerationFactory factory;

    /// Creates a compiler with the system compiler and a host generation factory.
    public RevisionCompiler(List<Path> parentModulePath, GenerationFactory factory) {
        this(Compiler.system(), parentModulePath, factory);
    }

    /// Creates a compiler with an injectable compiler, module path, and factory.
    public RevisionCompiler(Compiler compiler, List<Path> parentModulePath,
            GenerationFactory factory) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.parentModulePath = paths(parentModulePath);
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /// Attaches lazy layer compilation to a source revision.
    public Revision compile(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        if (revision.program() != null) return revision;
        return revision.withProgram(new LoadedProgram(revision));
    }

    private final class LoadedProgram implements Program {
        private final Revision revision;

        private LoadedProgram(Revision revision) { this.revision = revision; }

        @Override
        public Generation define() throws Exception {
            var modules = compileModules();
            var finder = MemoryModuleFinder.of(modules);
            var external = dependencyFinder();
            var configuration = resolve(finder, external);
            // Each candidate module gets its own loader. The host Tuul module
            // keeps its parent loader so Program and the host API retain one
            // class identity; candidate and external module bytes stay in
            // memory and never become class-path classes.
            var sourceByName = new HashMap<String, MemoryModule>();
            for (var module : modules) sourceByName.put(module.name(), module);
            var inMemory = new ArrayList<MemoryModule>();
            var hostModule = RevisionCompiler.class.getModule().getName();
            for (var resolved : configuration.modules()) {
                var name = resolved.name();
                if (name.equals(hostModule)) continue;
                var source = sourceByName.get(name);
                if (source != null) inMemory.add(source);
                else {
                    var reference = external.find(name).orElseThrow(() ->
                            new CompilationFailure("resolved module has no reference: " + name));
                    inMemory.add(MemoryModuleLoader.read(reference));
                }
            }
            var parent = RevisionCompiler.class.getClassLoader();
            var loaders = MemoryModuleLoader.create(inMemory, parent);
            var layer = ModuleLayer.defineModules(configuration, List.of(ModuleLayer.boot()),
                    name -> loaders.containsKey(name) ? loaders.get(name) : parent).layer();
            MemoryModuleLoader.configure(loaders, layer);
            var root = layer.findModule(revision.rootModule()).orElseThrow(() ->
                    new CompilationFailure("root module is not in candidate layer: " + revision.rootModule()));
            return Objects.requireNonNull(factory.define(new CandidateContext(layer, root)),
                    "generation factory returned null");
        }

        private List<MemoryModule> compileModules() throws Exception {
            if (revision.modules().isEmpty()) throw new CompilationFailure("revision has no source modules");
            var names = new HashSet<String>();
            var sources = new ArrayList<Path>();
            var moduleSources = new LinkedHashMap<String, Path>();
            for (var module : revision.modules()) {
                if (!names.add(module.name())) throw new CompilationFailure("duplicate source module: " + module.name());
                moduleSources.put(module.name(), module.root());
                for (var source : module.sources()) { inside(module.root(), source, "source"); sources.add(source); }
                if (!module.sources().contains(module.descriptor())) throw new CompilationFailure(
                        "module sources omit descriptor: " + module.name());
            }
            var modulePath = new ArrayList<Path>(parentModulePath);
            modulePath.addAll(revision.dependencies());
            for (var path : modulePath) if (!Files.exists(path)) throw new CompilationFailure(
                    "module path entry does not exist: " + path);
            var classes = new HashMap<String, Map<String, byte[]>>();
            var result = compiler.compile(new Compiler.Request(sources, modulePath, revision.rootModule(),
                    Runtime.version().feature(), true, java.util.Optional.empty(), List.of(), moduleSources),
                    sink(classes));
            if (!result.ok()) throw new CompilationFailure(result.problems());
            if (result.classes() == 0) throw new CompilationFailure("compiler emitted no classes");
            var compiled = new ArrayList<MemoryModule>();
            for (var module : revision.modules()) {
                var output = classes.get(module.name());
                if (output == null || !output.containsKey("module-info")) throw new CompilationFailure(
                        "compiler produced no module-info.class for " + module.name() + " (outputs: " + classes.keySet() + ")");
                var descriptor = descriptor(output);
                if (!descriptor.name().equals(module.name())) throw new CompilationFailure(
                        "module descriptor name " + descriptor.name() + " differs from " + module.name());
                var entries = new HashMap<String, byte[]>();
                output.forEach((name, bytes) -> entries.put(classEntry(name), bytes));
                for (var resource : module.resources()) {
                    inside(module.root(), resource.path(), "resource");
                    if (entries.put(resource.name(), Files.readAllBytes(resource.path())) != null) {
                        throw new CompilationFailure("duplicate module entry: " + resource.name());
                    }
                }
                compiled.add(new MemoryModule(descriptor, entries));
            }
            return compiled;
        }

        private ModuleFinder dependencyFinder() throws Exception {
            var paths = new ArrayList<Path>(parentModulePath);
            paths.addAll(revision.dependencies());
            if (paths.isEmpty()) return ModuleFinder.of();
            for (var path : paths) if (!Files.exists(path)) throw new CompilationFailure(
                    "module path entry does not exist: " + path);
            try {
                var finder = ModuleFinder.of(paths.toArray(Path[]::new));
                var automatic = finder.findAll().stream()
                        .filter(reference -> reference.descriptor().isAutomatic())
                        .map(reference -> reference.descriptor().name()).sorted().toList();
                if (!automatic.isEmpty()) throw new CompilationFailure(
                        "automatic modules are not supported; add module-info.class to "
                                + String.join(", ", automatic));
                return finder;
            } catch (RuntimeException failure) {
                throw new CompilationFailure("invalid module path: " + failure.getMessage());
            }
        }

        private Configuration resolve(ModuleFinder project, ModuleFinder external) throws Exception {
            try {
                var finder = ModuleFinder.compose(project, external);
                var configuration = ModuleLayer.boot().configuration().resolve(
                        ModuleFinder.of(), finder, List.of(revision.rootModule()));
                var packages = new HashMap<String, String>();
                for (var boot : ModuleLayer.boot().modules()) {
                    for (var name : boot.getPackages()) packages.putIfAbsent(name, boot.getName());
                }
                for (var resolved : configuration.modules()) {
                    var owner = resolved.name();
                    var reference = finder.find(owner).orElseThrow();
                    for (var name : reference.descriptor().packages()) {
                        var prior = packages.putIfAbsent(name, owner);
                        if (prior != null && !prior.equals(owner)) throw new CompilationFailure(
                                "split package " + name + " in " + prior + " and " + owner);
                    }
                }
                return configuration;
            } catch (CompilationFailure failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new CompilationFailure("cannot resolve module graph: " + failure.getMessage());
            }
        }

    }

    private static ModuleDescriptor descriptor(Map<String, byte[]> classes) throws Exception {
        var bytes = classes.get("module-info");
        if (bytes == null) throw new CompilationFailure("compiler produced no module-info.class");
        try (var input = new ByteArrayInputStream(bytes)) {
            return ModuleDescriptor.read(input);
        } catch (IllegalArgumentException | IOException failure) {
            throw new CompilationFailure("invalid compiled module descriptor: " + failure.getMessage());
        }
    }

    private static ClassSink sink(Map<String, Map<String, byte[]>> classes) {
        return new ClassSink() {
            @Override public OutputStream open(String binaryName) { return open("", binaryName); }
            @Override public OutputStream open(String module, String binaryName) {
                var output = new ByteArrayOutputStream();
                var target = classes.computeIfAbsent(module, ignored -> new HashMap<>());
                return new OutputStream() {
                    @Override public void write(int value) { output.write(value); }
                    @Override public void write(byte[] bytes, int offset, int length) {
                        output.write(bytes, offset, length);
                    }
                    @Override public void close() { target.put(binaryName, output.toByteArray()); }
                };
            }
        };
    }

    private static String classEntry(String binaryName) {
        return binaryName.replace('.', '/') + ".class";
    }

    private static List<Path> paths(List<Path> paths) {
        return paths == null ? List.of() : paths.stream()
                .map(path -> Objects.requireNonNull(path, "module path entry")
                        .toAbsolutePath().normalize()).toList();
    }

    private static void inside(Path root, Path path, String kind) throws Exception {
        var normalized = path == null ? null : path.toAbsolutePath().normalize();
        if (normalized == null || !normalized.startsWith(root.toAbsolutePath().normalize())) {
            throw new CompilationFailure(kind + " is outside module root: " + path);
        }
        if (!Files.isRegularFile(normalized)) throw new CompilationFailure(kind + " does not exist: " + path);
    }


    /// A compiler diagnostic which keeps all javac problems together.
    public static final class CompilationFailure extends Exception {
        private final List<Compiler.Problem> problems;
        private CompilationFailure(String message) { super(message); problems = List.of(); }
        private CompilationFailure(List<Compiler.Problem> problems) { super(format(problems)); this.problems = List.copyOf(problems); }
        /// Returns javac diagnostics in compiler order.
        public List<Compiler.Problem> problems() { return problems; }
        private static String format(List<Compiler.Problem> problems) {
            return problems.isEmpty() ? "compilation failed" : problems.stream()
                    .map(problem -> (problem.source() == null ? "" : problem.source() + ":")
                            + problem.line() + " " + problem.message())
                    .reduce((one, two) -> one + "; " + two).orElse("compilation failed");
        }
    }
}
