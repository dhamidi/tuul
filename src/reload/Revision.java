package reload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/// One immutable named-module source closure and the program it produces.
public final class Revision {

    private final String identity;
    private final String rootModule;
    private final List<SourceModule> modules;
    private final List<Path> dependencies;
    private final Program program;

    private Revision(String identity, Program program) {
        this.identity = require(identity, "identity");
        rootModule = "";
        modules = List.of();
        dependencies = List.of();
        this.program = Objects.requireNonNull(program, "program");
    }

    private Revision(String identity, String rootModule, List<SourceModule> modules,
            List<Path> dependencies, Program program) {
        this.identity = require(identity, "identity");
        this.rootModule = require(rootModule, "root module");
        this.modules = List.copyOf(modules);
        if (this.modules.stream().noneMatch(module -> module.name().equals(rootModule))) {
            throw new IllegalArgumentException("root module is not in source closure: " + rootModule);
        }
        this.dependencies = paths(dependencies);
        this.program = program;
    }

    /// Creates an in-process revision with an explicit stable identity.
    public static Revision of(String identity, Program program) {
        return new Revision(identity, Objects.requireNonNull(program, "program"));
    }

    /// Creates an in-process revision with a process-local identity.
    public static Revision of(Program program) {
        return of("program-" + Integer.toHexString(System.identityHashCode(program)), program);
    }

    /// Computes an identity for a named project module closure.
    public static Revision from(String rootModule, List<SourceModule> modules,
            List<Path> dependencies) throws IOException {
        var normalized = modules == null ? List.<SourceModule>of() : List.copyOf(modules);
        var paths = dependencies == null ? List.<Path>of() : List.copyOf(dependencies);
        return new Revision(digest(rootModule, normalized, paths), rootModule, normalized, paths, null);
    }

    /// Returns the content or caller-supplied identity.
    public String identity() { return identity; }

    /// Returns the module whose resolved layer is passed to the generation factory.
    public String rootModule() { return rootModule; }

    /// Returns all source modules in the closure, including the root module.
    public List<SourceModule> modules() { return modules; }

    /// Returns dependency module paths acquired by the project manager.
    public List<Path> dependencies() { return dependencies; }

    /// Returns the attached host program, or null before compilation.
    public Program program() { return program; }

    /// Returns this revision with the host-owned program attached.
    public Revision withProgram(Program program) {
        return new Revision(identity, rootModule, modules, dependencies,
                Objects.requireNonNull(program, "program"));
    }

    /// One named module's descriptor, Java sources, and logical resources.
    public record SourceModule(String name, Path root, Path descriptor,
            List<Path> sources, List<ResourceEntry> resources) {
        public SourceModule {
            name = require(name, "module name");
            root = Objects.requireNonNull(root, "module root").toAbsolutePath().normalize();
            descriptor = Objects.requireNonNull(descriptor, "module descriptor")
                    .toAbsolutePath().normalize();
            sources = sources == null ? List.of() : sources.stream()
                    .map(path -> Objects.requireNonNull(path, "source").toAbsolutePath().normalize()).toList();
            resources = resources == null ? List.of() : List.copyOf(resources);
            if (!sources.contains(descriptor)) throw new IllegalArgumentException(
                    "module sources must contain its descriptor: " + name);
        }
    }

    /// One resource path and its name inside its named module.
    public record ResourceEntry(String name, Path path) {
        public ResourceEntry {
            name = require(name, "resource name").replace('\\', '/');
            if (name.startsWith("/") || name.endsWith("/") || name.contains("//")
                    || java.util.Arrays.stream(name.split("/", -1))
                            .anyMatch(part -> part.equals(".") || part.equals("..") || part.isEmpty())) {
                throw new IllegalArgumentException("resource name must be relative: " + name);
            }
            path = Objects.requireNonNull(path, "resource path").toAbsolutePath().normalize();
        }
    }

    private static List<Path> paths(List<Path> paths) {
        return paths == null ? List.of() : paths.stream()
                .map(path -> Objects.requireNonNull(path, "dependency")
                        .toAbsolutePath().normalize()).toList();
    }

    private static String digest(String rootModule, List<SourceModule> modules,
            List<Path> dependencies) throws IOException {
        var digest = sha256();
        digest.update(require(rootModule, "root module").getBytes(StandardCharsets.UTF_8));
        for (var module : modules.stream().sorted(java.util.Comparator.comparing(SourceModule::name)).toList()) {
            digest.update(module.name().getBytes(StandardCharsets.UTF_8));
            for (var source : module.sources().stream().sorted().toList()) {
                file(digest, "source", module.name() + "/" + module.root().relativize(source), source);
            }
            for (var resource : module.resources().stream()
                    .sorted(java.util.Comparator.comparing(ResourceEntry::name)).toList()) {
                file(digest, "resource", module.name() + "/" + resource.name(), resource.path());
            }
        }
        for (var dependency : dependencies.stream().map(Path::toAbsolutePath).sorted().toList()) {
            if (Files.isDirectory(dependency)) {
                try (var files = Files.walk(dependency)) {
                    files.filter(Files::isRegularFile).sorted().forEach(path -> {
                        try { file(digest, "dependency", dependency.relativize(path).toString(), path); }
                        catch (IOException failure) { throw new DigestFailure(failure); }
                    });
                } catch (DigestFailure failure) { throw failure.io; }
            } else {
                file(digest, "dependency", dependency.toString(), dependency);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class DigestFailure extends RuntimeException {
        private final IOException io;
        private DigestFailure(IOException io) { this.io = io; }
    }

    private static void file(MessageDigest digest, String kind, String name, Path path) throws IOException {
        digest.update(kind.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(name.replace('\\', '/').getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (var input = Files.newInputStream(path)) {
            var bytes = new byte[16 * 1024];
            for (var read = input.read(bytes); read >= 0; read = input.read(bytes)) {
                if (read > 0) digest.update(bytes, 0, read);
            }
        }
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
