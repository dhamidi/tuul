package project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The source roots and named modules of one tuul project.
///
/// `src/`, `entrypoints/`, and `test/` are separate named modules. A descriptor
/// is required for every root that exists. Entrypoints use a named package and
/// a `Main` class; the directory below `entrypoints/` is its command name.
public record Layout(Path root) {

    public static final String ENTRYPOINT = "Main.java";
    private static final Pattern MODULE = Pattern.compile("\\b(?:open\\s+)?module\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*\\{");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");

    public Path src() { return root.resolve("src"); }
    public Path entrypointsRoot() { return root.resolve("entrypoints"); }
    public Path test() { return root.resolve("test"); }
    public Path tasks() { return root.resolve("tasks"); }
    public Path vendor() { return root.resolve("vendor"); }
    public Path resources() { return src().resolve("resources"); }
    public Path nativeRoot() { return root.resolve("native"); }

    public Path library(String module) {
        return root.resolve("build/native").resolve(System.mapLibraryName(module));
    }

    /// The application root as one source module for binding and documentation.
    public List<Path> libraries() { return List.of(src()); }

    /// The exploded output for a named module.
    public Path moduleOutput(String module) { return root.resolve("build/modules").resolve(module); }

    public boolean exists() { return Files.isDirectory(src()); }

    /// The application's named source module.
    public SourceModule application() throws IOException { return sourceModule(src()); }

    /// The entrypoint source module, when the project has one.
    public Optional<SourceModule> entrypointModule() throws IOException {
        return Files.isDirectory(entrypointsRoot()) ? Optional.of(sourceModule(entrypointsRoot())) : Optional.empty();
    }

    /// The test source module, when the project has one.
    public Optional<SourceModule> testModule() throws IOException {
        return Files.isDirectory(test()) ? Optional.of(sourceModule(test())) : Optional.empty();
    }

    /// Tasks are source modules too. Build does not compile them; `tuul run`
    /// supplies their module to the task compiler.
    public Optional<SourceModule> taskModule() throws IOException {
        return Files.isDirectory(tasks()) ? Optional.of(sourceModule(tasks())) : Optional.empty();
    }

    /// Names of the entrypoint classes below `entrypoints/`.
    public List<String> entrypoints() throws IOException {
        var module = entrypointModule();
        if (module.isEmpty()) return List.of();
        var found = new ArrayList<String>();
        for (var source : module.get().sources()) {
            if (!source.getFileName().toString().equals("Main.java")) continue;
            var relative = entrypointsRoot().relativize(source);
            if (relative.getNameCount() < 2) continue;
            found.add(relative.getName(relative.getNameCount() - 2).toString());
        }
        return found.stream().distinct().sorted().toList();
    }

    /// Selects the named entrypoint, defaulting to `web` or the only one.
    public Entrypoint entrypoint(String named) throws IOException {
        var module = entrypointModule().orElseThrow(() -> new IOException("no entrypoints/ module"));
        var candidates = new ArrayList<Entrypoint>();
        for (var source : module.sources()) {
            if (!source.getFileName().toString().equals("Main.java")) continue;
            var relative = entrypointsRoot().relativize(source);
            if (relative.getNameCount() < 2) continue;
            var name = relative.getName(relative.getNameCount() - 2).toString();
            candidates.add(new Entrypoint(name, module.name(), mainClass(source), source));
        }
        var selected = named == null || named.isBlank()
                ? candidates.stream().filter(value -> value.name().equals("web")).findFirst()
                        .or(() -> candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty())
                : candidates.stream().filter(value -> value.name().equals(named)).findFirst();
        return selected.orElseThrow(() -> new IOException("no entrypoint named " + named));
    }

    /// Selects the command entrypoint. `run` defaults to `cli`; the development
    /// host has its own web-first policy in [Dev].
    public Entrypoint runEntrypoint(String named) throws IOException {
        var selected = named == null || named.isBlank() ? "cli" : named;
        return entrypoint(selected);
    }

