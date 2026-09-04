package compiler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Compiles Java source paths and streams each class file to a [ClassSink].
///
/// Call [#system()] to use the compiler in the running JDK. A caller can supply
/// another compiler to control compilation and diagnostics.
@FunctionalInterface
public interface Compiler {

    /// Everything one named-module compiler invocation reads.
    record Request(List<Path> sources, List<Path> modulePath, String module, int release, boolean debug,
            Optional<Patch> patch, List<String> addExports, java.util.Map<String, Path> moduleSources) {

        public Request {
            sources = List.copyOf(sources);
            modulePath = List.copyOf(modulePath);
            if (module == null || module.isBlank()) throw new IllegalArgumentException("module must not be blank");
            patch = patch == null ? Optional.empty() : patch;
            addExports = addExports == null ? List.of() : List.copyOf(addExports);
            moduleSources = moduleSources == null ? java.util.Map.of() : java.util.Map.copyOf(moduleSources);
        }

        public Request(List<Path> sources, List<Path> modulePath, String module, int release,
                boolean debug, Optional<Patch> patch) {
            this(sources, modulePath, module, release, debug, patch, List.of(), java.util.Map.of());
        }

        public record Patch(String module, Path sourceRoot) {
            public Patch {
                if (module == null || module.isBlank()) throw new IllegalArgumentException("patch module must not be blank");
                if (sourceRoot == null) throw new NullPointerException("patch source root");
            }
        }
    }

    /// One error from the compiler. `source` is null when javac names no file.
    record Problem(Path source, long line, String message) {}

    /// The number of class files written and the errors that stopped the
    /// compilation. A successful result has no problems.
    record Result(int classes, List<Problem> problems) {

        public Result {
            problems = List.copyOf(problems);
        }

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /// Compiles one request. The method writes no class when the source list is
    /// empty. It returns compiler errors instead of throwing them.
    Result compile(Request request, ClassSink classes) throws IOException;

    /// Returns the compiler from the running JDK.
    static Compiler system() {
        return Javac.INSTANCE;
    }
}
