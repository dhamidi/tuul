package compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/// The identity of one javac invocation.
///
/// Build and documentation use this value to recognize the same class output.
/// The inputs are the sources, binary dependencies, JDK release, and compiler
/// options that can change a class file.
public final class Compilation {

    private Compilation() {}

    /// Returns the SHA-256 identity of one compiler request.
    ///
    /// The digest includes normalized source and dependency paths. It includes
    /// each file's size and modification time. It also includes module mode,
    /// release, and debug output. Argument order does not change the result.
    public static String fingerprint(List<Path> sources, List<Path> dependencies,
            boolean module, int release, boolean debug) throws IOException {
        var digest = sha256();
        update(digest, "release=" + release);
        update(digest, "module=" + module);
        update(digest, "debug=" + debug);
        for (var source : sources.stream().sorted().toList()) file(digest, source);
        for (var dependency : dependencies.stream().sorted().toList()) file(digest, dependency);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void file(MessageDigest digest, Path path) throws IOException {
        update(digest, path.toAbsolutePath().normalize().toString());
        update(digest, String.valueOf(java.nio.file.Files.size(path)));
        update(digest, java.nio.file.Files.getLastModifiedTime(path).toInstant().toString());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