    /// Selects a source group in the entrypoint module for `tuul dev`.
    /// Reload discovers the actual entrypoint from the module's `provides`
    /// directive, so the group can contain a provider with any class name.
    public String reloadEntrypoint(String named) throws IOException {
        entrypointModule().orElseThrow(() -> new IOException("no entrypoints/ module"));
        var candidates = new ArrayList<String>();
        try (var children = Files.list(entrypointsRoot())) {
            for (var child : children.filter(Files::isDirectory).sorted().toList()) {
                try (var tree = Files.walk(child)) {
                    if (tree.anyMatch(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".java"))) {
                        candidates.add(child.getFileName().toString());
                    }
                }
            }
        }
        var selected = named == null || named.isBlank()
                ? (candidates.contains("web") ? "web" : candidates.size() == 1 ? candidates.getFirst() : "")
                : named;
        if (!selected.isBlank() && candidates.contains(selected)) return selected;
        throw new IOException("no development entrypoint named " + (named == null ? "" : named));
    }

    /// The test runner class. A test module has one `Run.java`.
    public String testMain() throws IOException {
        var module = testModule().orElseThrow(() -> new IOException("no test module"));
        for (var path : module.sources()) {
            if (path.getFileName().toString().equals("Run.java")) {
                return packageName(path).map(name -> name + ".Run").orElse("Run");
            }
        }
        throw new IOException("test module needs Run.java");
    }

    /// Packages owned by a test source root. The named test runner receives
    /// explicit exports for these packages when repository tests are patched
    /// into `tuul`.
    public List<String> testPackages() throws IOException {
        var module = testModule().orElseThrow(() -> new IOException("no test module"));
        return module.sources().stream()
                .filter(path -> !path.getFileName().toString().equals("Run.java"))
                .filter(path -> !path.getFileName().toString().equals("module-info.java"))
                .map(Layout::packageName).flatMap(Optional::stream).distinct().sorted().toList();
    }

    public record SourceModule(String name, Path root, Path descriptor, List<Path> sources) {
        public SourceModule { sources = List.copyOf(sources); }
    }

    public record Entrypoint(String name, String module, String mainClass, Path source) {}

    private SourceModule sourceModule(Path sourceRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot)) throw new IOException("no source root at " + sourceRoot);
        var descriptor = sourceRoot.resolve("module-info.java");
        if (!Files.isRegularFile(descriptor)) {
            throw new IOException("module-info.java is required at " + descriptor);
        }
        var found = sources(sourceRoot);
        return new SourceModule(moduleName(descriptor), sourceRoot, descriptor, found);
    }

    private static List<Path> sources(Path root) throws IOException {
        try (var tree = Files.walk(root)) {
            return tree.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String moduleName(Path descriptor) throws IOException {
        Matcher match = MODULE.matcher(stripComments(Files.readString(descriptor)));
        if (!match.find()) throw new IOException("module declaration is missing in " + descriptor);
        return match.group(1);
    }

    private static String mainClass(Path source) throws IOException {
        var packageName = packageName(source).orElse("");
        return packageName.isEmpty() ? "Main" : packageName + ".Main";
    }

    private static Optional<String> packageName(Path source) {
        try {
            return PACKAGE.matcher(stripComments(Files.readString(source))).results()
                    .findFirst().map(match -> match.group(1));
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /// A native module is a directory of C under `native/`, or a single `.c`
    /// file sitting directly in it. Either way it compiles to one library,
    /// named after the directory or the file.
    public Map<String, List<Path>> natives() throws IOException {
        var modules = new LinkedHashMap<String, List<Path>>();
        for (var directory : vendored()) collect(directory, modules);
        collect(nativeRoot(), modules);
        return modules;
    }

    private List<Path> vendored() throws IOException {
        if (!Files.isDirectory(vendor())) return List.of();
        try (var tree = Files.walk(vendor())) {
            return tree.filter(Files::isDirectory)
                    .filter(directory -> directory.getFileName().toString().equals("native")).sorted().toList();
        }
    }

    private static void collect(Path root, Map<String, List<Path>> modules) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var tree = Files.list(root)) {
            for (var path : tree.sorted().toList()) {
                if (Files.isDirectory(path)) cSources(path).ifPresent(files -> modules.put(name(path), files));
                else if (path.toString().endsWith(".c")) modules.put(stem(path), List.of(path));
            }
        }
    }

    private static Optional<List<Path>> cSources(Path directory) throws IOException {
        try (var tree = Files.walk(directory)) {
            var sources = tree.filter(path -> path.toString().endsWith(".c")).sorted().toList();
            return sources.isEmpty() ? Optional.empty() : Optional.of(sources);
        }
    }

    private static String name(Path path) { return path.getFileName().toString(); }
    private static String stem(Path path) {
        var file = name(path);
        return file.substring(0, file.length() - ".c".length());
    }
}
