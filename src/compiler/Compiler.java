package compiler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Compiles Java source paths and streams each class file to a [ClassSink].
///
/// Call [#system()] to use the compiler in the running JDK. A caller can supply
/// another compiler to control compilation and diagnostics.
@FunctionalInterface
public interface Compiler {

    /// Everything one compiler invocation reads.
    record Request(List<Path> sources, List<Path> classpath, boolean module, int release, boolean debug) {

        public Request {
            sources = List.copyOf(sources);
            classpath = List.copyOf(classpath);
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
