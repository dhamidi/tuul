package reload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/// One immutable source input and the program it produces.
public final class Revision {

    private final String identity;
    private final Path root;
    private final String entrypoint;
    private final List<Path> sources;
    private final List<Path> resources;
    private final List<Path> dependencies;
    private final Program program;

    private Revision(String identity, Program program) {
        this(identity, null, "", List.of(), List.of(), List.of(), program);
    }

    /// Creates a path-backed revision with an identity that the caller already
    /// verified. Use [#from(Path, String, List, List, List)] to compute it.
    public Revision(String identity, Path root, String entrypoint, List<Path> sources,
            List<Path> resources, List<Path> dependencies) {
        this(identity, root, entrypoint, sources, resources, dependencies, null);
    }

    private Revision(String identity, Path root, String entrypoint, List<Path> sources,
            List<Path> resources, List<Path> dependencies, Program program) {
        this.identity = require(identity, "identity");
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.entrypoint = entrypoint == null ? "" : entrypoint;
        this.sources = paths(sources);
        this.resources = paths(resources);
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

    /// Computes a content identity from normalized entry names and bytes.
    public static Revision from(Path root, String entrypoint, List<Path> sources,
            List<Path> resources, List<Path> dependencies) throws IOException {
        var all = new java.util.ArrayList<Path>();
        all.addAll(sources);
        all.addAll(resources);
        all.addAll(dependencies);
        return new Revision(digest(root, all), root, entrypoint, sources, resources, dependencies);
    }

    /// Returns the content or caller-supplied revision identity.
    public String identity() { return identity; }
    /// Returns the normalized staging root, or null for an in-process revision.
    public Path root() { return root; }
    /// Returns the selected project entrypoint name.
    public String entrypoint() { return entrypoint; }
    /// Returns the normalized Java source paths.
    public List<Path> sources() { return sources; }
    /// Returns the normalized resource paths.
    public List<Path> resources() { return resources; }
    /// Returns the normalized compile and runtime dependency paths.
    public List<Path> dependencies() { return dependencies; }
    /// Returns the attached host program, or null before compilation.
    public Program program() { return program; }

    /// Returns this revision with the host-owned program attached. The source
    /// metadata and content identity remain unchanged. A compiler uses this
    /// method to keep materialization separate from loading and activation.
    public Revision withProgram(Program program) {
        return new Revision(identity, root, entrypoint, sources, resources, dependencies,
                Objects.requireNonNull(program, "program"));
    }

    private static List<Path> paths(List<Path> paths) {
        if (paths == null) return List.of();
        return paths.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    private static String digest(Path root, List<Path> entries) throws IOException {
        var digest = sha256();
        for (var entry : entries.stream().map(path -> path.toAbsolutePath().normalize()).sorted().toList()) {
            var name = root == null ? entry.toString() : root.toAbsolutePath().normalize().relativize(entry).toString();
            digest.update(name.replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (var input = Files.newInputStream(entry)) {
                var bytes = new byte[16 * 1024];
                for (var read = input.read(bytes); read >= 0; read = input.read(bytes)) {
                    if (read > 0) digest.update(bytes, 0, read);
                }
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
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
